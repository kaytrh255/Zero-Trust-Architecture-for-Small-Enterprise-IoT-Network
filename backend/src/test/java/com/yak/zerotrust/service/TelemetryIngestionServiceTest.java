package com.yak.zerotrust.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yak.zerotrust.access.AccessChannel;
import com.yak.zerotrust.access.AccessContext;
import com.yak.zerotrust.access.AccessDecision;
import com.yak.zerotrust.access.AccessDecisionOutcome;
import com.yak.zerotrust.access.AccessDecisionReason;
import com.yak.zerotrust.access.AccessEvaluation;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.DeviceTelemetry;
import com.yak.zerotrust.entity.DeviceType;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.repository.DeviceTelemetryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryIngestionServiceTest {

    @Mock
    private ZeroTrustDecisionService zeroTrustDecisionService;

    @Mock
    private DeviceTelemetryRepository telemetryRepository;

    @Test
    void persistsTelemetryOnlyAfterAnAllowDecision() {
        Device device = device(DeviceStatus.ACTIVE);
        when(zeroTrustDecisionService.evaluate(any())).thenReturn(new AccessEvaluation(
                decision(AccessDecisionOutcome.ALLOW, AccessDecisionReason.POLICY_ALLOW), device
        ));
        TelemetryIngestionService service = service();

        boolean stored = service.ingestMqttMessage(
                "iot/telemetry/sensor-001",
                payload(3).getBytes(StandardCharsets.UTF_8)
        );

        assertThat(stored).isTrue();
        ArgumentCaptor<DeviceTelemetry> telemetryCaptor = ArgumentCaptor.forClass(DeviceTelemetry.class);
        verify(telemetryRepository).save(telemetryCaptor.capture());
        assertThat(telemetryCaptor.getValue().getMetric()).isEqualTo("temperature");
        assertThat(telemetryCaptor.getValue().getDeviceCode()).isEqualTo("SENSOR-001");
        assertThat(telemetryCaptor.getValue().getDeviceSequence()).isEqualTo(3L);
        assertThat(device.getLastSeenAt()).isNotNull();
        ArgumentCaptor<AccessContext> contextCaptor = ArgumentCaptor.forClass(AccessContext.class);
        verify(zeroTrustDecisionService).evaluate(contextCaptor.capture());
        assertThat(contextCaptor.getValue().requesterUsername()).isEqualTo("mqtt:SENSOR-001");
        assertThat(contextCaptor.getValue().deviceCode()).isEqualTo("SENSOR-001");
        assertThat(contextCaptor.getValue().channel()).isEqualTo(AccessChannel.MQTT);
        assertThat(contextCaptor.getValue().messageSequence()).isEqualTo(3L);
    }

    @Test
    void doesNotPersistTelemetryWhenTheDeviceIsDeniedByPolicyOrStatus() {
        Device device = device(DeviceStatus.BLOCKED);
        when(zeroTrustDecisionService.evaluate(any())).thenReturn(new AccessEvaluation(
                decision(AccessDecisionOutcome.DENY, AccessDecisionReason.DEVICE_NOT_ACTIVE), device
        ));
        TelemetryIngestionService service = service();

        boolean stored = service.ingestMqttMessage(
                "iot/telemetry/SENSOR-002",
                payload(1).getBytes(StandardCharsets.UTF_8)
        );

        assertThat(stored).isFalse();
        verify(telemetryRepository, never()).save(any(DeviceTelemetry.class));
        assertThat(device.getLastSeenAt()).isNull();
    }

    @Test
    void rejectsMessagesOutsideTheExpectedTopicNamespace() {
        TelemetryIngestionService service = service();

        assertThatThrownBy(() -> service.ingestMqttMessage(
                "other/SENSOR-001",
                payload(1).getBytes(StandardCharsets.UTF_8)
        )).isInstanceOf(IllegalArgumentException.class);

        verify(zeroTrustDecisionService, never()).evaluate(any());
    }

    @Test
    void rejectsMessagesWithoutAPositiveSequenceNumber() {
        TelemetryIngestionService service = service();

        assertThatThrownBy(() -> service.ingestMqttMessage(
                "iot/telemetry/SENSOR-001",
                payload(0).getBytes(StandardCharsets.UTF_8)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");

        verify(zeroTrustDecisionService, never()).evaluate(any());
        verify(telemetryRepository, never()).save(any(DeviceTelemetry.class));
    }

    private TelemetryIngestionService service() {
        return new TelemetryIngestionService(
                new ObjectMapper(),
                zeroTrustDecisionService,
                telemetryRepository
        );
    }

    private String payload(long sequence) {
        return "{\"sequence\":" + sequence
                + ",\"metric\":\"Temperature\",\"value\":22.5,\"unit\":\"C\"}";
    }

    private AccessDecision decision(AccessDecisionOutcome outcome, AccessDecisionReason reason) {
        return new AccessDecision(
                1L,
                outcome,
                reason,
                "SENSOR-001",
                "device-telemetry",
                PolicyAction.WRITE,
                1L,
                "Sensor Publish Telemetry",
                Instant.now()
        );
    }

    private Device device(DeviceStatus status) {
        UserAccount owner = new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true);
        Device device = new Device(
                status == DeviceStatus.BLOCKED ? "SENSOR-002" : "SENSOR-001",
                "Test sensor",
                DeviceType.SENSOR,
                "192.168.10.21",
                status == DeviceStatus.BLOCKED ? "SENSOR-002" : "SENSOR-001",
                owner
        );
        device.changeStatus(status);
        return device;
    }
}
