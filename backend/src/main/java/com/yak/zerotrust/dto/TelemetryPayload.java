package com.yak.zerotrust.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TelemetryPayload(
        Long sequence,
        String metric,
        BigDecimal value,
        String unit,
        Instant measuredAt
) {
}
