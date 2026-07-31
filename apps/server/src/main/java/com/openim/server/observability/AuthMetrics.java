package com.openim.server.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证指标
 *
 * 与 {@link DomainMetrics} 分开：认证指标是安全监控的输入（撞库检测、
 * 锁定率异常告警），关注它们的是不同的人，告警阈值也与业务指标无关。
 *
 * 失败原因作为标签而非独立指标名：{@code openim_auth_login_failures_total{reason="locked"}}
 * 让"总失败率"和"按原因拆分"都能从同一个时间序列查询得出。
 * reason 取值是固定的小集合，不含用户输入，不会造成标签基数爆炸。
 *
 * <b>为什么要预注册全部 reason</b>：Counter 在第一次 {@code increment()} 之前
 * 不会出现在导出结果里。对失败类指标来说这是个陷阱——服务刚启动、还没有人登录失败时，
 * {@code openim_auth_login_failures_total} 这条序列<b>根本不存在</b>，于是：
 * <ul>
 *   <li>告警表达式 {@code failures / (success + failures)} 因为一侧为空而整体返回空，
 *       告警永远不触发</li>
 *   <li>Grafana 面板一片空白，看起来像监控坏了</li>
 *   <li>"没有失败"和"指标没接上"无法区分</li>
 * </ul>
 * 预注册让这些序列从启动起就存在且值为 0，"零失败"因此成为一个可以被观测到的事实。
 */
@Component
public class AuthMetrics {

    /**
     * 登录失败的全部原因。
     *
     * 新增取值时必须同时加进这里，否则该 reason 的序列在首次发生前不可见。
     * 用常量集合而不是枚举是为了与调用方现有的字符串参数保持一致，
     * 但语义上它就是一个封闭集合。
     */
    private static final List<String> LOGIN_FAILURE_REASONS = List.of(
            "unknown_identifier",
            "bad_password",
            "locked",
            "change_password_bad_current"
    );

    /** 密码重置失败原因，预注册理由同 {@link #LOGIN_FAILURE_REASONS} */
    private static final List<String> RESET_FAILURE_REASONS = List.of(
            "unknown_token",
            "expired",
            "already_used",
            "concurrent_use"
    );

    private final MeterRegistry registry;
    private final Counter loginSuccess;
    private final Counter accountLocked;
    private final Counter registrations;
    private final Counter registrationConflicts;
    private final Counter passwordChanges;

    private final Counter passwordResetRequested;
    private final Counter passwordResetRequestedUnknown;
    private final Counter passwordResetCompleted;
    private final Counter accountDeletions;
    private final Counter accountDeletionFailures;

    /** 按 reason 缓存 Counter，避免每次失败都走一次 registry 查找 */
    private final Map<String, Counter> loginFailures = new ConcurrentHashMap<>();
    private final Map<String, Counter> resetFailures = new ConcurrentHashMap<>();

    public AuthMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.loginSuccess = Counter.builder("openim.auth.login.success")
                .description("成功登录次数")
                .register(registry);
        this.accountLocked = Counter.builder("openim.auth.account_locked")
                .description("因连续失败被锁定的账号次数")
                .register(registry);
        this.registrations = Counter.builder("openim.auth.registrations")
                .description("成功注册的账号数")
                .register(registry);
        this.registrationConflicts = Counter.builder("openim.auth.registration_conflicts")
                .description("因用户名或邮箱已存在而被拒绝的注册数")
                .register(registry);
        this.passwordChanges = Counter.builder("openim.auth.password_changes")
                .description("成功改密次数")
                .register(registry);
        this.passwordResetRequested = Counter.builder("openim.auth.password_reset_requested")
                .description("已注册邮箱的密码重置请求数")
                .register(registry);
        this.passwordResetRequestedUnknown = Counter
                .builder("openim.auth.password_reset_requested_unknown")
                .description("针对未注册邮箱的密码重置请求数。持续偏高是账号枚举探测的信号")
                .register(registry);
        this.passwordResetCompleted = Counter.builder("openim.auth.password_reset_completed")
                .description("成功完成的密码重置数")
                .register(registry);
        this.accountDeletions = Counter.builder("openim.auth.account_deletions")
                .description("成功注销的账号数。不可逆操作，激增值得告警")
                .register(registry);
        this.accountDeletionFailures = Counter.builder("openim.auth.account_deletion_failures")
                .description("注销时密码校验失败次数。持续出现说明有人在尝试销毁他人账号")
                .register(registry);
        // 预注册全部失败原因，使这些序列从启动起就存在（值为 0）
        LOGIN_FAILURE_REASONS.forEach(this::loginFailureCounter);
        RESET_FAILURE_REASONS.forEach(this::resetFailureCounter);
    }

    /** 记录一次成功登录 */
    public void recordLoginSuccess() {
        loginSuccess.increment();
    }

    /**
     * @param reason 取值必须来自 {@link #LOGIN_FAILURE_REASONS}。
     *               传入未预注册的值仍然可用（会即时创建），但那条序列在首次发生前
     *               不可见，告警会漏掉它——新增取值时记得同步那个列表。
     */
    public void recordLoginFailure(String reason) {
        loginFailureCounter(reason).increment();
    }

    /** 按 reason 获取登录失败计数器，未注册过的 reason 首次使用时即时创建并缓存 */
    private Counter loginFailureCounter(String reason) {
        return loginFailures.computeIfAbsent(reason,
                r -> Counter.builder("openim.auth.login.failures")
                        .description("登录失败次数，按原因拆分")
                        .tag("reason", r)
                        .register(registry));
    }

    /** 记录账号因连续登录失败而被锁定的次数 */
    public void recordAccountLocked() {
        accountLocked.increment();
    }

    /** 记录一次成功注册 */
    public void recordRegistration() {
        registrations.increment();
    }

    /** 记录一次因用户名或邮箱已存在而被拒绝的注册 */
    public void recordRegistrationConflict() {
        registrationConflicts.increment();
    }

    /** 记录一次成功改密 */
    public void recordPasswordChange() {
        passwordChanges.increment();
    }

    /**
     * 记录一次密码重置请求。
     *
     * 已注册与未注册分成两个指标而不是一个带标签的指标，因为它们的用途不同：
     * 前者是业务量，后者是安全信号（大量针对未注册邮箱的请求 = 枚举探测）。
     * 分开后告警可以只盯后者，不受正常业务量波动影响。
     */
    public void recordPasswordResetRequested(boolean emailRegistered) {
        if (emailRegistered) {
            passwordResetRequested.increment();
        } else {
            passwordResetRequestedUnknown.increment();
        }
    }

    /** 记录一次成功完成的密码重置 */
    public void recordPasswordResetCompleted() {
        passwordResetCompleted.increment();
    }

    /**
     * 账号注销成功。
     *
     * 这是一个不可逆操作的计数器，值得单独告警——注销数突然激增可能意味着
     * 产品出了严重问题，也可能意味着有人在批量销毁账号。
     */
    public void recordAccountDeletion() {
        accountDeletions.increment();
    }

    /** 注销时密码校验失败。持续出现说明有人在尝试销毁他人账号 */
    public void recordAccountDeletionFailure() {
        accountDeletionFailures.increment();
    }

    /** @param reason 取值应来自 {@link #RESET_FAILURE_REASONS} */
    public void recordPasswordResetFailure(String reason) {
        resetFailureCounter(reason).increment();
    }

    /** 按 reason 获取密码重置失败计数器，未注册过的 reason 首次使用时即时创建并缓存 */
    private Counter resetFailureCounter(String reason) {
        return resetFailures.computeIfAbsent(reason,
                r -> Counter.builder("openim.auth.password_reset_failures")
                        .description("密码重置失败次数，按原因拆分")
                        .tag("reason", r)
                        .register(registry));
    }
}
