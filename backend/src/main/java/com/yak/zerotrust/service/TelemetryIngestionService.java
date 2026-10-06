package com.yak.zerotrust.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yak.zerotrust.access.AccessChannel;
import com.yak.zerotrust.access.AccessContext;
import com.yak.zerotrust.access.AccessDecision;
import com.yak.zerotrust.access.AccessDecisionOutcome;
import com.yak.zerotrust.access.AccessDecisionReason;
import com.yak.zerotrust.access.AccessEvaluation;
import com.yak.zerotrust.dto.SignedMqttTelemetryEnvelope;
import com.yak.zerotrust.dto.TelemetryPayload;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceTelemetry;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.mqtt.MqttMessageSignatureService;
import com.yak.zerotrust.repository.DeviceRepository;
import com.yak.zerotrust.repository.DeviceTelemetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TelemetryIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestionService.class);
    private static final int MAX_ENVELOPE_BYTES = 4096;
    private static final int MAX_SIGNED_PAYLOAD_BYTES = 2048;
    private static final Pattern TOPIC_PATTERN = Pattern.compile(
            "^iot/telemetry/([A-Za-z0-9][A-Za-z0-9._-]{0,63})$"
    );
    private static final Pattern METRIC_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9._-]{0,63}$");
    private static final Pattern UNIT_PATTERN = Pattern.compile("^[A-Za-z0-9%°./_-]{1,16}$");

    private final ObjectMapper objectMapper;
    private final ZeroTrustDecisionService zeroTrustDecisionService;
    private final AccessAuditService accessAuditService;
    private final DeviceRepository deviceRepository;
    private final DeviceTelemetryRepository telemetryRepository;
    private final MqttMessageSignatureService messageSignatureService;

    public TelemetryIngestionService(
            ObjectMapper objectMapper,
            ZeroTrustDecisionService zeroTrustDecisionService,
            AccessAuditService accessAuditService,
            DeviceRepository deviceRepository,
            DeviceTelemetryRepository telemetryRepository,
            MqttMessageSignatureService messageSignatureService
    ) {
        this.objectMapper = objectMapper;
        this.zeroTrustDecisionService = zeroTrustDecisionService;
        this.accessAuditService = accessAuditService;
        this.deviceRepository = deviceRepository;
        this.telemetryRepository = telemetryRepository;
        this.messageSignatureService = messageSignatureService;
    }

    @Transactional
    public boolean ingestMqttMessage(String topic, byte[] envelopeBytes) {
        String deviceCode = extractDeviceCode(topic);
        SignedMqttTelemetryEnvelope envelope = parseEnvelope(envelopeBytes);
        byte[] signedPayload = messageSignatureService.decodePayload(envelope.payload());
        if (signedPayload.length == 0 || signedPayload.length > MAX_SIGNED_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Signed MQTT telemetry payload must be 1-2048 bytes");
        }

        Optional<Device> registeredDevice = deviceRepository.findByDeviceCodeForUpdate(deviceCode);
        if (registeredDevice.isEmpty()) {
            // Preserve the normal DEVICE_NOT_FOUND access audit without trusting unsigned message fields.
            zeroTrustDecisionService.evaluate(context(deviceCode, null, null));
            return false;
        }

        Device device = registeredDevice.get();
        if (!messageSignatureService.verify(
                signedPayload,
                envelope.signature(),
                device.getMqttSigningPublicKey()
        )) {
            recordInvalidSignature(device);
            log.warn("Rejected MQTT telemetry with an invalid Ed25519 signature for device {}", deviceCode);
            return false;
        }

        TelemetryPayload payload = parseTelemetryPayload(signedPayload);
        validatePayload(payload);

        AccessEvaluation evaluation = zeroTrustDecisionService.evaluate(
                context(deviceCode, device, payload.sequence())
        );
        if (evaluation.decision().decision() != AccessDecisionOutcome.ALLOW) {
            log.info("MQTT telemetry denied for device {} at sequence {}: {}",
                    deviceCode, payload.sequence(), evaluation.decision().reason());
            return false;
        }

        Device evaluatedDevice = evaluation.device();
        if (evaluatedDevice == null) {
            // The decision service fails closed for an unknown device; this is a defensive guard.
            return false;
        }

        Instant receivedAt = Instant.now();
        Instant measuredAt = payload.measuredAt() == null ? receivedAt : payload.measuredAt();
        String metric = payload.metric().trim().toLowerCase(Locale.ROOT);
        String unit = payload.unit().trim();

        telemetryRepository.save(new DeviceTelemetry(
                evaluatedDevice,
                evaluatedDevice.getDeviceCode(),
                payload.sequence(),
                metric,
                payload.value(),
                unit,
                measuredAt,
                receivedAt,
                topic
        ));
        evaluatedDevice.recordTelemetryReceived(receivedAt);
        return true;
    }

    private String extractDeviceCode(String topic) {
        Matcher matcher = TOPIC_PATTERN.matcher(topic == null ? "" : topic);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("MQTT topic must use iot/telemetry/{deviceCode}");
        }
        return matcher.group(1).toUpperCase(Locale.ROOT);
    }

    private SignedMqttTelemetryEnvelope parseEnvelope(byte[] envelopeBytes) {
        if (envelopeBytes == null || envelopeBytes.length == 0 || envelopeBytes.length > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("MQTT signed envelope must be 1-4096 bytes");
        }
        try {
            SignedMqttTelemetryEnvelope envelope = objectMapper.readValue(
                    envelopeBytes,
                    SignedMqttTelemetryEnvelope.class
            );
            if (envelope == null || envelope.payload() == null || envelope.signature() == null) {
                throw new IllegalArgumentException("MQTT signed envelope must contain payload and signature");
            }
            return envelope;
        } catch (IOException exception) {
            throw new IllegalArgumentException("MQTT signed envelope must be valid JSON", exception);
        }
    }

    private TelemetryPayload parseTelemetryPayload(byte[] signedPayload) {
        try {
            return objectMapper.readValue(signedPayload, TelemetryPayload.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Signed MQTT telemetry payload must be valid JSON", exception);
        }
    }

    private AccessContext context(String deviceCode, Device device, Long sequence) {
        return new AccessContext(
                null,
                "mqtt:" + deviceCode,
                UserRole.DEVICE,
                AccessChannel.MQTT,
                device == null ? deviceCode : device.getDeviceCode(),
                device == null ? null : device.getId(),
                device == null ? null : device.getDeviceType(),
                device == null ? null : device.getStatus(),
                "device-telemetry",
                PolicyAction.WRITE,
                sequence
        );
    }

    private void recordInvalidSignature(Device device) {
        String deviceCode = device.getDeviceCode();
        AccessContext context = context(deviceCode, device, null);
        AccessDecision decision = new AccessDecision(
                null,
                AccessDecisionOutcome.DENY,
                AccessDecisionReason.INVALID_DEVICE_CREDENTIAL,
                deviceCode,
                "device-telemetry",
                PolicyAction.WRITE,
                null,
                null,
                Instant.now()
        );
        accessAuditService.record(context, decision);
    }

    private void validatePayload(TelemetryPayload payload) {
        if (payload == null || payload.sequence() == null || payload.sequence() <= 0) {
            throw new IllegalArgumentException("Telemetry sequence must be a positive integer");
        }
        if (payload.metric() == null || !METRIC_PATTERN.matcher(payload.metric().trim()).matches()) {
            throw new IllegalArgumentException("Telemetry metric is missing or invalid");
        }
        if (payload.value() == null || !fitsDatabaseNumeric(payload.value())) {
            throw new IllegalArgumentException("Telemetry value must fit NUMERIC(18,6)");
        }
        if (payload.unit() == null || !UNIT_PATTERN.matcher(payload.unit().trim()).matches()) {
            throw new IllegalArgumentException("Telemetry unit is missing or invalid");
        }
        if (payload.measuredAt() != null && payload.measuredAt().isAfter(Instant.now().plusSeconds(300))) {
            throw new IllegalArgumentException("Telemetry measurement time cannot be more than five minutes in the future");
        }
    }

    private boolean fitsDatabaseNumeric(BigDecimal value) {
        return value.scale() <= 6 && value.precision() <= 18 && value.precision() - value.scale() <= 12;
    }
}
