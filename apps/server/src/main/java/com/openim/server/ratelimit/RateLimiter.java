package com.openim.server.ratelimit;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 限流门面
 *
 * 正常路径用 Redis 分布式计数器，多实例共享配额。Redis 不可用时降级为
 * 进程内令牌桶——每实例独立，因此 N 个实例下总配额变成 N 倍，
 * 但仍然限制了单实例的爆破速率，远好过完全放行。
 *
 * <b>降级时凭证类动作使用更紧的配额</b>：登录/注册/改密的正常配额
 * （每分钟 5–10 次）本就是按"跨实例共享"设定的，降级后直接乘以实例数
 * 会把撞库窗口放大同样的倍数。因此 {@link RateLimitPolicy#failClosed()}
 * 为真的策略在降级期间按 {@link #DEGRADED_CREDENTIAL_DIVISOR} 收紧，
 * 使多实例下的总配额仍接近原设定值。
 *
 * <b>此前的实现有两个缺陷</b>，这里一并修正：
 * <ul>
 *   <li>本地降级不可达：{@code redisRateLimiter} 是 {@code @Component}，永不为 null，
 *       因此 {@code if (redisRateLimiter == null)} 分支后的 Bucket4j 代码从未执行过。
 *       降级路径改由"Redis 判定失败"触发，而不是"Redis 组件不存在"。</li>
 *   <li>一律 fail-open：Redis 故障时所有请求（含登录）无条件放行，
 *       使 Redis 抖动成为撞库窗口。</li>
 * </ul>
 *
 * 刻意<b>不</b>实现"Redis 故障时拒绝一切登录"：那会把一次 Redis 抖动
 * 放大成完整的认证不可用。数据库侧的账号锁定（见
 * {@link com.openim.server.domain.repository.CredentialRepository#recordFailure}）
 * 在降级期间仍然生效，且它不依赖 Redis——针对单个账号的爆破由它兜底，
 * 限流只需要拦住横向扫描。
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /**
     * 降级期间凭证类配额的收紧倍数。
     *
     * 取 4 对应"最多 4 个实例"的部署规模：4 实例 × (10/4) = 10，
     * 与 Redis 正常时的总配额一致。实例数远超 4 时应调整此值，
     * 或者说——更应该修好 Redis。
     */
    private static final long DEGRADED_CREDENTIAL_DIVISOR = 4;

    private final boolean enabled;
    private final LoadingCache<BucketKey, Bucket> localBuckets;

    @Nullable
    private final RedisRateLimiter redisRateLimiter;

    /** 降级次数是运维信号：持续非零说明 Redis 有问题，配额已不再跨实例共享 */
    private final Counter degradedDecisions;
    private final Counter degradedRejections;

    public RateLimiter(
            @Value("${openim.rate-limit.enabled:true}") boolean enabled,
            @Value("${openim.rate-limit.max-tracked-keys:100000}") long maxTrackedKeys,
            @Value("${openim.rate-limit.key-ttl-minutes:30}") long keyTtlMinutes,
            @Autowired(required = false) @Nullable RedisRateLimiter redisRateLimiter,
            MeterRegistry meterRegistry
    ) {
        this.enabled = enabled;
        this.redisRateLimiter = redisRateLimiter;
        this.localBuckets = Caffeine.newBuilder()
                .maximumSize(maxTrackedKeys)
                .expireAfterAccess(Duration.ofMinutes(keyTtlMinutes))
                .build(this::createLocalBucket);
        this.degradedDecisions = Counter.builder("openim.rate_limit.degraded")
                .description("由进程内令牌桶而非 Redis 做出的限流判定次数")
                .register(meterRegistry);
        this.degradedRejections = Counter.builder("openim.rate_limit.degraded_rejections")
                .description("降级期间被进程内令牌桶拒绝的请求数")
                .register(meterRegistry);
    }

    /**
     * 尝试消费一个令牌。
     *
     * @param policy     该动作的配额与故障语义
     * @param identifier 限流主体：已认证请求用 "user:{id}"，未认证用 "ip:{addr}"
     */
    public Decision tryConsume(RateLimitPolicy policy, String identifier) {
        if (!enabled) {
            return Decision.allow();
        }

        if (redisRateLimiter != null) {
            Optional<Decision> distributed = redisRateLimiter.tryConsume(policy, identifier);
            if (distributed.isPresent()) {
                return distributed.get();
            }
            // Redis 无法判定，落到本地桶
            degradedDecisions.increment();
        }

        Decision local = consumeLocal(policy, identifier);
        if (!local.allowed()) {
            degradedRejections.increment();
            if (policy.failClosed()) {
                log.warn("Credential-path request rejected under degraded rate limiting policy={}",
                        policy.name());
            }
        }
        return local;
    }

    /**
     * 从进程内令牌桶消费一个令牌。
     *
     * 这是 Redis 不可用时的降级路径：配额变为每实例独立，N 个实例下总配额是
     * Redis 正常时的 N 倍，但仍能拦住单实例的爆破速率，远好过完全放行。
     * 凭证类策略的容量已在 {@link #createLocalBucket} 中按
     * {@link #DEGRADED_CREDENTIAL_DIVISOR} 收紧。
     */
    private Decision consumeLocal(RateLimitPolicy policy, String identifier) {
        Bucket bucket = localBuckets.get(new BucketKey(policy, identifier));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed()
                ? Decision.allow()
                : Decision.reject(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    /**
     * 创建进程内令牌桶。
     *
     * 凭证类策略的容量按 {@link #DEGRADED_CREDENTIAL_DIVISOR} 收紧，
     * 使多实例降级时的总配额仍接近 Redis 正常时的设定值。
     * 容量下限为 1：再怎么收紧也要允许一次尝试通过，否则等于完全封锁登录。
     */
    private Bucket createLocalBucket(BucketKey key) {
        RateLimitPolicy policy = key.policy();
        long capacity = policy.capacity();
        long refill = policy.refill();
        if (policy.failClosed()) {
            capacity = Math.max(1, capacity / DEGRADED_CREDENTIAL_DIVISOR);
            refill = Math.max(1, refill / DEGRADED_CREDENTIAL_DIVISOR);
        }
        Bandwidth bandwidth = Bandwidth.classic(
                capacity,
                Refill.greedy(refill, policy.period())
        );
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private record BucketKey(RateLimitPolicy policy, String identifier) {}

    public record Decision(boolean allowed, Duration retryAfter) {
        private static final Decision ALLOWED = new Decision(true, Duration.ZERO);

        /** 放行判定，返回不可变单例避免每次分配新对象 */
        static Decision allow() { return ALLOWED; }

        /** 拒绝判定，携带建议客户端重试的等待时长 */
        static Decision reject(Duration retryAfter) {
            return new Decision(false, retryAfter);
        }

        /** 重试等待秒数。下限 1 秒：0 秒的 Retry-After 会让客户端立即重试，失去节流意义 */
        public long retryAfterSeconds() {
            return Math.max(1, retryAfter.toSeconds());
        }
    }
}
