package com.yak.zerotrust.dto;

import java.time.Instant;

public record MfaStatusResponse(
        boolean enabled,
        boolean enrollmentPending,
        Instant enrollmentExpiresAt,
        long recoveryCodesRemaining
) {
}
