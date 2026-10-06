package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.DeviceCredentialOperation;

import java.time.Instant;

public record DeviceCredentialAuditResponse(
        Long id,
        Long deviceId,
        String deviceCode,
        DeviceCredentialOperation operation,
        String previousSigningKeyFingerprint,
        String newSigningKeyFingerprint,
        Long changedByUserId,
        String changedByUsername,
        Instant changedAt
) {
}
