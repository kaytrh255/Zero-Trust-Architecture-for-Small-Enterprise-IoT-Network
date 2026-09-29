package com.yak.zerotrust.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "policies", uniqueConstraints = {
        @UniqueConstraint(name = "uk_policies_name", columnNames = "name")
})
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String subject;

    @Column(nullable = false, length = 100)
    private String resource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PolicyAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PolicyEffect effect;

    @Column(nullable = false)
    private boolean enabled;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Policy() {
    }

    public Policy(
            String name,
            String subject,
            String resource,
            PolicyAction action,
            PolicyEffect effect,
            boolean enabled,
            String description
    ) {
        this.name = name;
        this.subject = subject;
        this.resource = resource;
        this.action = action;
        this.effect = effect;
        this.enabled = enabled;
        this.description = description;
    }

    @PrePersist
    void setCreationTimestamps() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }

    public void updateDetails(
            String name,
            String subject,
            String resource,
            PolicyAction action,
            PolicyEffect effect,
            boolean enabled,
            String description
    ) {
        this.name = name;
        this.subject = subject;
        this.resource = resource;
        this.action = action;
        this.effect = effect;
        this.enabled = enabled;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSubject() {
        return subject;
    }

    public String getResource() {
        return resource;
    }

    public PolicyAction getAction() {
        return action;
    }

    public PolicyEffect getEffect() {
        return effect;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
