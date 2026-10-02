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
@Table(name = "device_credential_audits")
public class DeviceCredentialAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeviceCredentialOperation operation;

    @Column(name = "previous_signing_key_fingerprint", length = 64)
    private String previousSigningKeyFingerprint;

    @Column(name = "new_signing_key_fingerprint", nullable = false, length = 64)
    private String newSigningKeyFingerprint;

    @Column(name = "changed_by_user_id", nullable = false)
    private Long changedByUserId;

    @Column(name = "changed_by_username", nullable = false, length = 50)
    private String changedByUsername;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected DeviceCredentialAudit() {
    }

    public DeviceCredentialAudit(
            Device device,
            DeviceCredentialOperation operation,
            String previousSigningKeyFingerprint,
            String newSigningKeyFingerprint,
            Long changedByUserId,
            String changedByUsername
    ) {
        deviceId = device.getId();
        deviceCode = device.getDeviceCode();
        this.operation = operation;
        this.previousSigningKeyFingerprint = previousSigningKeyFingerprint;
        this.newSigningKeyFingerprint = newSigningKeyFingerprint;
        this.changedByUserId = changedByUserId;
        this.changedByUsername = changedByUsername;
        changedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public DeviceCredentialOperation getOperation() {
        return operation;
    }

    public String getPreviousSigningKeyFingerprint() {
        return previousSigningKeyFingerprint;
    }

    public String getNewSigningKeyFingerprint() {
        return newSigningKeyFingerprint;
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
