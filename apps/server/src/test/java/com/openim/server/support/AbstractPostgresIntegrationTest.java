package com.openim.server.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类：提供真实的 PostgreSQL + Redis 实例
 *
 * 同一个 JVM 进程内两个容器各启动一次，由 Ryuk 在退出时清理。
 * 同时提供两个容器是因为：
 * <ul>
 *   <li>Pub/Sub 跨实例扇出需要真实 Redis 验证消息的发布与接收</li>
 *   <li>分布式限流配额需要真实 Redis 才能验证多次请求共享同一个桶</li>
 *   <li>presence TTL 需要 Redis keyspace 语义，用 mock 无法准确测试</li>
 * </ul>
 */
public abstract class AbstractPostgresIntegrationTest {
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("openim")
                    .withUsername("openim")
                    .withPassword("openim");

    @SuppressWarnings("resource")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--appendonly", "no");

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // redis://host:port 格式，与 spring.data.redis.url 期望的格式一致
        registry.add("spring.data.redis.url", () ->
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        // MockMvc 环境没有真实 Servlet 容器，容器级 WebSocket 配置无法初始化
        registry.add("openim.realtime.container-config-enabled", () -> "false");
    }
}
