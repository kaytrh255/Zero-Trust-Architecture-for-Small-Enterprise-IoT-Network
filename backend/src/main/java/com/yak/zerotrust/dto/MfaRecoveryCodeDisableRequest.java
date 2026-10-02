package com.yak.zerotrust.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaRecoveryCodeDisableRequest(
        @NotBlank @Size(max = 72) String password,
        @NotBlank @Size(max = 64) String recoveryCode
) {
}
