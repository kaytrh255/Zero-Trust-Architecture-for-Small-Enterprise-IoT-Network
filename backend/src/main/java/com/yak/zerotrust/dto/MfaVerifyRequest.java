package com.yak.zerotrust.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MfaVerifyRequest(
        @NotBlank @Size(max = 4096) String mfaToken,
        @NotBlank @Size(max = 64) String code
) {
}
