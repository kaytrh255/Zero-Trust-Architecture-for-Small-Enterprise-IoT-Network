package com.yak.zerotrust.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderTest {

    @Test
    void bcryptStoresAHashAndVerifiesTheOriginalPassword() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String rawPassword = "StudentPass123!";
        String passwordHash = encoder.encode(rawPassword);

        assertThat(passwordHash).isNotEqualTo(rawPassword);
        assertThat(encoder.matches(rawPassword, passwordHash)).isTrue();
        assertThat(encoder.matches("wrong-password", passwordHash)).isFalse();
    }
}
