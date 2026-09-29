package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.PolicyAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record AccessCheckRequest(
        @NotBlank(message = "Device code is required")
        @Pattern(
                regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$",
                message = "Device code contains unsupported characters"
        )
        String deviceCode,

        @NotBlank(message = "Resource is required")
        @Pattern(
                regexp = "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}$",
                message = "Resource contains unsupported characters"
        )
        String resource,

        @NotNull(message = "Action is required")
        PolicyAction action
) {
}
