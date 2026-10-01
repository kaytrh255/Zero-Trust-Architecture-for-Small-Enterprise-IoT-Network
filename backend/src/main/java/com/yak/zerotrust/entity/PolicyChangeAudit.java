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
@Table(name = "policy_change_audits")
public class PolicyChangeAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PolicyChangeOperation operation;

    @Column(name = "before_name", length = 100)
    private String beforeName;

    @Column(name = "before_subject", length = 50)
    private String beforeSubject;

    @Column(name = "before_resource", length = 100)
    private String beforeResource;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_action", length = 20)
    private PolicyAction beforeAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_effect", length = 10)
    private PolicyEffect beforeEffect;

    @Column(name = "before_enabled")
    private Boolean beforeEnabled;

    @Column(name = "before_description", length = 500)
    private String beforeDescription;

    @Column(name = "after_name", length = 100)
    private String afterName;

    @Column(name = "after_subject", length = 50)
    private String afterSubject;

    @Column(name = "after_resource", length = 100)
    private String afterResource;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_action", length = 20)
    private PolicyAction afterAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_effect", length = 10)
    private PolicyEffect afterEffect;

    @Column(name = "after_enabled")
    private Boolean afterEnabled;

    @Column(name = "after_description", length = 500)
    private String afterDescription;

    @Column(name = "changed_by_user_id", nullable = false)
    private Long changedByUserId;

    @Column(name = "changed_by_username", nullable = false, length = 50)
    private String changedByUsername;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected PolicyChangeAudit() {
    }

    public PolicyChangeAudit(
            Long policyId,
            PolicyChangeOperation operation,
            PolicySnapshot before,
            PolicySnapshot after,
            Long changedByUserId,
            String changedByUsername
    ) {
        this.policyId = policyId;
        this.operation = operation;
        copyBefore(before);
        copyAfter(after);
        this.changedByUserId = changedByUserId;
        this.changedByUsername = changedByUsername;
        changedAt = Instant.now();
    }

    private void copyBefore(PolicySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        beforeName = snapshot.name();
        beforeSubject = snapshot.subject();
        beforeResource = snapshot.resource();
        beforeAction = snapshot.action();
        beforeEffect = snapshot.effect();
        beforeEnabled = snapshot.enabled();
        beforeDescription = snapshot.description();
    }

    private void copyAfter(PolicySnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        afterName = snapshot.name();
        afterSubject = snapshot.subject();
        afterResource = snapshot.resource();
        afterAction = snapshot.action();
        afterEffect = snapshot.effect();
        afterEnabled = snapshot.enabled();
        afterDescription = snapshot.description();
    }

    public Long getId() {
        return id;
    }

    public Long getPolicyId() {
        return policyId;
    }

    public PolicyChangeOperation getOperation() {
        return operation;
    }

    public PolicySnapshot getBeforeSnapshot() {
        if (beforeName == null) {
            return null;
        }
        return new PolicySnapshot(
                beforeName,
                beforeSubject,
                beforeResource,
                beforeAction,
                beforeEffect,
                Boolean.TRUE.equals(beforeEnabled),
                beforeDescription
        );
    }

    public PolicySnapshot getAfterSnapshot() {
        if (afterName == null) {
            return null;
        }
        return new PolicySnapshot(
                afterName,
                afterSubject,
                afterResource,
                afterAction,
                afterEffect,
                Boolean.TRUE.equals(afterEnabled),
                afterDescription
        );
    }

    public Long getChangedByUserId() {
        return changedByUserId;
    }

    public String getChangedByUsername() {
        return changedByUsername;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
