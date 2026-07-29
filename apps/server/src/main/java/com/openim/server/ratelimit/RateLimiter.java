package com.openim.server.ratelimit;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 限流门面
 *
 * 优先使用 Redis 分布式限流（{@link RedisRateLimiter}），多实例下配额共享。
 * Redis 不可用时自动降级为进程内令牌桶（Bucket4j + Caffeine），保证业务不中断。
 *
 * <pre>
 * Redis 可用时：rate:{policy}:{identifier}:{windowIndex}  ← 跨实例共享
 * Redis 不可用：进程内 Caffeine 缓存的 Bucket4j 令牌桶   ← 每实例独立
 * </pre>
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final boolean enabled;
    private final LoadingCache<BucketKey, Bucket> localBuckets;

    @Nullable
    private final RedisRateLimiter redisRateLimiter;

    public RateLimiter(
            @Value("${openim.rate-limit.enabled:true}") boolean enabled,
            @Value("${openim.rate-limit.max-tracked-keys:100000}") long maxTrackedKeys,
            @Value("${openim.rate-limit.key-ttl-minutes:30}") long keyTtlMinutes,
            @Autowired(required = false) @Nullable RedisRateLimiter redisRateLimiter
    ) {
        this.enabled = enabled;
        this.redisRateLimiter = redisRateLimiter;
        this.localBuckets = Caffeine.newBuilder()
                .maximumSize(maxTrackedKeys)
                .expireAfterAccess(Duration.ofMinutes(keyTtlMinutes))
                .build(this::createLocalBucket);
    }

    /**
     * 尝试消费一个令牌。
     *
     * @param policy     该动作的配额
     * @param identifier 限流主体：已认证请求用 "user:{id}"，未认证用 "ip:{addr}"
     */
    public Decision tryConsume(RateLimitPolicy policy, String identifier) {
        if (!enabled) {
            return Decision.allow();
        }
        // Redis 优先：多实例下共享配额，防止单用户通过多实例绕过限制
        if (redisRateLimiter != null) {
            Decision decision = redisRateLimiter.tryConsume(policy, identifier);
            // RedisRateLimiter 内部已处理 Redis 故障并降级放行；
            // 若它明确拒绝则直接返回，无需再查本地桶
            if (!decision.allowed()) {
                return decision;
            }
            return decision;
        }
        // 降级：进程内令牌桶
        Bucket bucket = localBuckets.get(new BucketKey(policy, identifier));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed()
                ? Decision.allow()
                : Decision.reject(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private Bucket createLocalBucket(BucketKey key) {
        RateLimitPolicy policy = key.policy();
        Bandwidth bandwidth = Bandwidth.classic(
                policy.capacity(),
                Refill.greedy(policy.refill(), policy.period())
        );
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private record BucketKey(RateLimitPolicy policy, String identifier) {}

    public record Decision(boolean allowed, Duration retryAfter) {
        private static final Decision ALLOWED = new Decision(true, Duration.ZERO);

        static Decision allow() { return ALLOWED; }

        static Decision reject(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }

        public long retryAfterSeconds() {
            return Math.max(1, retryAfter.toSeconds());
        }
    }
}
