package com.yak.zerotrust.entity;

public record PolicySnapshot(
        String name,
        String subject,
        String resource,
        PolicyAction action,
        PolicyEffect effect,
        boolean enabled,
        String description
) {

    public static PolicySnapshot from(Policy policy) {
        return new PolicySnapshot(
                policy.getName(),
                policy.getSubject(),
                policy.getResource(),
                policy.getAction(),
                policy.getEffect(),
                policy.isEnabled(),
                policy.getDescription()
        );
    }
}
