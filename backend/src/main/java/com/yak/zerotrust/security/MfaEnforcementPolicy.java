package com.yak.zerotrust.security;

import com.yak.zerotrust.entity.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MfaEnforcementPolicy {

    private final boolean requirePrivilegedMfa;

    public MfaEnforcementPolicy(@Value("${security.mfa.require-privileged:true}") boolean requirePrivilegedMfa) {
        this.requirePrivilegedMfa = requirePrivilegedMfa;
    }

    public boolean requiresEnrollment(UserPrincipal principal) {
        return requirePrivilegedMfa
                && isPrivileged(principal.getRole())
                && !principal.isMfaEnabled();
    }

    private boolean isPrivileged(UserRole role) {
        return role == UserRole.ADMIN || role == UserRole.SECURITY_ANALYST;
    }
}
