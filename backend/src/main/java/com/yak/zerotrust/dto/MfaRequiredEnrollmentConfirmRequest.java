package com.yak.zerotrust.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaRequiredEnrollmentConfirmRequest(
        @NotBlank @Size(max = 4096) String enrollmentToken,
        @NotBlank @Size(max = 64) String code
) {
}
