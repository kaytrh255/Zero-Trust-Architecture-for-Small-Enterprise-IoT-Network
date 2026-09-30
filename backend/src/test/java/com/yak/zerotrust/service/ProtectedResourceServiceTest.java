package com.yak.zerotrust.service;

import com.yak.zerotrust.access.AccessChannel;
import com.yak.zerotrust.access.AccessContext;
import com.yak.zerotrust.access.AccessDecision;
import com.yak.zerotrust.access.AccessDecisionOutcome;
import com.yak.zerotrust.access.AccessDecisionReason;
import com.yak.zerotrust.access.AccessEvaluation;
import com.yak.zerotrust.dto.ProtectedTelemetryResponse;
import com.yak.zerotrust.dto.TelemetryResponse;
import com.yak.zerotrust.entity.Device;
import com.yak.zerotrust.entity.DeviceType;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import com.yak.zerotrust.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProtectedResourceServiceTest {

    @Mock
    private ZeroTrustDecisionService zeroTrustDecisionService;

    @Mock
    private TelemetryQueryService telemetryQueryService;

    @Test
    void returnsTelemetryOnlyAfterAnAllowDecision() {
        Device device = device("SENSOR-001", DeviceType.SENSOR);
        AccessDecision decision = decision(AccessDecisionOutcome.ALLOW, AccessDecisionReason.POLICY_ALLOW);
        when(zeroTrustDecisionService.evaluate(any())).thenReturn(new AccessEvaluation(decision, device));
        List<TelemetryResponse> samples = List.of(new TelemetryResponse(
                12L,
                "SENSOR-001",
                1L,
                "temperature",
                new BigDecimal("22.5"),
                "C",
                Instant.now(),
                Instant.now()
        ));
        when(telemetryQueryService.getRecentForDevice("SENSOR-001")).thenReturn(samples);
        ProtectedResourceService service = new ProtectedResourceService(
                zeroTrustDecisionService,
                telemetryQueryService
        );

        ProtectedTelemetryResponse response = service.readDeviceTelemetry(
                "sensor-001",
                principal(UserRole.USER)
        );

        assertThat(response.accessDecision()).isEqualTo(decision);
        assertThat(response.telemetry()).isEqualTo(samples);
        ArgumentCaptor<AccessContext> contextCaptor = ArgumentCaptor.forClass(AccessContext.class);
        verify(zeroTrustDecisionService).evaluate(contextCaptor.capture());
        assertThat(contextCaptor.getValue().requesterUsername()).isEqualTo("student1");
        assertThat(contextCaptor.getValue().requesterRole()).isEqualTo(UserRole.USER);
        assertThat(contextCaptor.getValue().channel()).isEqualTo(AccessChannel.API);
        assertThat(contextCaptor.getValue().deviceCode()).isEqualTo("sensor-001");
        assertThat(contextCaptor.getValue().resource()).isEqualTo("sensor-data");
        assertThat(contextCaptor.getValue().action()).isEqualTo(PolicyAction.READ);
        verify(telemetryQueryService).getRecentForDevice("SENSOR-001");
    }

    @Test
    void returnsNoTelemetryAndNeverQueriesTheResourceWhenDenied() {
        AccessDecision decision = decision(AccessDecisionOutcome.DENY, AccessDecisionReason.DEVICE_NOT_ACTIVE);
        when(zeroTrustDecisionService.evaluate(any())).thenReturn(new AccessEvaluation(decision, null));
        ProtectedResourceService service = new ProtectedResourceService(
                zeroTrustDecisionService,
                telemetryQueryService
        );

        ProtectedTelemetryResponse response = service.readDeviceTelemetry(
                "SENSOR-002",
                principal(UserRole.USER)
        );

        assertThat(response.accessDecision()).isEqualTo(decision);
        assertThat(response.telemetry()).isEmpty();
        verify(telemetryQueryService, never()).getRecentForDevice(any());
    }

    private UserPrincipal principal(UserRole role) {
        return new UserPrincipal(new UserAccount(
                "student1",
                "hash",
                "Student One",
                role,
                true
        ));
    }

    private Device device(String code, DeviceType type) {
        return new Device(
                code,
                "Test device",
                type,
                "192.168.10.21",
                code,
                new UserAccount("admin", "hash", "Administrator", UserRole.ADMIN, true)
        );
    }

    private AccessDecision decision(AccessDecisionOutcome outcome, AccessDecisionReason reason) {
        return new AccessDecision(
                101L,
                outcome,
                reason,
                "SENSOR-001",
                "sensor-data",
                PolicyAction.READ,
                outcome == AccessDecisionOutcome.ALLOW ? 1L : null,
                outcome == AccessDecisionOutcome.ALLOW ? "Sensor Read Data" : null,
                Instant.now()
        );
    }
}
