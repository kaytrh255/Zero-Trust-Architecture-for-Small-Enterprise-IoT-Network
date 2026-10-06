package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.PolicyEffect;

public record PolicyChangeSnapshotResponse(
        String name,
        String subject,
        String resource,
        PolicyAction action,
        PolicyEffect effect,
        boolean enabled,
        String description
) {
}
