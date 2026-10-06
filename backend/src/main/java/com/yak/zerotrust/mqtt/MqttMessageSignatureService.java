package com.yak.zerotrust.mqtt;

import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Creates per-device Ed25519 keys and verifies signatures over the exact MQTT payload bytes. */
@Service
public class MqttMessageSignatureService {

    private static final Pattern BASE64URL = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final int ED25519_SIGNATURE_BYTES = 64;
    private static final Base64.Encoder BASE64URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    public DeviceSigningKeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair keyPair = generator.generateKeyPair();
            return new DeviceSigningKeyPair(
                    BASE64URL_ENCODER.encodeToString(keyPair.getPublic().getEncoded()),
                    BASE64URL_ENCODER.encodeToString(keyPair.getPrivate().getEncoded())
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Ed25519 key generation is unavailable", exception);
        }
    }

    /** Returns a stable SHA-256 fingerprint of the public-key bytes, never private key material. */
    public String fingerprintPublicKey(String encodedPublicKey) {
        byte[] publicKeyBytes = decodeCanonicalBase64Url(encodedPublicKey);
        if (publicKeyBytes == null) {
            throw new IllegalArgumentException("MQTT signing public key must use canonical unpadded base64url encoding");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKeyBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Decodes only canonical, unpadded base64url values used by the MQTT envelope. */
    public byte[] decodePayload(String encodedPayload) {
        byte[] decoded = decodeCanonicalBase64Url(encodedPayload);
        if (decoded == null) {
            throw new IllegalArgumentException("Signed MQTT payload must use unpadded base64url encoding");
        }
        return decoded;
    }

    public boolean verify(byte[] payload, String encodedSignature, String encodedPublicKey) {
        if (payload == null) {
            return false;
        }
        byte[] signatureBytes = decodeCanonicalBase64Url(encodedSignature);
        byte[] publicKeyBytes = decodeCanonicalBase64Url(encodedPublicKey);
        if (signatureBytes == null || signatureBytes.length != ED25519_SIGNATURE_BYTES || publicKeyBytes == null) {
            return false;
        }

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            var publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            return verifier.verify(signatureBytes);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] decodeCanonicalBase64Url(String value) {
        if (value == null || value.isBlank() || !BASE64URL.matcher(value).matches()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return BASE64URL_ENCODER.encodeToString(decoded).equals(value) ? decoded : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
