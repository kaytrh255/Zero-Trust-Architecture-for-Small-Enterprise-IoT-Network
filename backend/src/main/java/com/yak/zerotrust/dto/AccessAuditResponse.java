package com.yak.zerotrust.dto;

import com.yak.zerotrust.access.AccessChannel;
import com.yak.zerotrust.access.AccessDecisionOutcome;
import com.yak.zerotrust.access.AccessDecisionReason;
import com.yak.zerotrust.entity.DeviceStatus;
import com.yak.zerotrust.entity.DeviceType;
import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.UserRole;

import java.time.Instant;

public record AccessAuditResponse(
        Long id,
        Long requesterId,
        String requesterUsername,
        UserRole requesterRole,
        AccessChannel channel,
        Long deviceId,
        String deviceCode,
        DeviceType deviceType,
        DeviceStatus deviceStatus,
        String resource,
        PolicyAction action,
        AccessDecisionOutcome decision,
        AccessDecisionReason reason,
        Long messageSequence,
        Long matchedPolicyId,
        String matchedPolicyName,
        Instant evaluatedAt
) {
}
