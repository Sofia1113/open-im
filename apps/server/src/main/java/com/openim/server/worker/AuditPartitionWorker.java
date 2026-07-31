package com.openim.server.worker;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * audit_logs 分区维护
 *
 * 两件事：提前建好未来的分区，删掉超出保留期的旧分区。
 *
 * <b>为什么必须提前建</b>：分区表在没有匹配分区时会拒绝写入。虽然迁移里建了
 * DEFAULT 分区兜底（写入不会失败），但数据一旦落进 DEFAULT 就失去了分区的意义——
 * 按时间删除又变回全表 DELETE。提前两个月建足，使正常情况下 DEFAULT 永远是空的。
 *
 * <b>为什么用 DROP PARTITION 而不是 DELETE</b>：删除一个月的审计数据，
 * {@code DROP TABLE} 是一次元数据操作，瞬时完成且几乎不产生 WAL；
 * 等价的 {@code DELETE} 要逐行标记、写满 WAL、再等 VACUUM 回收空间，
 * 期间还会拖慢同表的写入。这是分区最主要的收益。
 *
 * <b>与 {@link DataRetentionWorker} 的分工</b>：那个 Worker 按行删除
 * outbox 与重置令牌，并保留资金相关的审计（{@code wallet.*} / {@code red_packet.*}）。
 * 这里按整个分区删除——粒度是月，不能按 action 筛选。
 * 因此<b>审计保留期必须由这里决定</b>，DataRetentionWorker 的
 * audit 逐行清理只作为 DEFAULT 分区里残留数据的兜底。
 */
@Component
public class AuditPartitionWorker {
    private static final Logger log = LoggerFactory.getLogger(AuditPartitionWorker.class);

    /** 提前创建几个月的分区。2 意味着即使任务停摆一个月也不会落进 DEFAULT */
    private static final int MONTHS_AHEAD = 2;

    private final JdbcClient jdbcClient;
    private final int retentionMonths;
    private final boolean dropEnabled;

    private final Counter partitionsCreated;
    private final Counter partitionsDropped;

    public AuditPartitionWorker(
            JdbcClient jdbcClient,
            MeterRegistry meterRegistry,
            @Value("${openim.retention.audit-months:6}") int retentionMonths,
            @Value("${openim.retention.audit-drop-partitions:false}") boolean dropEnabled
    ) {
        this.jdbcClient = jdbcClient;
        this.retentionMonths = retentionMonths;
        this.dropEnabled = dropEnabled;
        this.partitionsCreated = Counter.builder("openim.retention.audit_partitions_created")
                .description("新建的 audit_logs 月分区数")
                .register(meterRegistry);
        this.partitionsDropped = Counter.builder("openim.retention.audit_partitions_dropped")
                .description("因超出保留期而删除的 audit_logs 月分区数")
                .register(meterRegistry);
    }

    /**
     * 每天检查一次。
     *
     * 频率远高于必要（一个月建一次就够），但代价是一次幂等的函数调用，
     * 而漏建分区的代价是数据落进 DEFAULT。宁可多查。
     *
     * 每个实例都会执行；建分区的函数是幂等的，不需要选主。
     */
    @Scheduled(
            fixedDelayString = "${openim.retention.audit-partition-interval-millis:86400000}",
            initialDelayString = "${openim.retention.audit-partition-initial-delay-millis:60000}"
    )
    @Transactional
    public void maintainPartitions() {
        LocalDate today = LocalDate.now();

        for (int offset = 0; offset <= MONTHS_AHEAD; offset++) {
            LocalDate month = today.plusMonths(offset);
            try {
                String result = jdbcClient
                        .sql("SELECT ensure_audit_log_partition(:month)")
                        .param("month", Date.valueOf(month.withDayOfMonth(1)))
                        .query(String.class)
                        .single();
                if (result != null && result.contains("已创建")) {
                    partitionsCreated.increment();
                    log.info("Created audit log partition {}", result);
                }
            } catch (RuntimeException exception) {
                // 建分区失败不影响服务——DEFAULT 分区会接收写入。
                // 但这是需要人看一眼的信号，因此记 WARN 而不是 DEBUG。
                log.warn("Failed to ensure audit log partition for {} reason={}",
                        month, exception.getClass().getSimpleName());
            }
        }

        dropExpiredPartitions(today);
    }

    /**
     * 删除超出保留期的分区。
     *
     * <b>默认关闭。</b>删除审计数据是不可逆的，且保留期通常由合规要求而非
     * 存储成本决定——不该由一个默认开启的定时任务来执行。
     * 部署方确认过保留期后显式打开：{@code openim.retention.audit-drop-partitions=true}。
     */
    private void dropExpiredPartitions(LocalDate today) {
        if (!dropEnabled) {
            return;
        }
        LocalDate cutoff = today.minusMonths(retentionMonths).withDayOfMonth(1);
        try {
            List<String> dropped = jdbcClient
                    .sql("SELECT drop_audit_log_partitions_before(:cutoff)")
                    .param("cutoff", Date.valueOf(cutoff))
                    .query(String.class)
                    .list();
            for (String partition : dropped) {
                partitionsDropped.increment();
                // 删除审计数据必须留痕。静默删除是不可接受的——
                // 合规审查时"这段时间的记录去哪了"必须能回答。
                log.warn("Dropped audit log partition {} (retention {} months, cutoff {})",
                        partition, retentionMonths, cutoff);
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to drop expired audit log partitions reason={}",
                    exception.getClass().getSimpleName());
        }
    }
}
