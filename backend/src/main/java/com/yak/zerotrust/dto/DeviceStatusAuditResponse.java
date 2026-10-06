package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.DeviceStatus;

import java.time.Instant;

public record DeviceStatusAuditResponse(
        Long id,
        Long deviceId,
        String deviceCode,
        DeviceStatus previousStatus,
        DeviceStatus newStatus,
        Long changedByUserId,
        String changedByUsername,
        Instant changedAt
) {
}
