package com.openim.server.ratelimit;

import java.time.Duration;

/**
 * 限流策略
 *
 * 对应 docs/02-features/SECURITY.md §4："对登录、发消息、建群、呼叫、信令、
 * 发/领红包分别限流"。不同动作的滥用成本差异很大，因此不共用同一套配额：
 * 登录被爆破的代价是账号失窃，发消息被刷的代价是存储和推送压力，
 * 信令被刷则直接放大到其他用户的连接上。
 *
 * @param capacity   桶容量，即允许的突发量
 * @param refill     每个周期补充的令牌数
 * @param period     补充周期
 */
public record RateLimitPolicy(long capacity, long refill, Duration period) {

    /** 登录：防撞库。突发量刻意压得很低 */
    public static final RateLimitPolicy LOGIN =
            new RateLimitPolicy(10, 10, Duration.ofMinutes(1));

    /** 令牌刷新：正常客户端每 15 分钟一次，高频只可能是异常或攻击 */
    public static final RateLimitPolicy TOKEN_REFRESH =
            new RateLimitPolicy(20, 20, Duration.ofMinutes(1));

    /** 发消息：允许连续输入的突发，长期速率受限 */
    public static final RateLimitPolicy SEND_MESSAGE =
            new RateLimitPolicy(30, 30, Duration.ofMinutes(1));

    /**
     * WebRTC 信令：建连期间 ICE candidate 会密集到达，容量必须足够大，
     * 否则正常通话会被误伤。
     */
    public static final RateLimitPolicy RTC_SIGNAL =
            new RateLimitPolicy(200, 200, Duration.ofMinutes(1));

    /** 发起通话：正常用户不会高频呼叫 */
    public static final RateLimitPolicy CREATE_CALL =
            new RateLimitPolicy(10, 10, Duration.ofMinutes(1));

    /** 发红包：涉及资金变动，从严 */
    public static final RateLimitPolicy CREATE_RED_PACKET =
            new RateLimitPolicy(10, 10, Duration.ofMinutes(1));

    /** 领红包：群里同时发多个红包时会有并发领取，留出余量 */
    public static final RateLimitPolicy CLAIM_RED_PACKET =
            new RateLimitPolicy(30, 30, Duration.ofMinutes(1));

    /** 其余已认证接口的兜底配额 */
    public static final RateLimitPolicy DEFAULT_API =
            new RateLimitPolicy(300, 300, Duration.ofMinutes(1));
}
