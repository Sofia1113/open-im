package com.openim.server.auth;

import com.openim.server.config.ProductionProfile;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 把重置令牌写进日志的兜底实现
 *
 * <b>仅用于本地开发与测试。</b>没有邮件服务时，开发者从日志里取出链接完成流程。
 *
 * <b>为什么在 prod 下拒绝启动</b>：把一次性密码打进日志是真实的安全问题——
 * 日志会被聚合、索引、按不同权限分发，能看日志的人就能重置任何人的密码。
 * 但比这更糟的选项是"静默降级"：如果 prod 下它只是不打印令牌然后返回成功，
 * 密码重置接口会变成一个永远返回 200 却什么都不做的接口，
 * 用户永远收不到邮件，而监控上一切正常。
 *
 * 因此这里的选择是让它成为一个<b>启动期错误</b>，强迫部署者做一个明确的决定：
 * <ul>
 *   <li>接入真实渠道 —— 定义一个 {@link PasswordResetNotifier} Bean，
 *       本兜底实现会因 {@code @ConditionalOnMissingBean} 自动退出
 *       （装配见 {@link PasswordResetConfig}）</li>
 *   <li>暂不提供密码重置 —— 显式设置
 *       {@code openim.auth.password-reset-enabled=false}</li>
 * </ul>
 * 这与 {@code TOKEN_SECRET} 在 prod 下拒绝弱密钥启动是同一种思路：
 * 危险的默认值不应该能被"忘记配置"这件事激活。
 *
 * <b>不加 {@code @Component}</b>：{@code @ConditionalOnMissingBean} 只在
 * 自动配置类里有确定的行为，放在被组件扫描的类上时，判定结果取决于扫描顺序——
 * 真实实现先注册就生效，后注册就被忽略，而顺序不由我们控制。
 * 因此改由 {@link PasswordResetConfig} 用 {@code @Bean} 方法装配。
 */
public class LoggingPasswordResetNotifier implements PasswordResetNotifier {
    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetNotifier.class);

    private final ProductionProfile productionProfile;
    private final boolean passwordResetEnabled;

    public LoggingPasswordResetNotifier(
            ProductionProfile productionProfile,
            org.springframework.core.env.Environment environment
    ) {
        this.productionProfile = productionProfile;
        this.passwordResetEnabled = environment.getProperty(
                "openim.auth.password-reset-enabled", Boolean.class, true);
    }

    /** 生产环境自检：启用了重置却无真实送达渠道时拒绝启动，防止接口静默失效 */
    @PostConstruct
    void refuseToRunInProduction() {
        if (productionProfile.active() && passwordResetEnabled) {
            throw new IllegalStateException("""
                    生产环境启用了密码重置，但没有配置任何真实的送达渠道。
                    当前只有 LoggingPasswordResetNotifier，它会把一次性重置令牌写入日志——
                    能读日志的人就能重置任意账号的密码。

                    二选一：
                      1. 提供一个 PasswordResetNotifier 实现（SMTP / SES / SendGrid 等），
                         并让 productionReady() 返回 true
                      2. 显式关闭该功能：openim.auth.password-reset-enabled=false
                    """);
        }
        if (passwordResetEnabled) {
            log.warn("密码重置令牌将写入日志（仅适用于本地开发）。"
                    + "生产部署必须提供真实的 PasswordResetNotifier 实现。");
        }
    }

    /** 把明文令牌写入日志（仅本地开发）。prod 走不到这里——启动期即被拒绝 */
    @Override
    public void sendResetToken(String email, String displayName, String token, Duration validFor) {
        // 明文令牌只在本地开发下出现在日志里。prod 走不到这里——启动就失败了。
        log.info("""

                ┌─ 密码重置（开发模式）───────────────────────────────
                │ 收件人：{} <{}>
                │ 令牌  ：{}
                │ 有效期：{} 分钟
                │ 用法  ：POST /api/v1/auth/password/reset
                │         {{"token":"<上面的令牌>","newPassword":"<新密码>"}}
                └────────────────────────────────────────────────────
                """, displayName, email, token, validFor.toMinutes());
    }
}
