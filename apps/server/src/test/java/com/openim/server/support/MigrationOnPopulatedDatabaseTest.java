package com.openim.server.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 在<b>有数据</b>的数据库上验证迁移
 *
 * ── 为什么需要这个测试 ────────────────────────────────────
 *
 * 其余所有集成测试都跑在每次全新的 Testcontainers 数据库上。那意味着迁移
 * 只在<b>空库</b>上被验证过——而生产库总是有数据的。
 *
 * 这个盲区已经造成过一次真实故障：{@code V7__audit_log_partitioning.sql}
 * 的第一版先把历史数据插进 DEFAULT 分区，再创建当月分区。PostgreSQL 创建新分区时
 * 会校验 DEFAULT 里没有属于新范围的行，因此：
 * <ul>
 *   <li>空库上——DEFAULT 为空，没有冲突，迁移成功，全部测试通过</li>
 *   <li>有数据的库上——DEFAULT 里有当月的行，迁移<b>失败</b>，应用起不来</li>
 * </ul>
 * 那次是在本地 E2E 环境（数据库里留有前几轮的审计记录）偶然发现的。
 * 如果先上生产，结果是一次失败的部署加一次紧急回滚。
 *
 * ── 这个测试怎么做 ────────────────────────────────────────
 *
 * 分两步跑迁移，中间插入数据：
 * <ol>
 *   <li>迁移到 V6（分区之前的最后一个版本）</li>
 *   <li>按 V6 的 schema 插入跨多个月份的审计记录</li>
 *   <li>迁移到最新版本 —— V7 必须在这些数据上成功</li>
 * </ol>
 * 用独立的容器而不是共享的那个：它需要控制迁移的中间状态，
 * 而共享容器已经是最新 schema 了。
 */
class MigrationOnPopulatedDatabaseTest {

    /** V7 之前的最后一个版本。新增迁移后这个值不需要改——它锚定的是"分区之前" */
    private static final String VERSION_BEFORE_PARTITIONING = "6";

    @Test
    void migrationsSucceedOnADatabaseThatAlreadyHasData() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("postgres:17-alpine"))
                .withDatabaseName("openim")
                .withUsername("openim")
                .withPassword("openim")) {
            postgres.start();

            DataSource dataSource = dataSourceFor(postgres);

            // 1. 先迁移到分区之前的版本
            Flyway toV6 = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .target(VERSION_BEFORE_PARTITIONING)
                    .load();
            toV6.migrate();

            // 2. 插入跨月份的审计数据。
            //    必须包含**当月**的记录——那正是触发冲突的条件：
            //    创建当月分区时，DEFAULT 里若已有当月的行就会失败。
            seedAuditLogsAcrossMonths(dataSource);
            long seeded = countAuditLogs(dataSource);
            assertThat(seeded)
                    .as("测试前置：应当已插入跨月份的审计记录")
                    .isGreaterThanOrEqualTo(4);

            // 3. 迁移到最新。这一步在修复前会抛
            //    "updated partition constraint for default partition would be violated"
            Flyway toLatest = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load();

            assertThatCode(toLatest::migrate)
                    .as("迁移必须能在已有数据的库上成功——生产库总是有数据的")
                    .doesNotThrowAnyException();

            // 4. 数据一条不少
            assertThat(countAuditLogs(dataSource))
                    .as("分区迁移不得丢失任何审计记录")
                    .isEqualTo(seeded);

            // 5. 数据落进了月分区，而不是全部堆在 DEFAULT。
            //    全在 DEFAULT 意味着分区没起作用——按分区删除又退回全表 DELETE。
            assertThat(rowsInDefaultPartition(dataSource))
                    .as("历史数据应当被路由到对应的月分区，DEFAULT 应为空")
                    .isZero();

            List<String> partitions = partitionNames(dataSource);
            assertThat(partitions)
                    .as("应当为历史数据涉及的每个月都建了分区。实际分区：%s", partitions)
                    .hasSizeGreaterThan(2);
        }
    }

    /**
     * 插入跨越多个月份的审计记录。
     *
     * 直接用 SQL 而不是 AuditRepository：这个测试的目的是验证迁移，
     * 不能依赖应用层——而且 V6 时点的 schema 与当前的 Repository 未必兼容。
     */
    private void seedAuditLogsAcrossMonths(DataSource dataSource) {
        List<String> offsets = List.of(
                "now()",                              // 当月 —— 触发冲突的关键
                "now() - interval '1 month'",
                "now() - interval '2 months'",
                "now() - interval '13 months'"        // 跨年
        );
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String offset : offsets) {
                statement.executeUpdate(String.format("""
                        INSERT INTO audit_logs
                            (id, actor_type, actor_id, action, metadata, created_at)
                        VALUES ('%s', 'SYSTEM', NULL, 'test.migration_seed',
                                '{}'::jsonb, %s)
                        """, UUID.randomUUID(), offset));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("插入测试数据失败", exception);
        }
    }

    private long countAuditLogs(DataSource dataSource) {
        return querySingleLong(dataSource, "SELECT count(*) FROM audit_logs");
    }

    private long rowsInDefaultPartition(DataSource dataSource) {
        return querySingleLong(dataSource, "SELECT count(*) FROM audit_logs_default");
    }

    private List<String> partitionNames(DataSource dataSource) {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT c.relname
                       FROM pg_class c
                       JOIN pg_inherits i ON i.inhrelid = c.oid
                       JOIN pg_class p ON p.oid = i.inhparent
                      WHERE p.relname = 'audit_logs'
                      ORDER BY c.relname
                     """)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("查询分区列表失败", exception);
        }
        return names;
    }

    private long querySingleLong(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception exception) {
            throw new IllegalStateException("查询失败: " + sql, exception);
        }
    }

    private DataSource dataSourceFor(PostgreSQLContainer<?> postgres) {
        org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
                new org.springframework.jdbc.datasource.DriverManagerDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }
}
