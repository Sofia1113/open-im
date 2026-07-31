package com.openim.server.auth;

import com.openim.server.config.ProductionProfile;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产环境密码重置闸门
 *
 * 锁定一条安全约束：<b>prod profile 下不允许用"把令牌写进日志"的兜底实现</b>。
 *
 * 为什么值得专门测试：这个闸门的价值在于它<i>阻止启动</i>，而阻止启动是一种
 * 很容易被"顺手删掉"的行为——某次部署失败，有人发现是这个异常导致的，
 * 于是把它注释掉让服务先跑起来。那一刻，一次性重置令牌就开始流进日志系统了，
 * 而且没有任何迹象表明发生了什么。
 *
 * 这个测试让删除它的代价是一次明确的测试失败，而不是一个静默的安全降级。
 */
class ProductionPasswordResetGuardTest {

    /** prod + 启用重置 + 只有日志兜底 = 拒绝启动 */
    @Test
    void productionRefusesToStartWithTheLoggingFallbackWhenResetIsEnabled() {
        LoggingPasswordResetNotifier notifier = notifierFor(true, true);

        assertThatThrownBy(notifier::refuseToRunInProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有配置任何真实的送达渠道")
                .hasMessageContaining("password-reset-enabled=false");
    }

    /** prod + 显式关闭重置 = 允许启动。这是两个合法选项之一 */
    @Test
    void productionStartsWhenResetIsExplicitlyDisabled() {
        LoggingPasswordResetNotifier notifier = notifierFor(true, false);

        assertThatCode(notifier::refuseToRunInProduction).doesNotThrowAnyException();
    }

    /** 非 prod 下兜底实现可用——本地开发从日志里取令牌是它存在的理由 */
    @Test
    void nonProductionAllowsTheLoggingFallback() {
        LoggingPasswordResetNotifier notifier = notifierFor(false, true);

        assertThatCode(notifier::refuseToRunInProduction).doesNotThrowAnyException();
    }

    /**
     * 兜底实现必须自报"不可用于生产"。
     *
     * {@link PasswordResetNotifier#productionReady()} 默认返回 false，
     * 使"忘记覆盖它"成为安全的方向——一个真实实现如果忘了返回 true，
     * 最坏结果是被当作不可用于生产；反过来默认 true 的话，
     * 一个半成品实现会被当作生产就绪。
     */
    @Test
    void loggingFallbackDeclaresItselfNotProductionReady() {
        assertThat(notifierFor(false, true).productionReady()).isFalse();
    }

    private static LoggingPasswordResetNotifier notifierFor(
            boolean production, boolean resetEnabled) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("openim.auth.password-reset-enabled",
                String.valueOf(resetEnabled));
        if (production) {
            environment.setActiveProfiles("prod");
        }
        return new LoggingPasswordResetNotifier(
                new ProductionProfile(environment), environment);
    }

    /** 让编译器确认 Environment 是构造参数类型，避免签名变更后测试静默失配 */
    @SuppressWarnings("unused")
    private static Environment typeWitness(MockEnvironment environment) {
        return environment;
    }
}
