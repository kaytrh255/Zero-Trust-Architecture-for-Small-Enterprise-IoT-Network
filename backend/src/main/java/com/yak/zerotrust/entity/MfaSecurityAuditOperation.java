package com.yak.zerotrust.entity;

public enum MfaSecurityAuditOperation {
    ENROLLMENT_STARTED,
    ENABLED,
    DISABLED,
    RECOVERY_CODE_USED,
    RECOVERY_CODES_ROTATED,
    ADMIN_MFA_RECOVERY
}
