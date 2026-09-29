package com.yak.zerotrust.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TelemetryResponse(
        Long id,
        String deviceCode,
        String metric,
        BigDecimal value,
        String unit,
        Instant measuredAt,
        Instant receivedAt
) {
}
