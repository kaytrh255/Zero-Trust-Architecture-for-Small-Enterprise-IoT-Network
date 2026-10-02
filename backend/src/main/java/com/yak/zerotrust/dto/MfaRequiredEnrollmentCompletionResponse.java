package com.yak.zerotrust.dto;

import java.util.List;

public record MfaRequiredEnrollmentCompletionResponse(
        AuthResponse session,
        List<String> recoveryCodes
) {
}
