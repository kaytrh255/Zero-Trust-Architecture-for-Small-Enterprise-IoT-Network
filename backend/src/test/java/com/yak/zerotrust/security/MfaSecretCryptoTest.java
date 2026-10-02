package com.yak.zerotrust.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaSecretCryptoTest {

    @Test
    void encryptsSecretsWithAuthenticatedEncryptionAndFreshNonces() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        MfaSecretCrypto crypto = new MfaSecretCrypto(key);
        String secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

        String first = crypto.encrypt(secret);
        String second = crypto.encrypt(secret);

        assertThat(first).isNotEqualTo(secret).isNotEqualTo(second);
        assertThat(crypto.decrypt(first)).isEqualTo(secret);
        assertThat(crypto.decrypt(second)).isEqualTo(secret);
        String tampered = (first.charAt(0) == 'A' ? 'B' : 'A') + first.substring(1);
        assertThatThrownBy(() -> crypto.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidEncryptionKeys() {
        assertThatThrownBy(() -> new MfaSecretCrypto("not-base64"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MfaSecretCrypto(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
