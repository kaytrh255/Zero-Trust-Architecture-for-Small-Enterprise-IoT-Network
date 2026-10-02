package com.yak.zerotrust.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaRequiredEnrollmentRequest(
        @NotBlank @Size(max = 4096) String enrollmentToken
) {
}
