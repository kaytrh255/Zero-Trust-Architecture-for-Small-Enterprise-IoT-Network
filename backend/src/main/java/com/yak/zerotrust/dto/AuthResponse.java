package com.yak.zerotrust.dto;

public record AuthResponse(
        boolean mfaRequired,
        String mfaToken,
        long challengeExpiresInSeconds,
        boolean mfaEnrollmentRequired,
        String enrollmentToken,
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UserResponse user
) {

    public static AuthResponse authenticated(String token, long expiresInSeconds, UserResponse user) {
        return new AuthResponse(false, null, 0, false, null, token, "Bearer", expiresInSeconds, user);
    }

    public static AuthResponse mfaChallenge(String token, long expiresInSeconds) {
        return new AuthResponse(true, token, expiresInSeconds, false, null, null, null, 0, null);
    }

    public static AuthResponse mfaEnrollmentChallenge(String token, long expiresInSeconds) {
        return new AuthResponse(false, null, expiresInSeconds, true, token, null, null, 0, null);
    }
}
