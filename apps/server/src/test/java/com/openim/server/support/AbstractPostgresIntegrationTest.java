package com.openim.server.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类：提供真实的 PostgreSQL 实例
 *
 * 不使用 H2 等内存数据库，因为本项目依赖多个 PostgreSQL 专有语义：
 * {@code FOR UPDATE SKIP LOCKED}、{@code UPDATE ... RETURNING}、jsonb、部分索引。
 * 用方言不同的数据库测试，等于没有测试真正会在生产运行的 SQL。
 *
 * 容器采用单例模式（静态块启动而非 {@code @Container}），整个测试 JVM 内只启动一次，
 * 由 Testcontainers 的 Ryuk 在退出时清理。
 */
public abstract class AbstractPostgresIntegrationTest {
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("openim")
                    .withUsername("openim")
                    .withPassword("openim");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
