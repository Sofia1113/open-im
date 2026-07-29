package com.openim.server.ratelimit;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 令牌桶限流器
 *
 * 当前实现是进程内的：配额按实例独立计算，N 个实例的实际总配额是 N 倍。
 * 多实例部署时应换成 bucket4j 的 Redis 后端，届时只需替换 {@link #bucketFor} 的实现，
 * 调用方无需改动。
 *
 * 桶按 key 缓存并在空闲后淘汰，避免为每个出现过的 IP 或用户永久保留一个桶。
 */
@Component
public class RateLimiter {
    private final LoadingCache<BucketKey, Bucket> buckets;
    private final boolean enabled;

    public RateLimiter(
            @Value("${openim.rate-limit.enabled:true}") boolean enabled,
            @Value("${openim.rate-limit.max-tracked-keys:100000}") long maxTrackedKeys,
            @Value("${openim.rate-limit.key-ttl-minutes:30}") long keyTtlMinutes
    ) {
        this.enabled = enabled;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(maxTrackedKeys)
                .expireAfterAccess(Duration.ofMinutes(keyTtlMinutes))
                .build(this::createBucket);
    }

    /**
     * 尝试消费一个令牌。
     *
     * @param policy     该动作的配额
     * @param identifier 限流主体：已认证请求用 userId，未认证请求用客户端 IP
     * @return 是否放行，以及被拒绝时建议的重试等待时长
     */
    public Decision tryConsume(RateLimitPolicy policy, String identifier) {
        if (!enabled) {
            return Decision.allow();
        }
        Bucket bucket = buckets.get(new BucketKey(policy, identifier));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Decision.allow();
        }
        return Decision.reject(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private Bucket createBucket(BucketKey key) {
        RateLimitPolicy policy = key.policy();
        Bandwidth bandwidth = Bandwidth.classic(
                policy.capacity(),
                Refill.greedy(policy.refill(), policy.period())
        );
        return Bucket.builder().addLimit(bandwidth).build();
    }

    /** 限流键：策略与主体的组合，使同一用户在不同动作上互不影响 */
    private record BucketKey(RateLimitPolicy policy, String identifier) {
    }

    /**
     * 限流决策。
     *
     * @param allowed      是否放行
     * @param retryAfter   建议重试等待时长，放行时为零
     */
    public record Decision(boolean allowed, Duration retryAfter) {
        private static final Decision ALLOWED = new Decision(true, Duration.ZERO);

        static Decision allow() {
            return ALLOWED;
        }

        static Decision reject(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }

        /** 向客户端返回的 Retry-After 秒数，至少为 1 */
        public long retryAfterSeconds() {
            return Math.max(1, retryAfter.toSeconds());
        }
    }
}
