package com.openim.server.config;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lettuce 超时与断连行为
 *
 * <b>锁定一个故障注入实测出来的修复。</b>
 *
 * {@code RedisConfig} 曾经 import 了 {@code ClientOptions} 与 {@code TimeoutOptions}
 * 却从未使用——看起来配过超时，实际生效的是 Lettuce 默认值：命令超时 60 秒，
 * 且断连期间命令<b>排队等重连</b>而不是立即失败。
 *
 * 后果是 Redis 挂掉后每个碰到 Redis 的请求阻塞整整 60 秒才走降级分支。
 * "降级运行"实际变成"每个请求慢一分钟"——功能上等同于不可用，
 * 而且线程被长期占住，比直接失败更糟。
 *
 * <b>为什么单元测试原本发现不了</b>：所有降级测试注入的都是<b>立即抛出</b>的异常
 * （mock 的 {@code thenThrow}），而真实故障的形态是<b>超时</b>。
 * 两者进入同一段 catch，但对系统的影响完全不同。这个差别只有停掉真实的 Redis
 * 才会显现——见 {@code load-test/chaos-degradation.sh}。
 *
 * 本测试不重现超时（那需要真实故障），而是锁定配置本身不被移除。
 */
class RedisTimeoutConfigTest {

    private static final long COMMAND_TIMEOUT_MILLIS = 1_000;
    private static final long CONNECT_TIMEOUT_MILLIS = 2_000;

    private LettuceClientConfiguration configure() {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder();
        new RedisConfig()
                .lettuceTimeoutCustomizer(COMMAND_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .customize(builder);
        return builder.build();
    }

    /**
     * 命令超时必须远短于 Lettuce 的 60 秒默认值。
     *
     * 本项目对 Redis 的操作全是 GET/SET/XADD 这类简单命令，正常在毫秒级。
     * 超过 1 秒必然是故障而非慢。
     */
    @Test
    void commandTimeoutIsShortEnoughForDegradationToBeUseful() {
        Duration timeout = configure().getCommandTimeout();

        assertThat(timeout)
                .as("命令超时必须显式配置，否则 Lettuce 默认 60 秒会让降级变成慢速挂死")
                .isEqualTo(Duration.ofMillis(COMMAND_TIMEOUT_MILLIS));
        assertThat(timeout)
                .as("超时必须远短于一次 HTTP 请求可接受的总时长")
                .isLessThan(Duration.ofSeconds(5));
    }

    /**
     * <b>断连时必须拒绝命令，而不是排队等重连。</b>
     *
     * 这是让降级"快速失败"的关键开关。默认的 {@code DEFAULT}（等价于
     * ACCEPT_COMMANDS）会把命令缓冲起来，调用方感知到的就是挂住——
     * catch 块要等到超时才执行。
     */
    @Test
    void disconnectedCommandsAreRejectedRatherThanQueued() {
        ClientOptions options = configure().getClientOptions().orElseThrow(() ->
                new AssertionError("ClientOptions 未配置——超时与断连行为都会退回默认值"));

        assertThat(options.getDisconnectedBehavior())
                .as("必须是 REJECT_COMMANDS：默认行为会缓冲命令等重连，"
                        + "使降级分支要等到超时才执行")
                .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    @Test
    void connectTimeoutIsBounded() {
        ClientOptions options = configure().getClientOptions().orElseThrow();

        assertThat(options.getSocketOptions().getConnectTimeout())
                .as("连接超时必须有界，否则 Redis 不可达时建连会长时间挂住")
                .isEqualTo(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS));
    }

    /**
     * 自动重连必须开启。
     *
     * 关掉的话 Redis 恢复后不会自动重连——降级状态会永久保持，
     * 需要重启实例才能恢复。故障注入测试的"自动恢复"那一项验证的就是这个。
     */
    @Test
    void autoReconnectIsEnabledSoRecoveryNeedsNoIntervention() {
        ClientOptions options = configure().getClientOptions().orElseThrow();

        assertThat(options.isAutoReconnect())
                .as("必须开启自动重连，否则 Redis 恢复后仍需重启实例")
                .isTrue();
    }

    /** TCP keep-alive 让操作系统更快发现对端消失，而不是等下一次写入失败 */
    @Test
    void tcpKeepAliveIsEnabled() {
        ClientOptions options = configure().getClientOptions().orElseThrow();

        assertThat(options.getSocketOptions().isKeepAlive()).isTrue();
    }
}
