package com.yak.zerotrust.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "device_ownership_audits")
public class DeviceOwnershipAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;

    @Column(name = "previous_owner_id", nullable = false)
    private Long previousOwnerId;

    @Column(name = "previous_owner_username", nullable = false, length = 50)
    private String previousOwnerUsername;

    @Column(name = "new_owner_id", nullable = false)
    private Long newOwnerId;

    @Column(name = "new_owner_username", nullable = false, length = 50)
    private String newOwnerUsername;

    @Column(name = "changed_by_user_id", nullable = false)
    private Long changedByUserId;

    @Column(name = "changed_by_username", nullable = false, length = 50)
    private String changedByUsername;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected DeviceOwnershipAudit() {
    }

    public DeviceOwnershipAudit(
            Device device,
            UserAccount previousOwner,
            UserAccount newOwner,
            Long changedByUserId,
            String changedByUsername
    ) {
        deviceId = device.getId();
        deviceCode = device.getDeviceCode();
        previousOwnerId = previousOwner.getId();
        previousOwnerUsername = previousOwner.getUsername();
        newOwnerId = newOwner.getId();
        newOwnerUsername = newOwner.getUsername();
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

    public Long getPreviousOwnerId() {
        return previousOwnerId;
    }

    public String getPreviousOwnerUsername() {
        return previousOwnerUsername;
    }

    public Long getNewOwnerId() {
        return newOwnerId;
    }

    public String getNewOwnerUsername() {
        return newOwnerUsername;
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
