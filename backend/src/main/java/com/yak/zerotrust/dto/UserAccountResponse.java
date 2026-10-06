package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.UserRole;

import java.time.Instant;

public record UserAccountResponse(
        Long id,
        String username,
        String fullName,
        UserRole role,
        boolean enabled,
        boolean mfaEnabled,
        Instant createdAt,
        Instant updatedAt
) {
}
