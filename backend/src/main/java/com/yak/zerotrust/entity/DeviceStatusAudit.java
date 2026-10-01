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
@Table(name = "device_status_audits")
public class DeviceStatusAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 20)
    private DeviceStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private DeviceStatus newStatus;

    @Column(name = "changed_by_user_id", nullable = false)
    private Long changedByUserId;

    @Column(name = "changed_by_username", nullable = false, length = 50)
    private String changedByUsername;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected DeviceStatusAudit() {
    }

    public DeviceStatusAudit(
            Device device,
            DeviceStatus previousStatus,
            DeviceStatus newStatus,
            Long changedByUserId,
            String changedByUsername
    ) {
        deviceId = device.getId();
        deviceCode = device.getDeviceCode();
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
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

    public DeviceStatus getPreviousStatus() {
        return previousStatus;
    }

    public DeviceStatus getNewStatus() {
        return newStatus;
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
