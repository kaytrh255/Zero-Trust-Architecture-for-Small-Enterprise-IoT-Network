package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.PolicyEffect;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PolicyRequest(
        @NotBlank(message = "Policy name is required")
        @Size(max = 100, message = "Policy name must be at most 100 characters")
        String name,

        @NotBlank(message = "Policy subject is required")
        @Pattern(
                regexp = "^[A-Za-z0-9._-]{1,50}$",
                message = "Subject must use letters, numbers, dot, underscore, or hyphen"
        )
        String subject,

        @NotBlank(message = "Policy resource is required")
        @Pattern(
                regexp = "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}$",
                message = "Resource contains unsupported characters"
        )
        String resource,

        @NotNull(message = "Policy action is required")
        PolicyAction action,

        @NotNull(message = "Policy effect is required")
        PolicyEffect effect,

        @NotNull(message = "Policy enabled state is required")
        Boolean enabled,

        @Size(max = 500, message = "Description must be at most 500 characters")
        String description
) {
}
