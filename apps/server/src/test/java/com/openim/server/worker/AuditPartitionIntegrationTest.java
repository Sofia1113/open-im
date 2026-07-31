package com.openim.server.worker;

import com.openim.server.domain.repository.AuditRepository;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * audit_logs 分区集成测试
 *
 * 验证的不是"DDL 跑通了"，而是分区<b>确实在工作</b>：写入落到正确的月分区、
 * 分区裁剪生效、缺分区时不会写入失败。
 *
 * 最后一条尤其重要：分区表在没有匹配分区时会拒绝写入，而 audit_logs 的每一次
 * 写入都挂在登录、发红包这些核心操作的事务里。漏建一个月的分区就意味着
 * 那些操作全部报错——这是引入分区带来的新失败模式，必须有兜底且必须被测到。
 */
@SpringBootTest
class AuditPartitionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private AuditPartitionWorker partitionWorker;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void auditLogsIsAPartitionedTable() {
        String relkind = jdbcClient
                .sql("SELECT relkind::text FROM pg_class WHERE relname = 'audit_logs'")
                .query(String.class)
                .single();

        // 'p' = partitioned table，'r' = 普通表
        assertThat(relkind)
                .as("audit_logs 应当是分区表")
                .isEqualTo("p");
    }

    @Test
    void currentAndNextMonthPartitionsExistAfterMigration() {
        LocalDate now = LocalDate.now();
        List<String> partitions = partitionNames();

        assertThat(partitions)
                .as("迁移应当已建好当月分区")
                .contains(partitionNameFor(now));
        assertThat(partitions)
                .as("迁移应当已建好次月分区，避免月初漏建")
                .contains(partitionNameFor(now.plusMonths(1)));
    }

    /**
     * DEFAULT 分区必须存在。
     *
     * 它是"漏建分区"这个新失败模式的唯一兜底。没有它的话，一旦维护任务停摆
     * 跨月，所有带审计的操作（登录、改密、红包收发）会因为写入失败而整体报错。
     */
    @Test
    void defaultPartitionExistsAsFallback() {
        assertThat(partitionNames())
                .as("必须有 DEFAULT 分区兜底，否则漏建分区会让登录等操作直接失败")
                .contains("audit_logs_default");
    }

    @Test
    void writesLandInTheCorrectMonthlyPartition() {
        UUID actor = UUID.randomUUID();
        String action = "test.partition_routing";

        // 先建用户，audit_logs.actor_id 没有外键约束但用真实 UUID 更贴近实际
        auditRepository.recordSystemAction(action, Map.of("actor", actor.toString()));

        String partition = jdbcClient.sql("""
                        SELECT tableoid::regclass::text
                          FROM audit_logs
                         WHERE action = :action
                         LIMIT 1
                        """)
                .param("action", action)
                .query(String.class)
                .single();

        // tableoid 揭示这一行物理上存在哪个分区里
        assertThat(partition)
                .as("当月写入应落进当月分区，而不是 DEFAULT")
                .isEqualTo(partitionNameFor(LocalDate.now()));
    }

    /** 分区维护是幂等的：反复执行不报错、不重复创建 */
    @Test
    void partitionMaintenanceIsIdempotent() {
        int before = partitionNames().size();

        partitionWorker.maintainPartitions();
        partitionWorker.maintainPartitions();

        assertThat(partitionNames())
                .as("重复执行不应创建重复分区")
                .hasSize(before);
    }

    /**
     * 分区裁剪生效。
     *
     * 这是按时间分区的主要查询收益。用 EXPLAIN 确认执行计划只扫命中的分区——
     * 如果计划里出现了全部分区，说明分区键或查询条件写得不对，
     * 分区就只带来了维护复杂度而没有性能收益。
     */
    @Test
    void queriesByTimeRangePruneIrrelevantPartitions() {
        LocalDate now = LocalDate.now();
        String plan = String.join("\n", jdbcClient.sql("""
                        EXPLAIN SELECT count(*) FROM audit_logs
                         WHERE created_at >= :from AND created_at < :to
                        """)
                .param("from", Date.valueOf(now.withDayOfMonth(1)))
                .param("to", Date.valueOf(now.withDayOfMonth(1).plusMonths(1)))
                .query(String.class)
                .list());

        String currentPartition = partitionNameFor(now);
        assertThat(plan)
                .as("计划应当扫到当月分区。完整计划：\n%s", plan)
                .contains(currentPartition);

        // 下个月的分区不该出现在计划里——它不可能包含符合条件的行
        String nextPartition = partitionNameFor(now.plusMonths(1));
        assertThat(plan)
                .as("分区裁剪应当排除次月分区，否则分区没有带来查询收益。完整计划：\n%s", plan)
                .doesNotContain(nextPartition);
    }

    /**
     * 保留期删除默认关闭。
     *
     * 删除审计数据不可逆。默认开启意味着一次误配置的保留期就会静默销毁
     * 合规依据，因此这个开关必须默认关闭、由部署方显式打开。
     */
    @Test
    void partitionDroppingIsDisabledByDefault() {
        Boolean enabled = jdbcClient
                .sql("SELECT true") // 占位，真正的断言在下面
                .query(Boolean.class)
                .single();
        assertThat(enabled).isTrue();

        // 建一个远早于保留期的分区，跑一轮维护，确认它没被删
        jdbcClient.sql("SELECT ensure_audit_log_partition(:month)")
                .param("month", Date.valueOf(LocalDate.now().minusYears(2).withDayOfMonth(1)))
                .query(String.class)
                .single();
        String ancient = partitionNameFor(LocalDate.now().minusYears(2));
        assertThat(partitionNames()).contains(ancient);

        partitionWorker.maintainPartitions();

        assertThat(partitionNames())
                .as("默认配置下不应删除任何分区——审计数据的删除必须显式开启")
                .contains(ancient);
    }

    private List<String> partitionNames() {
        return jdbcClient.sql("""
                        SELECT c.relname
                          FROM pg_class c
                          JOIN pg_inherits i ON i.inhrelid = c.oid
                          JOIN pg_class p ON p.oid = i.inhparent
                         WHERE p.relname = 'audit_logs'
                        """)
                .query(String.class)
                .list();
    }

    private static String partitionNameFor(LocalDate month) {
        return String.format("audit_logs_%04d_%02d", month.getYear(), month.getMonthValue());
    }
}
