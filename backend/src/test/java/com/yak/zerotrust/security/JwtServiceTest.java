package com.yak.zerotrust.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String TEST_SECRET = "test-only-secret-with-more-than-32-bytes-for-hmac";

    @Test
    void tokenIsValidOnlyForItsSubject() {
        JwtService jwtService = new JwtService(TEST_SECRET, 3600);
        String token = jwtService.generateToken("alice");

        var alice = User.withUsername("alice").password("unused").roles("USER").build();
        var bob = User.withUsername("bob").password("unused").roles("USER").build();

        assertThat(jwtService.extractUsername(token)).isEqualTo("alice");
        assertThat(jwtService.isTokenValid(token, alice)).isTrue();
        assertThat(jwtService.isTokenValid(token, bob)).isFalse();
        assertThat(jwtService.isTokenValid("not-a-jwt", alice)).isFalse();

        JwtService otherKeyService = new JwtService("another-test-key-with-more-than-32-bytes-for-hmac", 3600);
        String tokenWithWrongSignature = otherKeyService.generateToken("alice");
        assertThat(jwtService.isTokenValid(tokenWithWrongSignature, alice)).isFalse();
    }
}
