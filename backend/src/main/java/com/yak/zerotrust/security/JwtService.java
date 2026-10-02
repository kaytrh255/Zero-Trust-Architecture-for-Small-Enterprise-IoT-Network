package com.yak.zerotrust.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    public static final long MFA_CHALLENGE_EXPIRATION_SECONDS = 300;
    public static final long MFA_ENROLLMENT_CHALLENGE_EXPIRATION_SECONDS = 600;
    private static final String TOKEN_USE_CLAIM = "token_use";
    private static final String ACCESS_TOKEN_USE = "access";
    private static final String MFA_CHALLENGE_TOKEN_USE = "mfa_challenge";
    private static final String MFA_ENROLLMENT_TOKEN_USE = "mfa_enrollment";
    private static final String MFA_AUTH_VERSION_CLAIM = "mfa_auth_version";
    private static final String MFA_CHALLENGE_ID_CLAIM = "mfa_challenge_id";

    private final SecretKey signingKey;
    private final long expirationSeconds;

    public JwtService(
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.expiration-seconds}") long expirationSeconds
    ) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
        }
        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("JWT_EXPIRATION_SECONDS must be positive");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.expirationSeconds = expirationSeconds;
    }

    public String generateToken(String username) {
        return generateToken(username, 0);
    }

    public String generateToken(String username, int mfaAuthVersion) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(expirationSeconds);

        return Jwts.builder()
                .subject(username)
                .claim(TOKEN_USE_CLAIM, ACCESS_TOKEN_USE)
                .claim(MFA_AUTH_VERSION_CLAIM, mfaAuthVersion)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    public String generateMfaChallengeToken(String username, String challengeId) {
        return generatePurposeToken(username, challengeId, MFA_CHALLENGE_TOKEN_USE, MFA_CHALLENGE_EXPIRATION_SECONDS);
    }

    public String generateMfaEnrollmentToken(String username, String challengeId) {
        return generatePurposeToken(
                username,
                challengeId,
                MFA_ENROLLMENT_TOKEN_USE,
                MFA_ENROLLMENT_CHALLENGE_EXPIRATION_SECONDS
        );
    }

    private String generatePurposeToken(String username, String challengeId, String purpose, long ttlSeconds) {
        Instant issuedAt = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim(TOKEN_USE_CLAIM, purpose)
                .claim(MFA_CHALLENGE_ID_CLAIM, challengeId)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusSeconds(ttlSeconds)))
                .signWith(signingKey)
                .compact();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public MfaChallengeClaims extractMfaChallenge(String token) {
        return extractChallenge(token, MFA_CHALLENGE_TOKEN_USE);
    }

    public MfaChallengeClaims extractMfaEnrollmentChallenge(String token) {
        return extractChallenge(token, MFA_ENROLLMENT_TOKEN_USE);
    }

    private MfaChallengeClaims extractChallenge(String token, String expectedPurpose) {
        Claims claims = parseClaims(token);
        if (!expectedPurpose.equals(claims.get(TOKEN_USE_CLAIM, String.class))) {
            throw new JwtException("Invalid MFA challenge token");
        }
        String username = claims.getSubject();
        String challengeId = claims.get(MFA_CHALLENGE_ID_CLAIM, String.class);
        if (username == null || challengeId == null) {
            throw new JwtException("Invalid MFA challenge token");
        }
        return new MfaChallengeClaims(username, challengeId);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            Claims claims = parseClaims(token);
            Object rawVersion = claims.get(MFA_AUTH_VERSION_CLAIM);
            int tokenVersion = rawVersion instanceof Number number ? number.intValue() : -1;
            int currentVersion = userDetails instanceof UserPrincipal principal
                    ? principal.getMfaAuthVersion()
                    : 0;
            return ACCESS_TOKEN_USE.equals(claims.get(TOKEN_USE_CLAIM, String.class))
                    && claims.getSubject() != null
                    && claims.getSubject().equals(userDetails.getUsername())
                    && tokenVersion == currentVersion
                    && claims.getExpiration() != null
                    && claims.getExpiration().toInstant().isAfter(Instant.now());
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public long getMfaChallengeExpirationSeconds() {
        return MFA_CHALLENGE_EXPIRATION_SECONDS;
    }

    public long getMfaEnrollmentChallengeExpirationSeconds() {
        return MFA_ENROLLMENT_CHALLENGE_EXPIRATION_SECONDS;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
