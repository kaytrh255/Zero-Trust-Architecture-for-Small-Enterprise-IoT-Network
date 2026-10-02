package com.yak.zerotrust.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaEnrollmentRequest(
        @NotBlank @Size(max = 72) String password
) {
}
