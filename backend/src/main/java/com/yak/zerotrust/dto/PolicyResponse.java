package com.yak.zerotrust.dto;

import com.yak.zerotrust.entity.PolicyAction;
import com.yak.zerotrust.entity.PolicyEffect;

import java.time.Instant;

public record PolicyResponse(
        Long id,
        String name,
        String subject,
        String resource,
        PolicyAction action,
        PolicyEffect effect,
        boolean enabled,
        String description,
        Instant createdAt,
        Instant updatedAt
) {
}
