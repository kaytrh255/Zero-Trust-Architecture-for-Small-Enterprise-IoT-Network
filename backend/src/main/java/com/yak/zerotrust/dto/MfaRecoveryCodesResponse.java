package com.yak.zerotrust.dto;

import java.util.List;

public record MfaRecoveryCodesResponse(
        List<String> recoveryCodes,
        MfaStatusResponse status
) {
    public MfaRecoveryCodesResponse {
        recoveryCodes = List.copyOf(recoveryCodes);
    }
}
