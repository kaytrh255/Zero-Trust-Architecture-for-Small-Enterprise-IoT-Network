package com.yak.zerotrust.security;

public record MfaLoginVerification(
        boolean successful,
        String username,
        UserPrincipal principal
) {
    public static MfaLoginVerification rejected(String username) {
        return new MfaLoginVerification(false, username, null);
    }

    public static MfaLoginVerification accepted(UserPrincipal principal) {
        return new MfaLoginVerification(true, principal.getUsername(), principal);
    }
}
