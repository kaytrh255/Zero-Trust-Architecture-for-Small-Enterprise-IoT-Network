package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.UserAccountAuditOperation;
import com.yak.zerotrust.entity.UserRole;

import java.time.Instant;

public record UserAccountAuditResponse(
        Long id,
        UserAccountAuditOperation operation,
        Long targetUserId,
        String targetUsername,
        UserRole previousRole,
        UserRole newRole,
        boolean previousEnabled,
        boolean newEnabled,
        Long actorUserId,
        String actorUsername,
        Instant changedAt
) {
}
