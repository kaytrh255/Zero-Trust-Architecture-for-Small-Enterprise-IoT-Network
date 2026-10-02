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
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_username", columnNames = "username")
})
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Column(name = "mfa_secret_ciphertext", length = 256)
    private String mfaSecretCiphertext;

    @Column(name = "mfa_pending_secret_ciphertext", length = 256)
    private String mfaPendingSecretCiphertext;

    @Column(name = "mfa_enrollment_expires_at")
    private Instant mfaEnrollmentExpiresAt;

    @Column(name = "mfa_last_totp_counter", nullable = false)
    private long mfaLastTotpCounter = -1;

    @Column(name = "mfa_auth_version", nullable = false)
    private int mfaAuthVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAccount() {
    }

    public UserAccount(String username, String passwordHash, String fullName, UserRole role, boolean enabled) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.enabled = enabled;
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

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    public String getMfaSecretCiphertext() {
        return mfaSecretCiphertext;
    }

    public String getMfaPendingSecretCiphertext() {
        return mfaPendingSecretCiphertext;
    }

    public Instant getMfaEnrollmentExpiresAt() {
        return mfaEnrollmentExpiresAt;
    }

    public long getMfaLastTotpCounter() {
        return mfaLastTotpCounter;
    }

    public int getMfaAuthVersion() {
        return mfaAuthVersion;
    }

    public void beginMfaEnrollment(String encryptedSecret, Instant expiresAt) {
        if (mfaEnabled) {
            throw new IllegalStateException("MFA is already enabled");
        }
        mfaPendingSecretCiphertext = encryptedSecret;
        mfaEnrollmentExpiresAt = expiresAt;
    }

    public void confirmMfaEnrollment(long acceptedTotpCounter) {
        if (mfaEnabled || mfaPendingSecretCiphertext == null || mfaEnrollmentExpiresAt == null) {
            throw new IllegalStateException("No pending MFA enrollment exists");
        }
        mfaSecretCiphertext = mfaPendingSecretCiphertext;
        mfaPendingSecretCiphertext = null;
        mfaEnrollmentExpiresAt = null;
        mfaEnabled = true;
        mfaLastTotpCounter = acceptedTotpCounter;
        mfaAuthVersion++;
    }

    public void acceptMfaTotpCounter(long acceptedTotpCounter) {
        if (acceptedTotpCounter <= mfaLastTotpCounter) {
            throw new IllegalArgumentException("MFA code has already been used");
        }
        mfaLastTotpCounter = acceptedTotpCounter;
    }

    public void disableMfa() {
        mfaEnabled = false;
        mfaSecretCiphertext = null;
        mfaPendingSecretCiphertext = null;
        mfaEnrollmentExpiresAt = null;
        mfaLastTotpCounter = -1;
        mfaAuthVersion++;
    }
}
