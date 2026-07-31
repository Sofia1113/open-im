package com.openim.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 连接与序列化配置
 *
 * 键统一用 String，值统一用 JSON（Jackson），与 Spring Data 默认的 JDK 序列化相比，
 * JSON 序列化的好处是值在 redis-cli 里可读，且跨实例/跨语言兼容。
 *
 * Pub/Sub 使用独立的监听容器，与命令通道解耦，避免高频消息影响读写延迟。
 */
@Configuration
public class RedisConfig {

    /**
     * Lettuce 超时与断连行为。
     *
     * <b>这是故障注入测试（load-test/chaos-degradation.sh）发现的一个真实缺陷。</b>
     *
     * 此前本类 import 了 {@code ClientOptions}、{@code TimeoutOptions} 和
     * {@code Duration} 却<b>从未使用</b>——有人打算配置超时但没写完，而 import
     * 的存在让它看起来已经配过了。实际生效的是 Lettuce 的默认值：
     * 命令超时 60 秒，且断连期间命令<b>排队等待重连</b>而不是立即失败。
     *
     * 后果是：Redis 挂掉后，每个碰到 Redis 的请求会阻塞整整 60 秒才走进降级分支。
     * 所谓的"降级运行"实际是"每个请求慢一分钟"——功能上等同于不可用，
     * 而且比直接失败更糟：线程被长期占住，连接池和 Tomcat 的工作线程一起耗尽。
     *
     * 单元测试发现不了这一点，因为它们注入的是<b>立即抛出</b>的异常，
     * 而真实故障的形态是<b>超时</b>。两者触发的是同一段 catch，
     * 但对系统的影响完全不同。
     *
     * <b>两项关键配置</b>：
     * <ul>
     *   <li>{@code commandTimeout} 降到 1 秒。本项目对 Redis 的操作全部是
     *       GET/SET/XADD/XREADGROUP 这类简单命令，正常在毫秒级；
     *       1 秒已经是一个极宽松的上限，超过它必然是故障而不是慢。</li>
     *   <li>{@code disconnectedBehavior = REJECT_COMMANDS}。默认行为
     *       （{@code DEFAULT}，等价于 ACCEPT_COMMANDS）会把命令缓冲起来等重连，
     *       调用方感知到的就是挂住。REJECT 让它立即抛异常，降级分支得以立刻执行。</li>
     * </ul>
     *
     * <b>阻塞读是一个必须小心的例外</b>，这里踩过一次坑：
     *
     * 事件流消费者用 {@code XREADGROUP ... BLOCK 2000}，它的执行时间本来就是 2 秒。
     * 第一版配置里写了 {@code TimeoutOptions.fixedTimeout(commandTimeout)}，
     * 注释还断言"带 BLOCK 的命令由 Lettuce 自动延长，不受影响"——
     * <b>那个断言是错的</b>。{@code fixedTimeout} 恰恰覆盖了 Lettuce 对阻塞命令的
     * 智能超时推导，于是每次 {@code XREADGROUP} 都在 1 秒时超时：
     *
     * <pre>Realtime stream poll failed reason=QueryTimeoutException</pre>
     *
     * 后果是<b>跨实例投递完全不工作</b>。而单实例下所有测试照常通过——
     * 单实例的消费者收到的全是自己发的事件（{@code originInstanceId} 相同直接跳过），
     * 消费失败不影响任何功能，因为本地投递在发布时已同步完成。
     * 这个缺陷只有真的起两个实例才会暴露（见 load-test/verify-multi-instance.mjs）。
     *
     * 修这个问题时又踩了第二次：改成
     * {@code TimeoutOptions.builder().timeoutCommands(true).build()} 会抛
     * {@code IllegalStateException: TimeoutSource is required for enabled timeouts}——
     * 那个开关需要配套提供 {@code TimeoutSource}，应用直接起不来。
     *
     * <b>正确做法是完全不碰 {@code timeoutOptions}</b>，用 Lettuce 的默认值。
     * 真正的保障放在配置约束上：{@code openim.realtime.stream-block-millis} (500ms)
     * 必须小于 {@code commandTimeout} (1000ms)，且
     * {@link com.openim.server.realtime.RealtimeEventStreamConsumer} 在启动时校验
     * 这个关系。这比依赖某个客户端库的超时推导细节更可靠——它是一条能被读懂、
     * 能被测试、不随库版本变化的不变量。
     *
     * 两次踩坑的共同教训：<b>对第三方库行为的断言必须实测</b>。
     * 前两版的注释都写得很确定，而两次都是错的。
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceTimeoutCustomizer(
            @Value("${openim.redis.command-timeout-millis:1000}") long commandTimeoutMillis,
            @Value("${openim.redis.connect-timeout-millis:2000}") long connectTimeoutMillis
    ) {
        Duration commandTimeout = Duration.ofMillis(commandTimeoutMillis);
        return builder -> builder
                .commandTimeout(commandTimeout)
                .clientOptions(ClientOptions.builder()
                        // 断连时立即拒绝命令，不排队等重连。
                        // 这是让降级"快速失败"而不是"慢慢超时"的关键开关。
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                                // TCP keep-alive：让操作系统更快发现对端消失，
                                // 而不是等到下一次写入失败
                                .keepAlive(true)
                                .build())
                        // **刻意不设置 timeoutOptions。** 两个变体都试过且都是错的：
                        //   fixedTimeout(commandTimeout) → 阻塞命令必然超时
                        //   timeoutCommands(true)        → 缺 TimeoutSource，启动失败
                        // 默认值配合"BLOCK 时长 < commandTimeout"的配置约束才是正确解。
                        //
                        // 连接建立前不阻塞命令：启动时 Redis 不可用也能正常起来，
                        // 降级路径接管
                        .autoReconnect(true)
                        .build());
    }

    /**
     * 通用 Redis 模板,配置键与值的序列化策略。
     *
     * 键统一用 String,值统一用 JSON(Jackson)——值在 redis-cli 里可读,
     * 且跨实例、跨语言兼容;Hash 结构的键与值采用同样的策略。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory factory,
            ObjectMapper objectMapper
    ) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
        return template;
    }

    /**
     * Pub/Sub 监听容器。
     *
     * 消息投递在独立线程池中执行：即使某个监听器处理慢，也不会阻塞 Lettuce 的 I/O 线程，
     * 更不会影响并发的读写命令。
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        // 使用虚拟线程（Java 21），大量并发监听器也不会耗尽平台线程
        container.setTaskExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        return container;
    }
}
