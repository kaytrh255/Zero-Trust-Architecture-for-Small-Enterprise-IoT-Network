package com.yak.zerotrust.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "authentication_attempt_audits")
public class AuthenticationAttemptAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempted_username", nullable = false, length = 255, updatable = false)
    private String attemptedUsername;

    @Column(name = "authenticated_user_id", updatable = false)
    private Long authenticatedUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 7, updatable = false)
    private AuthenticationAttemptOutcome outcome;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    protected AuthenticationAttemptAudit() {
    }

    public AuthenticationAttemptAudit(
            String attemptedUsername,
            Long authenticatedUserId,
            AuthenticationAttemptOutcome outcome
    ) {
        this.attemptedUsername = attemptedUsername;
        this.authenticatedUserId = authenticatedUserId;
        this.outcome = outcome;
        attemptedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getAttemptedUsername() {
        return attemptedUsername;
    }

    public Long getAuthenticatedUserId() {
        return authenticatedUserId;
    }

    public AuthenticationAttemptOutcome getOutcome() {
        return outcome;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
