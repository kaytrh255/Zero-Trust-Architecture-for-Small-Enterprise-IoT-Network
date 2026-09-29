package com.yak.zerotrust.access;

public enum AccessDecisionReason {
    POLICY_ALLOW,
    EXPLICIT_DENY,
    NO_MATCHING_POLICY,
    DEVICE_NOT_FOUND,
    DEVICE_NOT_ACTIVE,
    REQUESTER_ROLE_NOT_ALLOWED
}
