package com.yak.zerotrust.entity;

import com.yak.zerotrust.access.AccessChannel;
import com.yak.zerotrust.access.AccessContext;
import com.yak.zerotrust.access.AccessDecision;
import com.yak.zerotrust.access.AccessDecisionOutcome;
import com.yak.zerotrust.access.AccessDecisionReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "access_audits")
public class AccessAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_id")
    private Long requesterId;

    @Column(name = "requester_username", nullable = false, length = 100)
    private String requesterUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "requester_role", nullable = false, length = 30)
    private UserRole requesterRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccessChannel channel;

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", length = 20)
    private DeviceType deviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_status", length = 20)
    private DeviceStatus deviceStatus;

    @Column(nullable = false, length = 100)
    private String resource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PolicyAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccessDecisionOutcome decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AccessDecisionReason reason;

    @Column(name = "matched_policy_id")
    private Long matchedPolicyId;

    @Column(name = "matched_policy_name", length = 100)
    private String matchedPolicyName;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    private Instant evaluatedAt;

    protected AccessAudit() {
    }

    public AccessAudit(AccessContext context, AccessDecision decision) {
        requesterId = context.requesterId();
        requesterUsername = context.requesterUsername();
        requesterRole = context.requesterRole();
        channel = context.channel();
        deviceId = context.deviceId();
        deviceCode = context.deviceCode();
        deviceType = context.deviceType();
        deviceStatus = context.deviceStatus();
        resource = context.resource();
        action = context.action();
        this.decision = decision.decision();
        reason = decision.reason();
        matchedPolicyId = decision.matchedPolicyId();
        matchedPolicyName = decision.matchedPolicyName();
        evaluatedAt = decision.evaluatedAt();
    }

    @PrePersist
    void setEvaluatedAtIfMissing() {
        if (evaluatedAt == null) {
            evaluatedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getRequesterId() {
        return requesterId;
    }

    public String getRequesterUsername() {
        return requesterUsername;
    }

    public UserRole getRequesterRole() {
        return requesterRole;
    }

    public AccessChannel getChannel() {
        return channel;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public DeviceStatus getDeviceStatus() {
        return deviceStatus;
    }

    public String getResource() {
        return resource;
    }

    public PolicyAction getAction() {
        return action;
    }

    public AccessDecisionOutcome getDecision() {
        return decision;
    }

    public AccessDecisionReason getReason() {
        return reason;
    }

    public Long getMatchedPolicyId() {
        return matchedPolicyId;
    }

    public String getMatchedPolicyName() {
        return matchedPolicyName;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }
}
