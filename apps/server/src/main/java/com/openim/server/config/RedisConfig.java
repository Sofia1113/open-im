package com.openim.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
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
