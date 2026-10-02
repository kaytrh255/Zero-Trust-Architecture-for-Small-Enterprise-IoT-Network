package com.yak.zerotrust.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaRecoveryCodeRotationRequest(
        @NotBlank @Size(max = 72) String password,
        @NotBlank @Size(max = 64) String code
) {
}
