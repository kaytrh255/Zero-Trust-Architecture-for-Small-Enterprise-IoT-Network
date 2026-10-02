package com.yak.zerotrust.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "mfa_login_challenges")
public class MfaLoginChallenge {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private MfaLoginChallengePurpose purpose;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected MfaLoginChallenge() {
    }

    public MfaLoginChallenge(String id, Long userId, String username, Instant expiresAt) {
        this(id, userId, username, MfaLoginChallengePurpose.LOGIN, expiresAt);
    }

    public MfaLoginChallenge(
            String id,
            Long userId,
            String username,
            MfaLoginChallengePurpose purpose,
            Instant expiresAt
    ) {
        this.id = id;
        this.userId = userId;
        this.username = username;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
    }

    public String getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public MfaLoginChallengePurpose getPurpose() {
        return purpose;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public boolean isUsableAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now) {
        if (consumedAt != null) {
            throw new IllegalStateException("MFA challenge was already consumed");
        }
        consumedAt = now;
    }
}
