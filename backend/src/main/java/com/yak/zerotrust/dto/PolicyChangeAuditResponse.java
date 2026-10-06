package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.PolicyChangeOperation;

import java.time.Instant;

public record PolicyChangeAuditResponse(
        Long id,
        Long policyId,
        PolicyChangeOperation operation,
        PolicyChangeSnapshotResponse before,
        PolicyChangeSnapshotResponse after,
        Long changedByUserId,
        String changedByUsername,
        Instant changedAt
) {
}
