package com.yak.zerotrust.dto;

import com.yak.zerotrust.access.AccessDecision;

import java.util.List;

public record ProtectedTelemetryResponse(
        AccessDecision accessDecision,
        List<TelemetryResponse> telemetry
) {
}
