package com.openim.server.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {
    private static final RateLimitPolicy THREE_PER_MINUTE =
            new RateLimitPolicy(3, 3, Duration.ofMinutes(1));

    @Test
    void allowsRequestsUpToCapacityThenRejects() {
        RateLimiter limiter = new RateLimiter(true, 1_000, 30);

        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isTrue();
        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isTrue();
        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isTrue();
        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isFalse();
    }

    @Test
    void countsEachIdentifierSeparately() {
        RateLimiter limiter = new RateLimiter(true, 1_000, 30);
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(THREE_PER_MINUTE, "user:a");
        }

        // 一个用户耗尽配额不应影响其他用户
        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:b").allowed()).isTrue();
    }

    @Test
    void countsEachPolicySeparately() {
        RateLimiter limiter = new RateLimiter(true, 1_000, 30);
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(THREE_PER_MINUTE, "user:a");
        }

        // 发消息耗尽配额不应连带禁止该用户领红包
        assertThat(limiter.tryConsume(RateLimitPolicy.CLAIM_RED_PACKET, "user:a").allowed())
                .isTrue();
    }

    @Test
    void reportsARetryHintWhenRejected() {
        RateLimiter limiter = new RateLimiter(true, 1_000, 30);
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(THREE_PER_MINUTE, "user:a");
        }

        RateLimiter.Decision decision = limiter.tryConsume(THREE_PER_MINUTE, "user:a");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void canBeDisabledForEnvironmentsThatDoNotWantThrottling() {
        RateLimiter limiter = new RateLimiter(false, 1_000, 30);

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isTrue();
        }
    }
}
