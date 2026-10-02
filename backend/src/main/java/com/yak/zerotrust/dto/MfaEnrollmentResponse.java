package com.yak.zerotrust.dto;

import java.time.Instant;

public record MfaEnrollmentResponse(
        String secret,
        String otpauthUri,
        Instant expiresAt
) {
}
