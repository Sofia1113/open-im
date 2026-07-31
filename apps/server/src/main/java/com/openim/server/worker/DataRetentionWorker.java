package com.openim.server.worker;

import com.openim.server.auth.PasswordResetService;
import com.openim.server.domain.repository.AuditRepository;
import com.openim.server.domain.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 数据保留期清理
 *
 * <b>为什么需要它</b>：{@code outbox_events}、{@code audit_logs}、
 * {@code password_reset_tokens} 三张表此前都是只增不减的。每条消息、
 * 每次登录、每次红包状态变化都往里写行，而没有任何东西删过一行。
 * 后果是渐进的、不会触发任何告警的劣化：
 * <ul>
 *   <li>索引持续膨胀，{@code countPending()} 这类每次采集指标都要跑的查询越来越慢</li>
 *   <li>备份体积里绝大部分是没有用的历史事件，恢复时间随之延长</li>
 *   <li>{@code VACUUM} 的工作量线性增长</li>
 * </ul>
 * 这类问题在演示环境永远不会显现，上线几个月后才开始疼——而那时表已经很大，
 * 首次清理本身就成了一次风险操作。
 *
 * <b>分批删除</b>：每轮每张表最多删 {@code batchSize} 行。首次启用时表里可能
 * 已经积累了几百万行，一次 {@code DELETE} 会锁住大量行并产生一个巨大的事务。
 * 分批意味着首次清理需要跑很多轮才追上，这是有意的——追赶速度不重要，
 * 不影响线上请求才重要。
 *
 * <b>保留期的依据</b>：
 * <table>
 *   <caption>各表保留期</caption>
 *   <tr><th>表</th><th>默认保留</th><th>依据</th></tr>
 *   <tr><td>outbox_events（已发布）</td><td>7 天</td>
 *       <td>补偿窗口最长 5 分钟，7 天纯粹是留给排查</td></tr>
 *   <tr><td>password_reset_tokens</td><td>7 天</td>
 *       <td>排查"账号是谁重置的"</td></tr>
 *   <tr><td>audit_logs</td><td>180 天</td>
 *       <td>合规要求决定，不是存储成本决定</td></tr>
 * </table>
 *
 * 资金相关的审计（{@code wallet.*} / {@code red_packet.*}）不参与清理，
 * 见 {@link AuditRepository#deleteOlderThan}。
 */
@Component
public class DataRetentionWorker {
    private static final Logger log = LoggerFactory.getLogger(DataRetentionWorker.class);

    private final OutboxRepository outboxRepository;
    private final AuditRepository auditRepository;
    private final PasswordResetService passwordResetService;

    private final Duration outboxRetention;
    private final Duration auditRetention;
    private final Duration resetTokenRetention;
    private final int batchSize;

    private final Counter outboxDeleted;
    private final Counter auditDeleted;
    private final Counter resetTokensDeleted;

    public DataRetentionWorker(
            OutboxRepository outboxRepository,
            AuditRepository auditRepository,
            PasswordResetService passwordResetService,
            MeterRegistry meterRegistry,
            @Value("${openim.retention.outbox-days:7}") long outboxDays,
            @Value("${openim.retention.audit-days:180}") long auditDays,
            @Value("${openim.auth.password-reset-retention-days:7}") long resetTokenDays,
            @Value("${openim.retention.batch-size:5000}") int batchSize
    ) {
        this.outboxRepository = outboxRepository;
        this.auditRepository = auditRepository;
        this.passwordResetService = passwordResetService;
        this.outboxRetention = Duration.ofDays(outboxDays);
        this.auditRetention = Duration.ofDays(auditDays);
        this.resetTokenRetention = Duration.ofDays(resetTokenDays);
        this.batchSize = batchSize;
        this.outboxDeleted = Counter.builder("openim.retention.outbox_deleted")
                .description("已清理的已发布 outbox 事件数")
                .register(meterRegistry);
        this.auditDeleted = Counter.builder("openim.retention.audit_deleted")
                .description("已清理的审计记录数")
                .register(meterRegistry);
        this.resetTokensDeleted = Counter.builder("openim.retention.reset_tokens_deleted")
                .description("已清理的密码重置令牌数")
                .register(meterRegistry);
    }

    /**
     * 执行一轮清理。
     *
     * 每小时一次，不在业务低谷集中执行——分散的小批量删除对线上影响
     * 比"凌晨一次性删一天量"更小，也更容易发现问题（每小时都有指标，
     * 而不是每天一个尖峰）。
     *
     * 三张表各自独立处理：其中一张失败不影响其余两张。刻意<b>不</b>加
     * {@code @Transactional}——三次删除放进一个事务只会让锁持有更久，
     * 而它们之间没有任何一致性要求。
     *
     * 每个实例都会运行这个任务。重复删除是幂等的（删不到就是 0 行），
     * 不需要选主；代价只是多几次无结果的查询。
     */
    @Scheduled(
            fixedDelayString = "${openim.retention.interval-millis:3600000}",
            initialDelayString = "${openim.retention.initial-delay-millis:300000}"
    )
    public void purge() {
        Instant now = Instant.now();

        int outbox = safely("outbox_events", () -> outboxRepository
                .deletePublishedBefore(now.minus(outboxRetention), batchSize));
        outboxDeleted.increment(outbox);

        int audit = safely("audit_logs", () -> auditRepository
                .deleteOlderThan(now.minus(auditRetention), batchSize));
        auditDeleted.increment(audit);

        int tokens = safely("password_reset_tokens", () -> passwordResetService
                .purgeStaleTokens(resetTokenRetention));
        resetTokensDeleted.increment(tokens);

        if (outbox + audit + tokens > 0) {
            log.info("Retention purge removed outbox={} audit={} resetTokens={}",
                    outbox, audit, tokens);
        }
        // 删满一批说明还有积压，下一轮会继续。首次启用时这会持续很多轮。
        if (outbox == batchSize || audit == batchSize) {
            log.info("Retention batch was full, backlog remains; next round continues");
        }
    }

    /**
     * 安全执行一次删除：捕获异常并记 WARN，返回 0 而不是让调度线程死掉。
     *
     * 清理失败不影响其他表，失败项会在下一轮重试，积压由指标反映。
     *
     * @return 实际删除的行数；执行失败时返回 0
     */
    private int safely(String table, java.util.function.IntSupplier deletion) {
        try {
            return deletion.getAsInt();
        } catch (RuntimeException exception) {
            // 清理失败不应影响其他表，也不该让调度线程死掉——
            // 下一轮会重试，而积压本身由指标反映
            log.warn("Retention purge failed for table={} reason={}",
                    table, exception.getClass().getSimpleName());
            return 0;
        }
    }
}
