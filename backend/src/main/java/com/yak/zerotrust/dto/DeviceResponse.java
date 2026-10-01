package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.DeviceType;

import java.time.Instant;

public record DeviceResponse(
        Long id,
        String deviceCode,
        String deviceName,
        DeviceType deviceType,
        String ipAddress,
        String mqttClientId,
        boolean mqttSignatureEnabled,
        DeviceStatus status,
        Long ownerId,
        String ownerUsername,
        Instant createdAt,
        Instant lastSeenAt
) {
}
