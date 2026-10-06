package com.yak.zerotrust.controller;

import com.yak.zerotrust.access.AccessDecisionOutcome;
import com.yak.zerotrust.dto.ProtectedTelemetryResponse;
import com.yak.zerotrust.security.UserPrincipal;
import com.yak.zerotrust.service.ProtectedResourceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resources/devices")
public class ProtectedResourceController {

    private final ProtectedResourceService protectedResourceService;

    public ProtectedResourceController(ProtectedResourceService protectedResourceService) {
        this.protectedResourceService = protectedResourceService;
    }

    @GetMapping("/{deviceCode}/telemetry")
    public ResponseEntity<ProtectedTelemetryResponse> readDeviceTelemetry(
            @PathVariable String deviceCode,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        ProtectedTelemetryResponse response = protectedResourceService.readDeviceTelemetry(deviceCode, principal);
        HttpStatus status = response.accessDecision().decision() == AccessDecisionOutcome.ALLOW
                ? HttpStatus.OK
                : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(response);
    }
}
