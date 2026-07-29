package com.openim.server.ratelimit;

import java.time.Duration;

/**
 * 限流策略
 *
 * name 字段用于 Redis key 生成（格式：rate:{name}:{identifier}:{windowIndex}）。
 * 本地 Bucket4j 回退时 name 也用于区分不同策略的令牌桶，避免跨动作共享配额。
 *
 * @param name     策略名称，用于 Redis key 和日志
 * @param capacity 桶容量，即允许的突发量
 * @param refill   每个周期补充的令牌数
 * @param period   补充周期
 */
public record RateLimitPolicy(String name, long capacity, long refill, Duration period) {

    public static final RateLimitPolicy LOGIN =
            new RateLimitPolicy("LOGIN", 10, 10, Duration.ofMinutes(1));

    public static final RateLimitPolicy TOKEN_REFRESH =
            new RateLimitPolicy("TOKEN_REFRESH", 20, 20, Duration.ofMinutes(1));

    public static final RateLimitPolicy SEND_MESSAGE =
            new RateLimitPolicy("SEND_MESSAGE", 30, 30, Duration.ofMinutes(1));

    /** ICE candidate 在建连期间密集到达，容量过小会误伤正常通话 */
    public static final RateLimitPolicy RTC_SIGNAL =
            new RateLimitPolicy("RTC_SIGNAL", 200, 200, Duration.ofMinutes(1));

    public static final RateLimitPolicy CREATE_CALL =
            new RateLimitPolicy("CREATE_CALL", 10, 10, Duration.ofMinutes(1));

    public static final RateLimitPolicy CREATE_RED_PACKET =
            new RateLimitPolicy("CREATE_RED_PACKET", 10, 10, Duration.ofMinutes(1));

    public static final RateLimitPolicy CLAIM_RED_PACKET =
            new RateLimitPolicy("CLAIM_RED_PACKET", 30, 30, Duration.ofMinutes(1));

    public static final RateLimitPolicy DEFAULT_API =
            new RateLimitPolicy("DEFAULT_API", 300, 300, Duration.ofMinutes(1));
}
