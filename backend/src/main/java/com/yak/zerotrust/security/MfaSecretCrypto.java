package com.yak.zerotrust.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class MfaSecretCrypto {

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public MfaSecretCrypto(@Value("${security.mfa.encryption-key}") String base64Key) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("MFA_ENCRYPTION_KEY must be a base64-encoded 256-bit key", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("MFA_ENCRYPTION_KEY must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String plaintext) {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array()
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt MFA secret", exception);
        }
    }

    public String decrypt(String ciphertext) {
        byte[] packed;
        try {
            packed = Base64.getUrlDecoder().decode(ciphertext);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Stored MFA secret is invalid", exception);
        }
        if (packed.length <= NONCE_LENGTH + TAG_LENGTH_BITS / 8) {
            throw new IllegalStateException("Stored MFA secret is truncated");
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        byte[] encrypted = new byte[packed.length - NONCE_LENGTH];
        ByteBuffer.wrap(packed).get(nonce).get(encrypted);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not decrypt MFA secret", exception);
        }
    }
}
