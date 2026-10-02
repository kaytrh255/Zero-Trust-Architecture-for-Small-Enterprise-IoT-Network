package com.yak.zerotrust.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DeviceOwnerRequest(
        @NotBlank(message = "Owner username is required")
        @Pattern(
                regexp = "^[\\s]{0,10}[A-Za-z0-9._-]{3,50}[\\s]{0,10}$",
                message = "Owner username must contain 3-50 letters, numbers, dot, underscore, or hyphen; surrounding whitespace is allowed"
        )
        String ownerUsername
) {
}
