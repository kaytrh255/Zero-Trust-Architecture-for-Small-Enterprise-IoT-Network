package com.yak.zerotrust.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "device_telemetry")
public class DeviceTelemetry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;

    @Column(name = "device_sequence", nullable = false)
    private long deviceSequence;

    @Column(nullable = false, length = 64)
    private String metric;

    @Column(name = "metric_value", nullable = false, precision = 18, scale = 6)
    private BigDecimal value;

    @Column(nullable = false, length = 16)
    private String unit;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(nullable = false, length = 128)
    private String topic;

    protected DeviceTelemetry() {
    }

    public DeviceTelemetry(
            Device device,
            String deviceCode,
            long deviceSequence,
            String metric,
            BigDecimal value,
            String unit,
            Instant measuredAt,
            Instant receivedAt,
            String topic
    ) {
        this.device = device;
        this.deviceCode = deviceCode;
        this.deviceSequence = deviceSequence;
        this.metric = metric;
        this.value = value;
        this.unit = unit;
        this.measuredAt = measuredAt;
        this.receivedAt = receivedAt;
        this.topic = topic;
    }

    public Long getId() {
        return id;
    }

    public Device getDevice() {
        return device;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public long getDeviceSequence() {
        return deviceSequence;
    }

    public String getMetric() {
        return metric;
    }

    public BigDecimal getValue() {
        return value;
    }

    public String getUnit() {
        return unit;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getTopic() {
        return topic;
    }
}
