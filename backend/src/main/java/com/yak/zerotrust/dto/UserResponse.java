package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.UserRole;

public record UserResponse(
        Long id,
        String username,
        String fullName,
        UserRole role,
        boolean enabled
) {
}
