package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.MfaSecurityAuditOperation;

import java.time.Instant;

public record MfaSecurityAuditResponse(
        Long id,
        Long userId,
        String username,
        MfaSecurityAuditOperation operation,
        Instant changedAt,
        Long actorUserId,
        String actorUsername
) {
}
