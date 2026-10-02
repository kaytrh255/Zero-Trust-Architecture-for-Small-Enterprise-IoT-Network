package com.yak.zerotrust.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;

@Component
public class MfaTotpService {

    public static final long PERIOD_SECONDS = 30;
    private static final int SECRET_BYTES = 20;
    private static final int CODE_DIGITS = 6;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final SecureRandom random = new SecureRandom();

    public String generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return base32Encode(secret);
    }

    public OptionalLong findMatchingCounter(String secret, String candidate, Instant now, long lastAcceptedCounter) {
        if (candidate == null || !candidate.matches("[0-9]{6}")) {
            return OptionalLong.empty();
        }
        long currentCounter = Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS);
        long[] candidates = {currentCounter, currentCounter - 1, currentCounter + 1};
        byte[] supplied = candidate.getBytes(StandardCharsets.US_ASCII);
        for (long counter : candidates) {
            if (counter <= lastAcceptedCounter || counter < 0) {
                continue;
            }
            if (MessageDigest.isEqual(supplied, codeForCounter(secret, counter).getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(counter);
            }
        }
        return OptionalLong.empty();
    }

    public String generateCode(String secret, Instant at) {
        return codeForCounter(secret, Math.floorDiv(at.getEpochSecond(), PERIOD_SECONDS));
    }

    public String provisioningUri(String username, String secret) {
        String issuer = "Zero Trust IoT";
        String label = encodePath(issuer + ":" + username);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + encodeQuery(issuer)
                + "&algorithm=SHA1&digits=" + CODE_DIGITS + "&period=" + PERIOD_SECONDS;
    }

    static byte[] base32Decode(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        int buffer = 0;
        int bitsLeft = 0;
        byte[] result = new byte[(normalized.length() * 5) / 8];
        int index = 0;
        for (int position = 0; position < normalized.length(); position++) {
            char character = normalized.charAt(position);
            int digit = BASE32_ALPHABET.indexOf(character);
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid base32 MFA secret");
            }
            buffer = (buffer << 5) | digit;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[index++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        if (index != result.length || bitsLeft >= 5) {
            throw new IllegalArgumentException("Invalid base32 MFA secret");
        }
        return result;
    }

    static String codeForCounter(String secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not generate a time-based MFA code", exception);
        }
    }

    private String base32Encode(byte[] value) {
        StringBuilder result = new StringBuilder((value.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte next : value) {
            buffer = (buffer << 8) | (next & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                result.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            result.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return result.toString();
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
