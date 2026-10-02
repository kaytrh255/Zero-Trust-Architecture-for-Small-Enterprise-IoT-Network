package com.yak.zerotrust.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String TEST_SECRET = "test-only-secret-with-more-than-32-bytes-for-hmac";

    @Test
    void tokenIsValidOnlyForItsSubjectAndPurpose() {
        JwtService jwtService = new JwtService(TEST_SECRET, 3600);
        String token = jwtService.generateToken("alice");

        var alice = User.withUsername("alice").password("unused").roles("USER").build();
        var bob = User.withUsername("bob").password("unused").roles("USER").build();

        assertThat(jwtService.extractUsername(token)).isEqualTo("alice");
        assertThat(jwtService.isTokenValid(token, alice)).isTrue();
        assertThat(jwtService.isTokenValid(token, bob)).isFalse();

        String loginChallenge = jwtService.generateMfaChallengeToken("alice", "login-challenge-id");
        assertThat(jwtService.isTokenValid(loginChallenge, alice)).isFalse();
        assertThat(jwtService.extractMfaChallenge(loginChallenge).challengeId()).isEqualTo("login-challenge-id");
        assertThatThrownBy(() -> jwtService.extractMfaEnrollmentChallenge(loginChallenge))
                .isInstanceOf(JwtException.class);

        String enrollmentChallenge = jwtService.generateMfaEnrollmentToken("alice", "enrollment-challenge-id");
        assertThat(jwtService.isTokenValid(enrollmentChallenge, alice)).isFalse();
        assertThat(jwtService.extractMfaEnrollmentChallenge(enrollmentChallenge).challengeId())
                .isEqualTo("enrollment-challenge-id");
        assertThatThrownBy(() -> jwtService.extractMfaChallenge(enrollmentChallenge))
                .isInstanceOf(JwtException.class);
        assertThat(jwtService.getMfaEnrollmentChallengeExpirationSeconds()).isEqualTo(600);

        assertThat(jwtService.isTokenValid("not-a-jwt", alice)).isFalse();

        JwtService otherKeyService = new JwtService("another-test-key-with-more-than-32-bytes-for-hmac", 3600);
        String tokenWithWrongSignature = otherKeyService.generateToken("alice");
        assertThat(jwtService.isTokenValid(tokenWithWrongSignature, alice)).isFalse();
    }
}
