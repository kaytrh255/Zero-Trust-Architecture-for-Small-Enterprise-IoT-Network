package com.yak.zerotrust.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "devices", uniqueConstraints = {
        @UniqueConstraint(name = "uk_devices_device_code", columnNames = "device_code"),
        @UniqueConstraint(name = "uk_devices_mqtt_client_id", columnNames = "mqtt_client_id")
})
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;

    @Column(name = "device_name", nullable = false, length = 100)
    private String deviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", nullable = false, length = 20)
    private DeviceType deviceType;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "mqtt_client_id", nullable = false, length = 100)
    private String mqttClientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeviceStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserAccount owner;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected Device() {
    }

    public Device(
            String deviceCode,
            String deviceName,
            DeviceType deviceType,
            String ipAddress,
            String mqttClientId,
            UserAccount owner
    ) {
        this.deviceCode = deviceCode;
        this.deviceName = deviceName;
        this.deviceType = deviceType;
        this.ipAddress = ipAddress;
        this.mqttClientId = mqttClientId;
        this.status = DeviceStatus.ACTIVE;
        this.owner = owner;
    }

    @PrePersist
    void setCreatedAt() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void updateDetails(
            String deviceCode,
            String deviceName,
            DeviceType deviceType,
            String ipAddress,
            String mqttClientId
    ) {
        this.deviceCode = deviceCode;
        this.deviceName = deviceName;
        this.deviceType = deviceType;
        this.ipAddress = ipAddress;
        this.mqttClientId = mqttClientId;
    }

    public void changeStatus(DeviceStatus status) {
        this.status = status;
    }

    public void recordTelemetryReceived(Instant receivedAt) {
        this.lastSeenAt = receivedAt;
    }

    public Long getId() {
        return id;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getMqttClientId() {
        return mqttClientId;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public UserAccount getOwner() {
        return owner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
