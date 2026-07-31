package com.openim.server.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 基于 Redis 的分布式限流器（固定窗口计数器）
 *
 * 使用 Lua 脚本保证 INCR + EXPIRE 的原子性，避免两条命令之间的竞争窗口。
 * Key 格式：{@code rate:{policyName}:{identifier}:{windowIndex}}
 * 其中 {@code windowIndex = epochSeconds / windowPeriodSeconds}，
 * 确保同一时间窗口内的所有请求都命中同一个 key。
 *
 * <b>故障语义</b>：本类不自行决定 Redis 故障时放行还是拒绝——它返回
 * {@link Optional#empty()} 表示"无法给出判定"，由 {@link RateLimiter}
 * 按策略的 {@link RateLimitPolicy.FailureMode} 决定。
 *
 * 这个分工是必要的：此前本类在异常时直接返回"放行"，导致登录接口在 Redis
 * 抖动期间完全失去限流保护，而调用方无从得知判定是真实的还是降级的。
 */
@Component
public class RedisRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /**
     * Lua 原子计数脚本。
     *
     * 第一个 INCR 之后立即设置 EXPIRE，保证窗口到期后自动清理。
     * 如果在超出限额后仍然有请求到来，EXPIRE 不会重置，TTL 继续倒计时直到窗口结束。
     *
     * 返回：{allowed (1/0), retry_after_seconds}
     */
    private static final String LUA_SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if count > tonumber(ARGV[1]) then
                return {0, redis.call('TTL', KEYS[1])}
            end
            return {1, 0}
            """;

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> script;

    @SuppressWarnings("unchecked")
    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        DefaultRedisScript<List<Long>> s = new DefaultRedisScript<>();
        s.setScriptText(LUA_SCRIPT);
        s.setResultType((Class<List<Long>>) (Class<?>) List.class);
        this.script = s;
    }

    /**
     * 尝试消费一个令牌。
     *
     * @param policy     限流策略（容量和时间窗口）
     * @param identifier 限流主体（如 "user:alice"、"ip:1.2.3.4"）
     * @return 判定结果；{@link Optional#empty()} 表示 Redis 不可用，无法判定
     */
    public Optional<RateLimiter.Decision> tryConsume(RateLimitPolicy policy, String identifier) {
        long windowSecs = policy.period().toSeconds();
        // windowIndex 使同一时间窗口内的所有请求落到同一个 key
        long windowIndex = System.currentTimeMillis() / 1000 / windowSecs;
        String key = "rate:" + policy.name() + ":" + identifier + ":" + windowIndex;

        try {
            List<Long> result = redisTemplate.execute(
                    script,
                    List.of(key),
                    String.valueOf(policy.capacity()),
                    String.valueOf(windowSecs)
            );
            if (result == null || result.isEmpty()) {
                // 脚本返回了意料之外的结果：与连接失败同等对待，交由调用方按策略决定
                log.warn("Redis rate limit script returned no result policy={}", policy.name());
                return Optional.empty();
            }
            long allowed = result.get(0);
            long retryAfterSecs = result.size() > 1 ? result.get(1) : windowSecs;
            return Optional.of(allowed == 1
                    ? RateLimiter.Decision.allow()
                    : RateLimiter.Decision.reject(Duration.ofSeconds(Math.max(1, retryAfterSecs))));
        } catch (RuntimeException exception) {
            // 不记录 identifier：它含用户 ID 或 IP（见 SECURITY.md §9 对日志的要求）
            log.warn("Redis rate limiter unavailable policy={} reason={}",
                    policy.name(), exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
