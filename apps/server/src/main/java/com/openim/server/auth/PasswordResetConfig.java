package com.openim.server.auth;

import com.openim.server.config.ProductionProfile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 密码重置的送达渠道装配
 *
 * 只在没有其他 {@link PasswordResetNotifier} Bean 时提供
 * {@link LoggingPasswordResetNotifier} 作为兜底。
 *
 * <b>为什么用 {@code @Bean} 而不是把条件注解放在实现类上</b>：
 * {@code @ConditionalOnMissingBean} 的判定依赖 Bean 定义的注册顺序，
 * 而组件扫描的顺序不由我们控制。放在被扫描的 {@code @Component} 上时，
 * "真实实现是否已注册"这件事在判定时可能还未发生——结果是有时兜底实现生效、
 * 有时不生效，取决于类路径扫描顺序。{@code @Configuration} 类的 {@code @Bean}
 * 方法在常规组件之后处理，此时条件判定是确定的。
 *
 * 接入真实渠道的方式：在任意 {@code @Configuration} 里定义一个
 * {@code PasswordResetNotifier} Bean（SMTP / SES / SendGrid 等），
 * 并让它的 {@code productionReady()} 返回 true。本类的兜底会自动退出。
 */
@Configuration
public class PasswordResetConfig {

    /** 兜底实现：没有真实送达渠道时装配日志版。条件注解的装配顺序考虑见类注释 */
    @Bean
    @ConditionalOnMissingBean(PasswordResetNotifier.class)
    public PasswordResetNotifier loggingPasswordResetNotifier(
            ProductionProfile productionProfile,
            Environment environment
    ) {
        return new LoggingPasswordResetNotifier(productionProfile, environment);
    }
}
