package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.AuthenticationAttemptOutcome;

import java.time.Instant;

public record AuthenticationAttemptAuditResponse(
        Long id,
        String attemptedUsername,
        Long authenticatedUserId,
        AuthenticationAttemptOutcome outcome,
        Instant attemptedAt
) {
}
