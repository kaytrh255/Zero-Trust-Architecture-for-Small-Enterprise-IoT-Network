package com.yak.zerotrust.security;

import com.yak.zerotrust.exception.LoginRateLimitExceededException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    @Test
    void enforcesFixedWindowLimitAndReturnsRetryAfter() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 60, 10);
        limiter.checkAndRecord("192.0.2.10");
        limiter.checkAndRecord("192.0.2.10");

        assertThatThrownBy(() -> limiter.checkAndRecord("192.0.2.10"))
                .isInstanceOf(LoginRateLimitExceededException.class)
                .satisfies(exception -> assertThat(
                        ((LoginRateLimitExceededException) exception).getRetryAfterSeconds()
                ).isBetween(1L, 60L));
    }

    @Test
    void keepsBudgetsIndependentBetweenPeerAddresses() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 60, 10);
        limiter.checkAndRecord("192.0.2.10");
        limiter.checkAndRecord("192.0.2.11");

        assertThatThrownBy(() -> limiter.checkAndRecord("192.0.2.10"))
                .isInstanceOf(LoginRateLimitExceededException.class);
    }

}
