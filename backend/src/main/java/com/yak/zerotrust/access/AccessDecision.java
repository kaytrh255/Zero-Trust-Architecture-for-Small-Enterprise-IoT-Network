package com.yak.zerotrust.access;

import com.yak.zerotrust.entity.PolicyAction;

import java.time.Instant;

public record AccessDecision(
        Long auditId,
        AccessDecisionOutcome decision,
        AccessDecisionReason reason,
        String deviceCode,
        String resource,
        PolicyAction action,
        Long matchedPolicyId,
        String matchedPolicyName,
        Instant evaluatedAt
) {

    public AccessDecision withAuditId(Long id) {
        return new AccessDecision(
                id,
                decision,
                reason,
                deviceCode,
                resource,
                action,
                matchedPolicyId,
                matchedPolicyName,
                evaluatedAt
        );
    }
}
