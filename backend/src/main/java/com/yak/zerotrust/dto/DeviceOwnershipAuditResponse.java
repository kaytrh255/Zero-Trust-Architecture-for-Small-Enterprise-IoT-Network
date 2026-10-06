package com.yak.zerotrust.dto;

import java.time.Instant;

public record DeviceOwnershipAuditResponse(
        Long id,
        Long deviceId,
        String deviceCode,
        Long previousOwnerId,
        String previousOwnerUsername,
        Long newOwnerId,
        String newOwnerUsername,
        Long changedByUserId,
        String changedByUsername,
        Instant changedAt
) {
}
