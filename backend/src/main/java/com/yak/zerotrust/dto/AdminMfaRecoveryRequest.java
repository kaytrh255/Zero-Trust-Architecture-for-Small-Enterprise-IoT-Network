package com.yak.zerotrust.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminMfaRecoveryRequest(
        @NotBlank @Size(max = 50) String targetUsername,
        @NotBlank @Size(max = 72) String password,
        @NotBlank @Pattern(regexp = "[0-9]{6}") String code
) {
}
