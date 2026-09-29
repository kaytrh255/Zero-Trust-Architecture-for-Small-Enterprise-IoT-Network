package com.yak.zerotrust.service;

import com.yak.zerotrust.access.AccessChannel;
import com.yak.zerotrust.access.AccessContext;
import com.yak.zerotrust.access.AccessDecisionOutcome;
import com.yak.zerotrust.access.AccessEvaluation;
import com.yak.zerotrust.dto.ProtectedTelemetryResponse;
import com.yak.zerotrust.dto.TelemetryResponse;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProtectedResourceService {

    private final ZeroTrustDecisionService zeroTrustDecisionService;
    private final TelemetryQueryService telemetryQueryService;

    public ProtectedResourceService(
            ZeroTrustDecisionService zeroTrustDecisionService,
            TelemetryQueryService telemetryQueryService
    ) {
        this.zeroTrustDecisionService = zeroTrustDecisionService;
        this.telemetryQueryService = telemetryQueryService;
    }

    @Transactional
    public ProtectedTelemetryResponse readDeviceTelemetry(String deviceCode, UserPrincipal principal) {
        AccessContext context = new AccessContext(
                principal.getId(),
                principal.getUsername(),
                principal.getRole(),
                AccessChannel.API,
                deviceCode,
                null,
                null,
                null,
                "sensor-data",
                PolicyAction.READ
        );
        AccessEvaluation evaluation = zeroTrustDecisionService.evaluate(context);
        if (evaluation.decision().decision() != AccessDecisionOutcome.ALLOW) {
            return new ProtectedTelemetryResponse(evaluation.decision(), List.of());
        }

        if (evaluation.device() == null) {
            throw new IllegalStateException("An ALLOW decision must include its registered device");
        }
        List<TelemetryResponse> telemetry = telemetryQueryService
                .getRecentForDevice(evaluation.device().getDeviceCode());
        return new ProtectedTelemetryResponse(evaluation.decision(), telemetry);
    }
}
