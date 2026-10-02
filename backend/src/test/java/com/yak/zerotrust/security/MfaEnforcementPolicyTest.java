package com.yak.zerotrust.security;

import com.yak.zerotrust.entity.UserAccount;
import com.yak.zerotrust.entity.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MfaEnforcementPolicyTest {

    @Test
    void enforcementRequiresEnrollmentOnlyForPrivilegedAccountsWithoutMfa() {
        MfaEnforcementPolicy policy = new MfaEnforcementPolicy(true);

        assertThat(policy.requiresEnrollment(principal(UserRole.ADMIN, false))).isTrue();
        assertThat(policy.requiresEnrollment(principal(UserRole.SECURITY_ANALYST, false))).isTrue();
        assertThat(policy.requiresEnrollment(principal(UserRole.ADMIN, true))).isFalse();
        assertThat(policy.requiresEnrollment(principal(UserRole.USER, false))).isFalse();
    }

    @Test
    void optionalModePreservesPhase21Behavior() {
        MfaEnforcementPolicy policy = new MfaEnforcementPolicy(false);

        assertThat(policy.requiresEnrollment(principal(UserRole.ADMIN, false))).isFalse();
        assertThat(policy.requiresEnrollment(principal(UserRole.SECURITY_ANALYST, false))).isFalse();
        assertThat(policy.requiresEnrollment(principal(UserRole.ADMIN, true))).isFalse();
    }

    private UserPrincipal principal(UserRole role, boolean mfaEnabled) {
        UserAccount user = new UserAccount("test-user", "hash", "Test User", role, true);
        if (mfaEnabled) {
            user.beginMfaEnrollment("encrypted-seed", java.time.Instant.now().plusSeconds(600));
            user.confirmMfaEnrollment(1);
        }
        return new UserPrincipal(user);
    }
}
