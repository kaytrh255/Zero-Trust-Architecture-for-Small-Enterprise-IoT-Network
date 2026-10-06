package com.yak.zerotrust.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MfaTotpServiceTest {

    private final MfaTotpService service = new MfaTotpService();

    @Test
    void matchesRfc6238Sha1SixDigitVector() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"; // Base32 for RFC 6238's test key.

        assertThat(service.generateCode(secret, Instant.ofEpochSecond(59))).isEqualTo("287082");
    }

    @Test
    void acceptsOnlyUnseenCodesWithinOneStepOfTheCurrentCounter() {
        String secret = service.generateSecret();
        Instant now = Instant.ofEpochSecond(3_000);
        long counter = now.getEpochSecond() / MfaTotpService.PERIOD_SECONDS;
        String code = service.generateCode(secret, now);

        assertThat(service.findMatchingCounter(secret, code, now, counter - 1).orElse(-1))
                .isEqualTo(counter);
        assertThat(service.findMatchingCounter(secret, code, now, counter).isPresent()).isFalse();
        assertThat(service.findMatchingCounter(secret, "not-a-code", now, -1).isPresent()).isFalse();
    }

    @Test
    void createsBase32SecretsAndAnAuthenticatorProvisioningUri() {
        String secret = service.generateSecret();

        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(service.provisioningUri("alice@example", secret))
                .startsWith("otpauth://totp/Zero%20Trust%20IoT%3Aalice%40example?")
                .contains("secret=" + secret)
                .contains("algorithm=SHA1&digits=6&period=30");
    }
}
