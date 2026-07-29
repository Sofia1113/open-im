package com.openim.server.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis 的分布式限流器（固定窗口计数器）
 *
 * 使用 Lua 脚本保证 INCR + EXPIRE 的原子性，避免两条命令之间的竞争窗口。
 * Key 格式：{@code rate:{policyName}:{identifier}:{windowIndex}}
 * 其中 {@code windowIndex = epochSeconds / windowPeriodSeconds}，
 * 确保同一时间窗口内的所有请求都命中同一个 key。
 *
 * <b>降级策略：</b>Redis 不可用时记录警告并放行请求，而不是直接拒绝——
 * 限流服务不应成为业务的单点故障。
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
     * @return 决策结果
     */
    public RateLimiter.Decision tryConsume(RateLimitPolicy policy, String identifier) {
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
                return RateLimiter.Decision.allow();
            }
            long allowed = result.get(0);
            long retryAfterSecs = result.size() > 1 ? result.get(1) : windowSecs;
            return allowed == 1
                    ? RateLimiter.Decision.allow()
                    : RateLimiter.Decision.reject(Duration.ofSeconds(Math.max(1, retryAfterSecs)));
        } catch (RuntimeException exception) {
            // Redis 不可用时放行，不让限流服务成为单点故障
            log.warn("Redis rate limiter unavailable for key={}, allowing request", key);
            return RateLimiter.Decision.allow();
        }
    }
}
