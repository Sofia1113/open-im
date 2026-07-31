package com.openim.server.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 可用性探测
 *
 * <b>为什么需要它</b>：Redis 曾被放进就绪探针组
 * （{@code management.endpoint.health.group.readiness.include}）。
 * 那个配置有一个致命的连锁反应——Redis 是所有实例共用的单点，它一旦不可用，
 * <b>每一个</b> Pod 的就绪探针同时失败，Service 的端点列表被清空，
 * 整站返回 503。
 *
 * 更糟的是，它让代码里所有精心设计的降级路径变成不可达代码：
 * <ul>
 *   <li>{@link com.openim.server.ratelimit.RateLimiter} 的进程内令牌桶降级</li>
 *   <li>{@link com.openim.server.realtime.EventDeduplicator} 的故障放行</li>
 *   <li>{@link com.openim.server.realtime.PresenceService} 的全部 try-catch 兜底</li>
 * </ul>
 * 这些分支只有在"Redis 挂了但服务还在处理请求"时才会执行，而旧配置下
 * 服务此刻已经被摘掉流量了。写了容错却永远不会触发，比没有容错更危险——
 * 它让人以为系统能扛住 Redis 故障。
 *
 * 因此就绪探针只保留数据库（没有数据库确实什么都做不了），Redis 的状态改由
 * 本组件以指标形式暴露，交给告警而不是交给负载均衡器处置。
 *
 * <b>Redis 不可用时的服务水平</b>：
 * <table>
 *   <caption>降级矩阵</caption>
 *   <tr><th>能力</th><th>降级后行为</th></tr>
 *   <tr><td>限流</td><td>进程内令牌桶，配额变为每实例独立，凭证类策略收紧 4 倍</td></tr>
 *   <tr><td>在线状态</td><td>全部显示离线（不影响消息收发）</td></tr>
 *   <tr><td>跨实例投递</td><td>仅本实例连接可达；跨实例消息由客户端按 seq 补拉兜底</td></tr>
 *   <tr><td>事件去重</td><td>放行，可能重复推送；客户端按 message.id 二次去重</td></tr>
 *   <tr><td>登录/发消息/红包</td><td>不受影响，全部走数据库</td></tr>
 * </table>
 */
@Component
public class RedisAvailability {
    private static final Logger log = LoggerFactory.getLogger(RedisAvailability.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 初始值为 true：启动瞬间尚未探测过，此时报告不可用会让刚拉起的实例
     * 立刻触发告警。首次探测在一个探测周期内就会修正它。
     */
    private final AtomicBoolean available = new AtomicBoolean(true);

    /** 状态翻转次数。持续增长说明 Redis 在抖动，比单纯的"当前不可用"更值得告警 */
    private final Counter transitions;

    /** 连续不可用的探测轮数，用于日志中体现故障持续时间而不是每轮重复刷屏 */
    private final AtomicLong consecutiveFailures = new AtomicLong();

    public RedisAvailability(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.transitions = Counter.builder("openim.redis.availability_transitions")
                .description("Redis 可用性状态翻转次数，持续增长说明连接在抖动")
                .register(meterRegistry);
        Gauge.builder("openim.redis.available", available, b -> b.get() ? 1 : 0)
                .description("Redis 当前是否可用：1 可用，0 不可用（服务降级运行，非不可用）")
                .register(meterRegistry);
    }

    /**
     * 探测 Redis 连通性。
     *
     * 用 {@code PING} 而不是读写一个探测键：PING 不占用键空间，
     * 也不会因为某个键恰好被逐出而误判。
     */
    @Scheduled(
            fixedDelayString = "${openim.redis.health-probe-interval-millis:10000}",
            initialDelayString = "${openim.redis.health-probe-initial-delay-millis:5000}"
    )
    public void probe() {
        boolean healthy;
        try {
            String pong = redisTemplate.execute(
                    (org.springframework.data.redis.core.RedisCallback<String>)
                            connection -> connection.ping());
            healthy = "PONG".equalsIgnoreCase(pong);
        } catch (RuntimeException exception) {
            healthy = false;
        }
        record(healthy);
    }

    /**
     * 更新可用性状态并处理状态翻转。
     *
     * 状态未变化时只累计连续失败轮数，每 30 轮才刷一条警告日志，避免故障期间刷屏；
     * 状态翻转时才累计翻转计数（抖动信号）并记录恢复/故障日志。
     */
    private void record(boolean healthy) {
        boolean previous = available.getAndSet(healthy);
        if (previous == healthy) {
            if (!healthy) {
                long rounds = consecutiveFailures.incrementAndGet();
                // 每 30 轮（默认 5 分钟）提醒一次，避免故障期间刷屏
                if (rounds % 30 == 0) {
                    log.warn("Redis still unavailable after {} probes; running degraded", rounds);
                }
            }
            return;
        }
        transitions.increment();
        if (healthy) {
            log.info("Redis recovered after {} failed probes", consecutiveFailures.getAndSet(0));
        } else {
            consecutiveFailures.set(1);
            log.warn("Redis became unavailable; service continues in degraded mode "
                    + "(rate limiting local-only, presence offline, cross-instance fan-out paused)");
        }
    }

    /**
     * 当前是否可用。
     *
     * 供需要提前分流的调用方使用——例如批量在线状态查询可以在已知不可用时
     * 直接返回全部离线，省掉一次注定超时的往返。
     *
     * 这是一个<b>提示</b>而非保证：探测有周期，返回 true 时 Redis 也可能刚好挂掉，
     * 因此调用方仍然必须保留自己的 try-catch。
     */
    public boolean isAvailable() {
        return available.get();
    }
}
