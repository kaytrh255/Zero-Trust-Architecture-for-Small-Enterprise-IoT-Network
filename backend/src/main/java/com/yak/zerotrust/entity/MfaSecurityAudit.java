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
@Table(name = "mfa_security_audits")
public class MfaSecurityAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MfaSecurityAuditOperation operation;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_username", length = 50)
    private String actorUsername;

    protected MfaSecurityAudit() {
    }

    /** Retained for compatibility with legacy audit records without actor attribution. */
    public MfaSecurityAudit(Long userId, String username, MfaSecurityAuditOperation operation) {
        this(userId, username, operation, null, null);
    }

    public MfaSecurityAudit(
            Long userId,
            String username,
            MfaSecurityAuditOperation operation,
            Long actorUserId,
            String actorUsername
    ) {
        this.userId = userId;
        this.username = username;
        this.operation = operation;
        this.changedAt = Instant.now();
        this.actorUserId = actorUserId;
        this.actorUsername = actorUsername;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public MfaSecurityAuditOperation getOperation() {
        return operation;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getActorUsername() {
        return actorUsername;
    }
}
