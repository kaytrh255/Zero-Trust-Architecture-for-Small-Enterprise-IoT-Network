package com.yak.zerotrust.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yak.zerotrust.access.AccessChannel;
import com.yak.zerotrust.access.AccessContext;
import com.yak.zerotrust.access.AccessDecisionOutcome;
import com.yak.zerotrust.access.AccessEvaluation;
import com.yak.zerotrust.dto.TelemetryPayload;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceTelemetry;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.repository.DeviceTelemetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TelemetryIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestionService.class);
    private static final int MAX_PAYLOAD_BYTES = 2048;
    private static final Pattern TOPIC_PATTERN = Pattern.compile(
            "^iot/telemetry/([A-Za-z0-9][A-Za-z0-9._-]{0,63})$"
    );
    private static final Pattern METRIC_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9._-]{0,63}$");
    private static final Pattern UNIT_PATTERN = Pattern.compile("^[A-Za-z0-9%°./_-]{1,16}$");

    private final ObjectMapper objectMapper;
    private final ZeroTrustDecisionService zeroTrustDecisionService;
    private final DeviceTelemetryRepository telemetryRepository;

    public TelemetryIngestionService(
            ObjectMapper objectMapper,
            ZeroTrustDecisionService zeroTrustDecisionService,
            DeviceTelemetryRepository telemetryRepository
    ) {
        this.objectMapper = objectMapper;
        this.zeroTrustDecisionService = zeroTrustDecisionService;
        this.telemetryRepository = telemetryRepository;
    }

    @Transactional
    public boolean ingestMqttMessage(String topic, byte[] payloadBytes) {
        String deviceCode = extractDeviceCode(topic);
        TelemetryPayload payload = parsePayload(payloadBytes);
        validatePayload(payload);

        AccessContext accessContext = new AccessContext(
                null,
                "mqtt:" + deviceCode,
                UserRole.DEVICE,
                AccessChannel.MQTT,
                deviceCode,
                null,
                null,
                null,
                "device-telemetry",
                PolicyAction.WRITE,
                payload.sequence()
        );
        AccessEvaluation evaluation = zeroTrustDecisionService.evaluate(accessContext);
        if (evaluation.decision().decision() != AccessDecisionOutcome.ALLOW) {
            log.info("MQTT telemetry denied for device {} at sequence {}: {}",
                    deviceCode, payload.sequence(), evaluation.decision().reason());
            return false;
        }

        Device device = evaluation.device();
        if (device == null) {
            // The decision service fails closed for an unknown device; this is a defensive guard.
            return false;
        }

        Instant receivedAt = Instant.now();
        Instant measuredAt = payload.measuredAt() == null ? receivedAt : payload.measuredAt();
        String metric = payload.metric().trim().toLowerCase(Locale.ROOT);
        String unit = payload.unit().trim();

        telemetryRepository.save(new DeviceTelemetry(
                device,
                device.getDeviceCode(),
                payload.sequence(),
                metric,
                payload.value(),
                unit,
                measuredAt,
                receivedAt,
                topic
        ));
        device.recordTelemetryReceived(receivedAt);
        return true;
    }

    private String extractDeviceCode(String topic) {
        Matcher matcher = TOPIC_PATTERN.matcher(topic == null ? "" : topic);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("MQTT topic must use iot/telemetry/{deviceCode}");
        }
        return matcher.group(1).toUpperCase(Locale.ROOT);
    }

    private TelemetryPayload parsePayload(byte[] payloadBytes) {
        if (payloadBytes == null || payloadBytes.length == 0 || payloadBytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("MQTT telemetry payload must be 1-2048 bytes");
        }
        try {
            return objectMapper.readValue(new String(payloadBytes, StandardCharsets.UTF_8), TelemetryPayload.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("MQTT telemetry payload must be valid JSON", exception);
        }
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
