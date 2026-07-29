package com.openim.server.observability;

import com.openim.server.domain.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 业务指标
 *
 * 对应 docs/01-architecture/ARCHITECTURE.md §9 列出的指标清单。
 * 只暴露聚合量，不含用户标识、消息内容或金额明细——指标会被长期存储并广泛访问，
 * 不适合承载任何可用于识别个人的数据。
 */
@Component
public class DomainMetrics {
    private final Counter messagesSent;
    private final Counter redPacketsCreated;
    private final Counter redPacketsClaimed;
    private final Counter redPacketsRefunded;
    private final Counter rateLimitRejections;
    private final Counter tokenReuseDetections;
    private final Counter realtimeSendFailures;

    /**
     * 刻意不注入 {@code RealtimeWebSocketHandler}：连接数 Gauge 由 handler 自行注册，
     * 否则 handler 需要记录指标、指标又需要读 handler 的连接数，会形成循环依赖。
     */
    public DomainMetrics(
            MeterRegistry registry,
            OutboxRepository outboxRepository
    ) {
        this.messagesSent = Counter.builder("openim.messages.sent")
                .description("成功写入的消息条数")
                .register(registry);
        // 刻意不叫 red_packets.created：Prometheus 把 _created 视为 OpenMetrics 保留后缀
        // 并会将其剥离，导致该指标与其它 red_packets.* 指标撞名成 openim_red_packets_total
        this.redPacketsCreated = Counter.builder("openim.red_packets.issued")
                .description("创建的红包个数")
                .register(registry);
        this.redPacketsClaimed = Counter.builder("openim.red_packets.claimed")
                .description("成功领取的次数")
                .register(registry);
        this.redPacketsRefunded = Counter.builder("openim.red_packets.refunded")
                .description("过期退款的红包个数")
                .register(registry);
        this.rateLimitRejections = Counter.builder("openim.rate_limit.rejections")
                .description("被限流拒绝的请求数")
                .register(registry);
        this.tokenReuseDetections = Counter.builder("openim.auth.token_reuse_detected")
                .description("检测到刷新令牌重放并撤销设备的次数")
                .register(registry);
        this.realtimeSendFailures = Counter.builder("openim.realtime.send_failures")
                .description("实时事件发送失败次数（含缓冲区溢出）")
                .register(registry);

        // Outbox 积压是投递健康度的核心信号：持续增长说明消费端跟不上或在反复失败
        Gauge.builder("openim.outbox.pending", outboxRepository, DomainMetrics::countPendingQuietly)
                .description("尚未发布的 outbox 事件数")
                .register(registry);
    }

    /**
     * 采集 Gauge 时数据库可能不可用。
     *
     * 指标采集失败不应向上抛出——它会让 /actuator/prometheus 整体返回 500，
     * 反而掩盖了其他仍然有效的指标。
     */
    private static double countPendingQuietly(OutboxRepository repository) {
        try {
            return repository.countPending();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    public void recordMessageSent() {
        messagesSent.increment();
    }

    public void recordRedPacketCreated() {
        redPacketsCreated.increment();
    }

    public void recordRedPacketClaimed() {
        redPacketsClaimed.increment();
    }

    public void recordRedPacketRefunded() {
        redPacketsRefunded.increment();
    }

    public void recordRateLimitRejection() {
        rateLimitRejections.increment();
    }

    public void recordTokenReuseDetected() {
        tokenReuseDetections.increment();
    }

    public void recordRealtimeSendFailure() {
        realtimeSendFailures.increment();
    }
}
