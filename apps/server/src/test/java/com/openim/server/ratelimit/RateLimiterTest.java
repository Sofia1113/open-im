package com.openim.server.ratelimit;

import com.openim.server.ratelimit.RateLimitPolicy.FailureMode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {
    private static final RateLimitPolicy THREE_PER_MINUTE =
            new RateLimitPolicy("TEST", 3, 3, Duration.ofMinutes(1), FailureMode.FAIL_OPEN);

    /** 凭证类策略：降级时容量按 DEGRADED_CREDENTIAL_DIVISOR(4) 收紧 */
    private static final RateLimitPolicy CREDENTIAL_EIGHT_PER_MINUTE =
            new RateLimitPolicy("TEST_CRED", 8, 8, Duration.ofMinutes(1), FailureMode.FAIL_CLOSED);

    private static RateLimiter localOnlyLimiter(boolean enabled) {
        return new RateLimiter(enabled, 1_000, 30, null, new SimpleMeterRegistry());
    }

    @Test
    void allowsRequestsUpToCapacityThenRejects() {
        RateLimiter limiter = localOnlyLimiter(true);

        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isTrue();
        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isTrue();
        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isTrue();
        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isFalse();
    }

    @Test
    void countsEachIdentifierSeparately() {
        RateLimiter limiter = localOnlyLimiter(true);
        for (int i = 0; i < 3; i++) limiter.tryConsume(THREE_PER_MINUTE, "user:a");

        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:b").allowed()).isTrue();
    }

    @Test
    void countsEachPolicySeparately() {
        RateLimiter limiter = localOnlyLimiter(true);
        for (int i = 0; i < 3; i++) limiter.tryConsume(THREE_PER_MINUTE, "user:a");

        // 发消息耗尽配额不应连带禁止该用户领红包
        assertThat(limiter.tryConsume(RateLimitPolicy.CLAIM_RED_PACKET, "user:a").allowed()).isTrue();
    }

    @Test
    void reportsARetryHintWhenRejected() {
        RateLimiter limiter = localOnlyLimiter(true);
        for (int i = 0; i < 3; i++) limiter.tryConsume(THREE_PER_MINUTE, "user:a");

        RateLimiter.Decision decision = limiter.tryConsume(THREE_PER_MINUTE, "user:a");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void canBeDisabledForEnvironmentsThatDoNotWantThrottling() {
        RateLimiter limiter = localOnlyLimiter(false);

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isTrue();
        }
    }

    // ── 降级路径 ─────────────────────────────────────────────
    //
    // 此前这条路径是不可达代码：RateLimiter 只在 redisRateLimiter == null 时
    // 使用本地桶，而它是 @Component 永不为 null。改为由"Redis 返回空判定"触发后，
    // 以下测试才真正覆盖到降级行为。

    @Test
    void fallsBackToLocalBucketWhenRedisCannotDecide() {
        RedisRateLimiter failing = Mockito.mock(RedisRateLimiter.class);
        Mockito.when(failing.tryConsume(Mockito.any(), Mockito.anyString()))
                .thenReturn(Optional.empty());
        RateLimiter limiter = new RateLimiter(
                true, 1_000, 30, failing, new SimpleMeterRegistry());

        // 本地桶接管：前 3 次放行，第 4 次拒绝
        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isTrue();
        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isTrue();
        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isTrue();
        assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isFalse();
    }

    @Test
    void redisVerdictTakesPrecedenceOverLocalBucket() {
        RedisRateLimiter rejecting = Mockito.mock(RedisRateLimiter.class);
        Mockito.when(rejecting.tryConsume(Mockito.any(), Mockito.anyString()))
                .thenReturn(Optional.of(RateLimiter.Decision.reject(Duration.ofSeconds(30))));
        RateLimiter limiter = new RateLimiter(
                true, 1_000, 30, rejecting, new SimpleMeterRegistry());

        // Redis 明确拒绝时不应再查本地桶（本地桶此刻是满的，会放行）
        RateLimiter.Decision decision = limiter.tryConsume(THREE_PER_MINUTE, "user:a");
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void degradedCredentialPolicyUsesTighterQuotaThanConfigured() {
        RedisRateLimiter failing = Mockito.mock(RedisRateLimiter.class);
        Mockito.when(failing.tryConsume(Mockito.any(), Mockito.anyString()))
                .thenReturn(Optional.empty());
        RateLimiter limiter = new RateLimiter(
                true, 1_000, 30, failing, new SimpleMeterRegistry());

        // 配额 8，凭证类策略降级后收紧为 8/4 = 2：
        // 多实例下每实例 2 次使总量仍接近原设定，而不是 实例数 × 8
        assertThat(limiter.tryConsume(CREDENTIAL_EIGHT_PER_MINUTE, "ip:1.2.3.4").allowed()).isTrue();
        assertThat(limiter.tryConsume(CREDENTIAL_EIGHT_PER_MINUTE, "ip:1.2.3.4").allowed()).isTrue();
        assertThat(limiter.tryConsume(CREDENTIAL_EIGHT_PER_MINUTE, "ip:1.2.3.4").allowed()).isFalse();
    }

    @Test
    void degradedNonCredentialPolicyKeepsFullQuota() {
        RedisRateLimiter failing = Mockito.mock(RedisRateLimiter.class);
        Mockito.when(failing.tryConsume(Mockito.any(), Mockito.anyString()))
                .thenReturn(Optional.empty());
        RateLimiter limiter = new RateLimiter(
                true, 1_000, 30, failing, new SimpleMeterRegistry());

        // 普通业务动作不收紧：限流不应成为业务的单点故障
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume(THREE_PER_MINUTE, "user:a").allowed()).isTrue();
        }
    }

    @Test
    void recordsDegradedDecisionsAsAMetric() {
        RedisRateLimiter failing = Mockito.mock(RedisRateLimiter.class);
        Mockito.when(failing.tryConsume(Mockito.any(), Mockito.anyString()))
                .thenReturn(Optional.empty());
        MeterRegistry registry = new SimpleMeterRegistry();
        RateLimiter limiter = new RateLimiter(true, 1_000, 30, failing, registry);

        limiter.tryConsume(THREE_PER_MINUTE, "user:a");

        // 降级计数持续非零是"Redis 有问题，配额已不再跨实例共享"的告警信号
        assertThat(registry.counter("openim.rate_limit.degraded").count()).isEqualTo(1);
    }
}
