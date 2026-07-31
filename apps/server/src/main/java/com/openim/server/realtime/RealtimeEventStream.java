package com.openim.server.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.config.InstanceId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实时事件流（Redis Streams）
 *
 * <b>为什么从 Pub/Sub 换到 Streams</b>：Pub/Sub 是 fire-and-forget，
 * {@code convertAndSend} 返回只代表"消息已发往当前订阅者"，不代表任何人收到了。
 * 没有持久化、没有消费位点、没有确认、没有重放。这让整条链路的实际语义是
 * <b>at-most-once</b>，尽管代码注释里写的是"至少一次"：
 *
 * <ol>
 *   <li>{@code OutboxPublisherWorker} 一旦 {@code convertAndSend} 不抛异常
 *       就 {@code markPublished}</li>
 *   <li>此刻若某个实例的订阅器正在重连（Redis 抖动、滚动更新、网络分区），
 *       它<b>永久</b>错过这条事件</li>
 *   <li>Outbox 记录已标记发布，去重键已写入，补偿路径不会再发第二次</li>
 * </ol>
 *
 * 消息类事件靠客户端按 {@code seq} 补拉兜住了，但红包状态、通话信令、设备撤销
 * 这些没有序号的事件<b>没有任何兜底</b>——红包领完了对方界面不刷新，
 * 来电信令丢了就是接不通。
 *
 * Streams 修复了这一点：{@code XADD} 成功意味着事件<b>已经落到 Redis 的数据结构里</b>，
 * 消费者按自己的位点读取，处理完才 {@code XACK}；未确认的记录留在 pending 列表中，
 * 由 {@link RealtimeEventStreamConsumer} 的 {@code XAUTOCLAIM} 重新投递。
 * 至此 {@code markPublished} 才是一个诚实的标记。
 *
 * <b>为什么每个实例一个消费组</b>：Redis 消费组是<i>竞争消费</i>——一条记录只会
 * 交给组内一个消费者。但 IM 的扇出是<i>广播</i>：每个实例上都连着不同的用户，
 * 每个实例都必须收到每一条事件。因此每个实例创建以自己 instanceId 命名的独立
 * 消费组，各自持有完整的位点与 pending 列表。代价是事件被读取 N 次（N = 实例数），
 * 这正是广播语义应有的成本。
 *
 * <b>刻意保留的边界</b>：实例长时间下线期间，它的 pending 记录可能被
 * {@code XTRIM} 修剪掉，重启后不再补投。这是可接受的——实例下线时它上面没有
 * 任何连接，事件本来也无处投递；客户端重连后按 {@code seq} 补拉消息历史。
 */
@Component
public class RealtimeEventStream {
    private static final Logger log = LoggerFactory.getLogger(RealtimeEventStream.class);

    /** 事件流的键。所有实例共享一条流，各自用独立消费组读取 */
    public static final String STREAM_KEY = "openim:events:stream";

    /** 事件 JSON 存放的字段名。整条事件序列化进一个字段，避免字段结构与载荷耦合 */
    public static final String FIELD_DATA = "data";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final InstanceId instanceId;

    private final long maxLength;
    private final long idleGroupTimeoutMillis;

    private final Counter publishedEvents;
    private final Counter publishFailures;
    private final AtomicLong streamLength = new AtomicLong();

    public RealtimeEventStream(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            InstanceId instanceId,
            MeterRegistry meterRegistry,
            @Value("${openim.realtime.stream-max-length:100000}") long maxLength,
            @Value("${openim.realtime.stream-idle-group-timeout-millis:3600000}")
            long idleGroupTimeoutMillis
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.maxLength = maxLength;
        this.idleGroupTimeoutMillis = idleGroupTimeoutMillis;
        this.publishedEvents = Counter.builder("openim.realtime.stream_published")
                .description("写入实时事件流的事件数")
                .register(meterRegistry);
        this.publishFailures = Counter.builder("openim.realtime.stream_publish_failures")
                .description("写入实时事件流失败次数；失败的事件由 Outbox 补偿重投")
                .register(meterRegistry);
        Gauge.builder("openim.realtime.stream_length", streamLength, AtomicLong::get)
                .description("实时事件流当前长度，持续增长说明消费跟不上或修剪未生效")
                .register(meterRegistry);
    }

    /**
     * 发布一个事件到流。
     *
     * @param dedupKey 事件的稳定业务标识，消费端据此识别重复投递。可为 null——
     *                 RTC 信令这类一次性事件没有稳定标识，消费端会退回用流记录 ID
     *                 去重，仍能挡住 {@code XAUTOCLAIM} 对同一条记录的重投。
     * @throws RealtimePublishException 写入失败。<b>刻意抛出而不是吞掉</b>：
     *         调用方需要据此决定后续动作——{@code OutboxPublisherWorker} 靠它
     *         把记录标记为失败并重试，若在这里静默失败，Outbox 会把一条从未
     *         真正发出的事件标记为已发布，正是本次改造要消除的那个缺陷。
     */
    public void publish(
            String targetType,
            Map<String, String> target,
            String event,
            Object payload,
            String dedupKey
    ) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("originInstanceId", instanceId.value());
        message.put("targetType", targetType);
        message.putAll(target);
        message.put("event", event);
        message.put("payload", payload);
        if (dedupKey != null) {
            message.put("dedupKey", dedupKey);
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.opsForStream().add(
                    MapRecord.create(STREAM_KEY, Map.of(FIELD_DATA, json)));
            publishedEvents.increment();
        } catch (Exception exception) {
            publishFailures.increment();
            throw new RealtimePublishException(
                    "Failed to publish realtime event to stream: " + event, exception);
        }
    }

    /**
     * 修剪流，防止无限增长。
     *
     * 用近似修剪（{@code MAXLEN ~}）：Redis 只在整个宏节点都可删除时才删，
     * 省下精确修剪的遍历开销，代价是实际长度会略高于阈值。对"防止无限增长"
     * 这个目的而言，精确的长度没有意义。
     *
     * 每个实例都会执行这个任务，重复修剪是幂等的，不需要选主。
     */
    @Scheduled(
            fixedDelayString = "${openim.realtime.stream-trim-interval-millis:60000}",
            initialDelayString = "${openim.realtime.stream-trim-initial-delay-millis:30000}"
    )
    public void trim() {
        try {
            redisTemplate.opsForStream().trim(STREAM_KEY, maxLength, true);
            StreamInfo.XInfoStream info = redisTemplate.opsForStream().info(STREAM_KEY);
            streamLength.set(info.streamLength());
        } catch (RuntimeException exception) {
            // 流还不存在（尚无任何事件）时 XINFO 会失败，属正常情况
            log.debug("Stream trim skipped reason={}", exception.getClass().getSimpleName());
        }
    }

    /**
     * 清理已下线实例遗留的消费组。
     *
     * Pod 名字带随机后缀，每次重建都是一个新的 instanceId，也就是一个新消费组。
     * 不清理的话消费组会随着每次发版累积，每个都持有自己的 pending 列表，
     * 让 {@code XINFO} 输出无法阅读，也拖慢 Redis 的组管理操作。
     *
     * 判定依据是组内所有消费者的 idle 时间：全部超过阈值即认为该实例已消失。
     * 阈值默认 1 小时，远大于正常的轮询间隔，不会误删只是暂时空闲的实例。
     */
    @Scheduled(
            fixedDelayString = "${openim.realtime.stream-group-cleanup-interval-millis:600000}",
            initialDelayString = "${openim.realtime.stream-group-cleanup-initial-delay-millis:120000}"
    )
    public void cleanupStaleConsumerGroups() {
        try {
            StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(STREAM_KEY);
            for (StreamInfo.XInfoGroup group : groups) {
                String groupName = group.groupName();
                if (groupName.equals(instanceId.value())) {
                    continue; // 自己的组
                }
                if (isGroupStale(groupName)) {
                    redisTemplate.opsForStream().destroyGroup(STREAM_KEY, groupName);
                    log.info("Destroyed stale consumer group={} (all consumers idle beyond {}ms)",
                            groupName, idleGroupTimeoutMillis);
                }
            }
        } catch (RuntimeException exception) {
            log.debug("Consumer group cleanup skipped reason={}",
                    exception.getClass().getSimpleName());
        }
    }

    /** 判断消费组是否已无活跃消费者：组内所有消费者 idle 均超过阈值（或没有消费者）即视为陈旧 */
    private boolean isGroupStale(String groupName) {
        StreamInfo.XInfoConsumers consumers =
                redisTemplate.opsForStream().consumers(STREAM_KEY, groupName);
        if (consumers.isEmpty()) {
            // 没有消费者的组：实例已经走了但组还在
            return true;
        }
        for (StreamInfo.XInfoConsumer consumer : consumers) {
            if (consumer.idleTimeMs() < idleGroupTimeoutMillis) {
                return false; // 还有活跃消费者
            }
        }
        return true;
    }

    /** 事件写入流失败。调用方据此决定重试或补偿，不应被静默吞掉 */
    public static class RealtimePublishException extends RuntimeException {
        public RealtimePublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
