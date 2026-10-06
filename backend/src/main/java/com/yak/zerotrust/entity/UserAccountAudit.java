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
@Table(name = "user_account_audits")
public class UserAccountAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserAccountAuditOperation operation;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Column(name = "target_username", nullable = false, length = 50)
    private String targetUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_role", nullable = false, length = 30)
    private UserRole previousRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_role", nullable = false, length = 30)
    private UserRole newRole;

    @Column(name = "previous_enabled", nullable = false)
    private boolean previousEnabled;

    @Column(name = "new_enabled", nullable = false)
    private boolean newEnabled;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "actor_username", nullable = false, length = 50)
    private String actorUsername;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected UserAccountAudit() {
    }

    public UserAccountAudit(
            UserAccount target,
            UserRole previousRole,
            boolean previousEnabled,
            UserRole newRole,
            boolean newEnabled,
            UserAccount actor
    ) {
        operation = UserAccountAuditOperation.ACCOUNT_UPDATED;
        targetUserId = target.getId();
        targetUsername = target.getUsername();
        this.previousRole = previousRole;
        this.newRole = newRole;
        this.previousEnabled = previousEnabled;
        this.newEnabled = newEnabled;
        actorUserId = actor.getId();
        actorUsername = actor.getUsername();
        changedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UserAccountAuditOperation getOperation() {
        return operation;
    }

    public Long getTargetUserId() {
        return targetUserId;
    }

    public String getTargetUsername() {
        return targetUsername;
    }

    public UserRole getPreviousRole() {
        return previousRole;
    }

    public UserRole getNewRole() {
        return newRole;
    }

    public boolean isPreviousEnabled() {
        return previousEnabled;
    }

    public boolean isNewEnabled() {
        return newEnabled;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
