package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.UserRole;
import jakarta.validation.constraints.NotNull;

public record UserAccountUpdateRequest(
        @NotNull UserRole role,
        @NotNull Boolean enabled
) {
}
