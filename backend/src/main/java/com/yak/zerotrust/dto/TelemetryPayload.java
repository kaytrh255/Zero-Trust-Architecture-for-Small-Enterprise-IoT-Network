package com.yak.zerotrust.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TelemetryPayload(
        String deviceToken,
        String metric,
        BigDecimal value,
        String unit,
        Instant measuredAt
) {
}
