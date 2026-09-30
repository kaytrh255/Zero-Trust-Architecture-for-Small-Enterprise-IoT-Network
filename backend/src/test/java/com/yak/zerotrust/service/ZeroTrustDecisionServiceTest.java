package com.yak.zerotrust.service;

import com.yak.zerotrust.access.AccessChannel;
import com.yak.zerotrust.access.AccessContext;
import com.yak.zerotrust.access.AccessDecision;
import com.yak.zerotrust.access.AccessDecisionOutcome;
import com.yak.zerotrust.access.AccessDecisionReason;
import com.yak.zerotrust.access.AccessEvaluation;
import com.yak.zerotrust.entity.AccessAudit;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.DeviceType;
import com.yak.zerotrust.entity.Policy;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.PolicyEffect;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.policy.PolicyEvaluationService;
import com.yak.zerotrust.repository.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZeroTrustDecisionServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private PolicyEvaluationService policyEvaluationService;

    @Mock
    private AccessAuditService accessAuditService;

    @InjectMocks
    private ZeroTrustDecisionService zeroTrustDecisionService;

    @BeforeEach
    void persistAuditForAssertions() {
        when(accessAuditService.record(any(AccessContext.class), any(AccessDecision.class)))
                .thenAnswer(invocation -> new AccessAudit(
                        invocation.getArgument(0),
                        invocation.getArgument(1)
                ));
    }

    @Test
    void allowsAnActiveDeviceWhenAnAllowPolicyMatches() {
        Device device = device(DeviceStatus.ACTIVE);
        when(deviceRepository.findByDeviceCodeForUpdate("SENSOR-001")).thenReturn(Optional.of(device));
        Policy allow = policy("Sensor Read Data", "sensor-data", PolicyAction.READ, PolicyEffect.ALLOW);
        when(policyEvaluationService.findApplicablePolicy("SENSOR", "sensor-data", PolicyAction.READ))
                .thenReturn(Optional.of(allow));

        AccessEvaluation evaluation = zeroTrustDecisionService.evaluate(apiContext(
                UserRole.USER, "sensor-001", "SENSOR-DATA", PolicyAction.READ
        ));

        assertThat(evaluation.decision().decision()).isEqualTo(AccessDecisionOutcome.ALLOW);
        assertThat(evaluation.decision().reason()).isEqualTo(AccessDecisionReason.POLICY_ALLOW);
        assertThat(evaluation.decision().matchedPolicyName()).isEqualTo("Sensor Read Data");
        assertThat(evaluation.device()).isSameAs(device);
        verify(accessAuditService).record(any(AccessContext.class), any(AccessDecision.class));
    }

    @Test
    void acceptsOnlyNewerMqttSequencesAndRecordsTheHighWaterMark() {
        Device device = device(DeviceStatus.ACTIVE);
        when(deviceRepository.findByDeviceCodeForUpdate("SENSOR-001")).thenReturn(Optional.of(device));
        when(policyEvaluationService.findApplicablePolicy("SENSOR", "device-telemetry", PolicyAction.WRITE))
                .thenReturn(Optional.of(policy("Sensor Publish Telemetry", "device-telemetry", PolicyAction.WRITE, PolicyEffect.ALLOW)));

        AccessEvaluation first = zeroTrustDecisionService.evaluate(mqttContext("SENSOR-001", 4L));

        assertThat(first.decision().decision()).isEqualTo(AccessDecisionOutcome.ALLOW);
        assertThat(device.getLastMqttSequence()).isEqualTo(4L);
        verify(accessAuditService).record(any(AccessContext.class), any(AccessDecision.class));
    }

    @Test
    void deniesAndAuditsAReplayedMqttSequenceAfterExplicitPolicyEvaluation() {
        Device device = device(DeviceStatus.ACTIVE);
        device.recordMqttSequence(8L);
        when(deviceRepository.findByDeviceCodeForUpdate("SENSOR-001")).thenReturn(Optional.of(device));
        when(policyEvaluationService.findApplicablePolicy("SENSOR", "device-telemetry", PolicyAction.WRITE))
                .thenReturn(Optional.of(policy("Sensor Publish Telemetry", "device-telemetry", PolicyAction.WRITE, PolicyEffect.ALLOW)));

        AccessEvaluation replay = zeroTrustDecisionService.evaluate(mqttContext("SENSOR-001", 8L));

        assertThat(replay.decision().decision()).isEqualTo(AccessDecisionOutcome.DENY);
        assertThat(replay.decision().reason()).isEqualTo(AccessDecisionReason.REPLAYED_MESSAGE);
        assertThat(device.getLastMqttSequence()).isEqualTo(8L);
        ArgumentCaptor<AccessContext> contextCaptor = ArgumentCaptor.forClass(AccessContext.class);
        ArgumentCaptor<AccessDecision> decisionCaptor = ArgumentCaptor.forClass(AccessDecision.class);
        verify(accessAuditService).record(contextCaptor.capture(), decisionCaptor.capture());
        assertThat(contextCaptor.getValue().messageSequence()).isEqualTo(8L);
        assertThat(decisionCaptor.getValue().reason()).isEqualTo(AccessDecisionReason.REPLAYED_MESSAGE);
    }

    @Test
    void deniesAnExplicitDenyPolicy() {
        when(deviceRepository.findByDeviceCodeForUpdate("SENSOR-001"))
                .thenReturn(Optional.of(device(DeviceStatus.ACTIVE)));
        Policy deny = policy("Sensor Cannot Write Camera", "camera-stream", PolicyAction.WRITE, PolicyEffect.DENY);
        when(policyEvaluationService.findApplicablePolicy("SENSOR", "camera-stream", PolicyAction.WRITE))
                .thenReturn(Optional.of(deny));

        AccessEvaluation evaluation = zeroTrustDecisionService.evaluate(apiContext(
                UserRole.USER, "SENSOR-001", "camera-stream", PolicyAction.WRITE
        ));

        assertThat(evaluation.decision().decision()).isEqualTo(AccessDecisionOutcome.DENY);
        assertThat(evaluation.decision().reason()).isEqualTo(AccessDecisionReason.EXPLICIT_DENY);
        assertThat(evaluation.decision().matchedPolicyName()).isEqualTo("Sensor Cannot Write Camera");
    }

    @Test
    void defaultsToDenyWhenThereIsNoApplicablePolicy() {
        when(deviceRepository.findByDeviceCodeForUpdate("SENSOR-001"))
                .thenReturn(Optional.of(device(DeviceStatus.ACTIVE)));
        when(policyEvaluationService.findApplicablePolicy("SENSOR", "unknown-resource", PolicyAction.READ))
                .thenReturn(Optional.empty());

        AccessDecision decision = zeroTrustDecisionService.evaluate(apiContext(
                UserRole.USER, "SENSOR-001", "unknown-resource", PolicyAction.READ
        )).decision();

        assertThat(decision.decision()).isEqualTo(AccessDecisionOutcome.DENY);
        assertThat(decision.reason()).isEqualTo(AccessDecisionReason.NO_MATCHING_POLICY);
        assertThat(decision.matchedPolicyId()).isNull();
    }

    @Test
    void blocksInactiveDevicesBeforePolicyEvaluation() {
        when(deviceRepository.findByDeviceCodeForUpdate("SENSOR-002"))
                .thenReturn(Optional.of(device(DeviceStatus.BLOCKED)));

        AccessDecision decision = zeroTrustDecisionService.evaluate(apiContext(
                UserRole.USER, "SENSOR-002", "sensor-data", PolicyAction.READ
        )).decision();

        assertThat(decision.decision()).isEqualTo(AccessDecisionOutcome.DENY);
        assertThat(decision.reason()).isEqualTo(AccessDecisionReason.DEVICE_NOT_ACTIVE);
        verify(policyEvaluationService, never()).findApplicablePolicy(any(), any(), any());
    }

    @Test
    void deniesAndAuditsUnknownDevices() {
        when(deviceRepository.findByDeviceCodeForUpdate("UNKNOWN-001")).thenReturn(Optional.empty());

        AccessEvaluation evaluation = zeroTrustDecisionService.evaluate(apiContext(
                UserRole.USER, "unknown-001", "sensor-data", PolicyAction.READ
        ));

        assertThat(evaluation.decision().decision()).isEqualTo(AccessDecisionOutcome.DENY);
        assertThat(evaluation.decision().reason()).isEqualTo(AccessDecisionReason.DEVICE_NOT_FOUND);
        assertThat(evaluation.decision().deviceCode()).isEqualTo("UNKNOWN-001");
        verify(accessAuditService).record(any(AccessContext.class), any(AccessDecision.class));
    }

    @Test
    void deniesManagementRolesFromActingAsResourceRequesters() {
        AccessDecision decision = zeroTrustDecisionService.evaluate(apiContext(
                UserRole.ADMIN, "SENSOR-001", "sensor-data", PolicyAction.READ
        )).decision();

        assertThat(decision.decision()).isEqualTo(AccessDecisionOutcome.DENY);
        assertThat(decision.reason()).isEqualTo(AccessDecisionReason.REQUESTER_ROLE_NOT_ALLOWED);
        verify(deviceRepository, never()).findByDeviceCodeForUpdate(any());
        verify(policyEvaluationService, never()).findApplicablePolicy(any(), any(), any());
    }

    @Test
    void storesTheEvaluatedIdentityAndDecisionInTheAuditRecord() {
        when(deviceRepository.findByDeviceCodeForUpdate("SENSOR-001"))
                .thenReturn(Optional.of(device(DeviceStatus.ACTIVE)));
        when(policyEvaluationService.findApplicablePolicy("SENSOR", "sensor-data", PolicyAction.READ))
                .thenReturn(Optional.of(policy("Sensor Read Data", "sensor-data", PolicyAction.READ, PolicyEffect.ALLOW)));

        zeroTrustDecisionService.evaluate(apiContext(UserRole.USER, "SENSOR-001", "sensor-data", PolicyAction.READ));

        ArgumentCaptor<AccessContext> contextCaptor = ArgumentCaptor.forClass(AccessContext.class);
        ArgumentCaptor<AccessDecision> decisionCaptor = ArgumentCaptor.forClass(AccessDecision.class);
        verify(accessAuditService).record(contextCaptor.capture(), decisionCaptor.capture());
        assertThat(contextCaptor.getValue().requesterUsername()).isEqualTo("student1");
        assertThat(contextCaptor.getValue().requesterRole()).isEqualTo(UserRole.USER);
        assertThat(contextCaptor.getValue().deviceStatus()).isEqualTo(DeviceStatus.ACTIVE);
        assertThat(decisionCaptor.getValue().decision()).isEqualTo(AccessDecisionOutcome.ALLOW);
    }

    private AccessContext apiContext(UserRole role, String deviceCode, String resource, PolicyAction action) {
        return new AccessContext(
                7L,
                "student1",
                role,
                AccessChannel.API,
                deviceCode,
                null,
                null,
                null,
                resource,
                action,
                null
        );
    }

    private AccessContext mqttContext(String deviceCode, Long sequence) {
        return new AccessContext(
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
                sequence
        );
    }

    private Policy policy(String name, String resource, PolicyAction action, PolicyEffect effect) {
        return new Policy(name, "SENSOR", resource, action, effect, true, null);
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
        setEntityId(device, 1L);
        return device;
    }

    private void setEntityId(Object entity, Long id) {
        try {
            Field idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to prepare persisted test entity", exception);
        }
    }
}
