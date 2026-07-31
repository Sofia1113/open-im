package com.openim.server.ratelimit;

import java.time.Duration;

/**
 * 限流策略
 *
 * name 字段用于 Redis key 生成（格式：rate:{name}:{identifier}:{windowIndex}）。
 * 本地令牌桶降级时 name 也用于区分不同策略，避免跨动作共享配额。
 *
 * <b>failureMode 的含义</b>：当分布式限流不可用（Redis 故障）且本地降级也
 * 无法提供有意义的保护时，这个请求应该放行还是拒绝。
 *
 * @param name        策略名称，用于 Redis key 和指标标签
 * @param capacity    桶容量，即允许的突发量
 * @param refill      每个周期补充的令牌数
 * @param period      补充周期
 * @param failureMode 限流后端故障时的行为
 */
public record RateLimitPolicy(
        String name,
        long capacity,
        long refill,
        Duration period,
        FailureMode failureMode
) {
    /**
     * 限流后端故障时的行为。
     *
     * <p><b>为什么需要区分</b>：统一 fail-open 会让 Redis 抖动成为撞库窗口；
     * 统一 fail-closed 会让 Redis 故障直接导致全站不可用。两者都不可接受，
     * 因此按"这个动作被滥用的后果有多严重"来决定。
     */
    public enum FailureMode {
        /**
         * 后端故障时放行。适用于普通业务动作：限流是防滥用措施，
         * 不应成为业务的单点故障。
         */
        FAIL_OPEN,

        /**
         * 后端故障时拒绝。适用于凭证类动作（登录、注册、改密）：
         * 这些接口在无限流保护下会直接暴露给撞库和账号枚举，
         * 短暂不可用远好过被爆破。
         *
         * 注意这不会造成全站瘫痪——受影响的只是登录等少数端点，
         * 已登录用户的正常使用不受影响。
         */
        FAIL_CLOSED
    }

    /**
     * 按用户名查找他人。
     *
     * <b>这个策略是可见性边界的一部分，不只是防滥用。</b>接口要求提供完整用户名，
     * 因此单次请求泄露不了什么；但不限流的话，攻击者可以用字典跑几十万次请求
     * 来枚举哪些用户名存在——那等于把刚移除的全站用户列表又还回去了。
     * 配额限制了枚举速率，使字典攻击的成本高到不实用。
     *
     * 20 次/分钟：正常用户添加联系人是低频操作，一分钟内查 20 个人已经远超
     * 手动输入的速度。
     *
     * FAIL_OPEN：这不是凭证入口，且它在建立第一次对话的主路径上——
     * Redis 故障时让它不可用会导致新用户无法开始任何对话。
     */
    public static final RateLimitPolicy USER_SEARCH =
            new RateLimitPolicy("USER_SEARCH", 20, 20, Duration.ofMinutes(1),
                    FailureMode.FAIL_OPEN);

    /** 凭证类动作：Redis 故障时拒绝而非放行 */
    public static final RateLimitPolicy LOGIN =
            new RateLimitPolicy("LOGIN", 10, 10, Duration.ofMinutes(1),
                    FailureMode.FAIL_CLOSED);

    /**
     * 注册配额比登录更紧：正常用户一生只注册一次，
     * 同一 IP 每分钟 5 次已经足够覆盖共享出口 NAT 的合理场景。
     */
    public static final RateLimitPolicy REGISTER =
            new RateLimitPolicy("REGISTER", 5, 5, Duration.ofMinutes(1),
                    FailureMode.FAIL_CLOSED);

    /** 改密要验证当前密码，同样是密码尝试入口 */
    public static final RateLimitPolicy CHANGE_PASSWORD =
            new RateLimitPolicy("CHANGE_PASSWORD", 5, 5, Duration.ofMinutes(1),
                    FailureMode.FAIL_CLOSED);

    /**
     * 注销账号。
     *
     * 配额最紧（每分钟 3 次）：它既是密码尝试入口，又是<b>不可逆</b>操作。
     * 单独一个策略而不是复用 CHANGE_PASSWORD——共享配额意味着有人反复尝试
     * 注销就能耗尽正常用户改密的配额。
     */
    public static final RateLimitPolicy DELETE_ACCOUNT =
            new RateLimitPolicy("DELETE_ACCOUNT", 3, 3, Duration.ofMinutes(1),
                    FailureMode.FAIL_CLOSED);

    /**
     * 刷新令牌不是凭证猜测入口（令牌是 256 位随机值，爆破不可行），
     * 但故障时放行会让重放检测失去限流保护，取 fail-closed 更稳妥。
     * 客户端在刷新失败时会引导重新登录，不会静默失效。
     */
    public static final RateLimitPolicy TOKEN_REFRESH =
            new RateLimitPolicy("TOKEN_REFRESH", 20, 20, Duration.ofMinutes(1),
                    FailureMode.FAIL_CLOSED);

    /**
     * 请求密码重置。
     *
     * 配额比登录更紧（每分钟 3 次）：正常用户不会连续申请重置，
     * 而这个接口的每次调用都会向第三方发一封邮件——滥用它既能骚扰
     * 任意邮箱地址，也能耗尽邮件服务的配额。
     *
     * FAIL_CLOSED：Redis 故障期间宁可暂时无法申请重置。这个功能不在关键路径上，
     * 而不受限流保护的邮件发送接口是一个现成的骚扰工具。
     */
    public static final RateLimitPolicy PASSWORD_RESET_REQUEST =
            new RateLimitPolicy("PASSWORD_RESET_REQUEST", 3, 3, Duration.ofMinutes(1),
                    FailureMode.FAIL_CLOSED);

    /**
     * 提交重置令牌。
     *
     * 令牌是 256 位随机值，暴力猜测不可行，因此配额可以宽松一些——
     * 它主要防的是自动化工具的无意义重试，而不是猜测攻击。
     */
    public static final RateLimitPolicy PASSWORD_RESET_CONFIRM =
            new RateLimitPolicy("PASSWORD_RESET_CONFIRM", 10, 10, Duration.ofMinutes(1),
                    FailureMode.FAIL_CLOSED);

    public static final RateLimitPolicy SEND_MESSAGE =
            new RateLimitPolicy("SEND_MESSAGE", 30, 30, Duration.ofMinutes(1),
                    FailureMode.FAIL_OPEN);

    /**
     * 创建会话。
     *
     * 配额比一般写操作紧（每分钟 10 次）：每次创建都会写入会话行、成员行和
     * 单聊唯一键，而且创建出来的会话<b>目前没有删除接口</b>——被刷出来的垃圾会话
     * 会永久留在对端的会话列表里。这既是防滥用，也是防"用建群刷屏骚扰"。
     */
    public static final RateLimitPolicy CREATE_CONVERSATION =
            new RateLimitPolicy("CREATE_CONVERSATION", 10, 10, Duration.ofMinutes(1),
                    FailureMode.FAIL_OPEN);

    /**
     * 会话成员与资料管理。
     *
     * 与创建分开：正常的一次建群后批量加人会连续调用多次这个接口，
     * 共享创建的配额会让"建群 + 加 20 个人"撞上限流。
     */
    public static final RateLimitPolicy CONVERSATION_ADMIN =
            new RateLimitPolicy("CONVERSATION_ADMIN", 30, 30, Duration.ofMinutes(1),
                    FailureMode.FAIL_OPEN);

    /** ICE candidate 在建连期间密集到达，容量过小会误伤正常通话 */
    public static final RateLimitPolicy RTC_SIGNAL =
            new RateLimitPolicy("RTC_SIGNAL", 200, 200, Duration.ofMinutes(1),
                    FailureMode.FAIL_OPEN);

    public static final RateLimitPolicy CREATE_CALL =
            new RateLimitPolicy("CREATE_CALL", 10, 10, Duration.ofMinutes(1),
                    FailureMode.FAIL_OPEN);

    public static final RateLimitPolicy CREATE_RED_PACKET =
            new RateLimitPolicy("CREATE_RED_PACKET", 10, 10, Duration.ofMinutes(1),
                    FailureMode.FAIL_OPEN);

    public static final RateLimitPolicy CLAIM_RED_PACKET =
            new RateLimitPolicy("CLAIM_RED_PACKET", 30, 30, Duration.ofMinutes(1),
                    FailureMode.FAIL_OPEN);

    public static final RateLimitPolicy DEFAULT_API =
            new RateLimitPolicy("DEFAULT_API", 300, 300, Duration.ofMinutes(1),
                    FailureMode.FAIL_OPEN);

    /** 该策略在限流后端故障时是否拒绝请求（fail-closed）。凭证类动作返回 true，降级时按更紧的配额限流 */
    public boolean failClosed() {
        return failureMode == FailureMode.FAIL_CLOSED;
    }
}
