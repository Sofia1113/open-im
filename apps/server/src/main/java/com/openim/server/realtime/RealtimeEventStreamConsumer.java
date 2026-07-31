package com.openim.server.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.config.InstanceId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.Range;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实时事件流消费者
 *
 * 每个实例以自己的 instanceId 作为<b>消费组名</b>读取
 * {@link RealtimeEventStream#STREAM_KEY}，因此每个实例都能看到全部事件——
 * 这正是扇出需要的广播语义，而不是消费组默认的竞争消费
 * （原因见 {@link RealtimeEventStream} 的类注释）。
 *
 * <b>投递流程</b>：
 * <ol>
 *   <li>{@code XREADGROUP} 阻塞读取新记录</li>
 *   <li>跳过本实例自己发布的记录（主路径已在发布时同步投递过）</li>
 *   <li>按去重标识判断本实例是否已投递过（{@link EventDeduplicator}）</li>
 *   <li>投递给本地 WebSocket 连接</li>
 *   <li>{@code XACK} 确认</li>
 * </ol>
 *
 * <b>失败会怎样</b>：任何一步抛出异常都不会 {@code XACK}，记录留在 pending 列表中，
 * 由 {@link #reclaimPendingMessages()} 的 {@code XAUTOCLAIM} 在闲置超时后重新投递。
 * 这是 at-least-once 的来源：确认发生在处理之后，而不是读取之时。
 *
 * <b>为什么手写轮询而不用 StreamMessageListenerContainer</b>：需要对
 * {@code XAUTOCLAIM} 的重投时机、去重与 ACK 的先后顺序做精确控制，
 * 这些逻辑集中在一处比分散在容器配置和监听器回调里更容易验证。
 */
@Component
public class RealtimeEventStreamConsumer {
    private static final Logger log = LoggerFactory.getLogger(RealtimeEventStreamConsumer.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** 组已存在时 Redis 返回的错误前缀。首个启动的实例建组，其余实例撞上它属于正常 */
    private static final String BUSYGROUP = "BUSYGROUP";

    private final StringRedisTemplate redisTemplate;
    private final RealtimeWebSocketHandler localHandler;
    private final EventDeduplicator deduplicator;
    private final ObjectMapper objectMapper;
    private final InstanceId instanceId;

    private final int batchSize;
    private final Duration blockTimeout;
    private final Duration minIdleBeforeReclaim;
    private final long errorBackoffMillis;

    /** Redis 命令超时，用于启动时校验阻塞时长不超过它 */
    private final long commandTimeoutMillis;

    private final Counter consumed;
    private final Counter reclaimed;
    private final Counter processingFailures;
    private final AtomicLong pendingCount = new AtomicLong();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollThread;

    public RealtimeEventStreamConsumer(
            StringRedisTemplate redisTemplate,
            RealtimeWebSocketHandler localHandler,
            EventDeduplicator deduplicator,
            ObjectMapper objectMapper,
            InstanceId instanceId,
            MeterRegistry meterRegistry,
            @Value("${openim.realtime.stream-batch-size:64}") int batchSize,
            @Value("${openim.realtime.stream-block-millis:500}") long blockMillis,
            @Value("${openim.realtime.stream-reclaim-idle-millis:30000}") long reclaimIdleMillis,
            @Value("${openim.realtime.stream-error-backoff-millis:1000}") long errorBackoffMillis,
            @Value("${openim.redis.command-timeout-millis:1000}") long commandTimeoutMillis
    ) {
        this.redisTemplate = redisTemplate;
        this.localHandler = localHandler;
        this.deduplicator = deduplicator;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.batchSize = batchSize;
        this.blockTimeout = Duration.ofMillis(blockMillis);
        this.minIdleBeforeReclaim = Duration.ofMillis(reclaimIdleMillis);
        this.commandTimeoutMillis = commandTimeoutMillis;
        this.errorBackoffMillis = errorBackoffMillis;
        this.consumed = Counter.builder("openim.realtime.stream_consumed")
                .description("从事件流消费并投递的事件数")
                .register(meterRegistry);
        this.reclaimed = Counter.builder("openim.realtime.stream_reclaimed")
                .description("超时未确认后被重新认领的事件数；持续非零说明投递在失败")
                .register(meterRegistry);
        this.processingFailures = Counter.builder("openim.realtime.stream_processing_failures")
                .description("事件处理失败次数，失败的记录会留在 pending 等待重投")
                .register(meterRegistry);
        Gauge.builder("openim.realtime.stream_pending", pendingCount, AtomicLong::get)
                .description("本实例未确认的事件数，持续增长说明消费者卡住了")
                .register(meterRegistry);
    }

    /** 消费组名 = 实例 ID：每组独立消费全部事件，实现跨实例广播语义 */
    private String groupName() {
        return instanceId.value();
    }

    /** 消费者名。每组只有一个消费者——组本身已经代表了这个实例 */
    private String consumerName() {
        return instanceId.value() + "-c1";
    }

    /**
     * 校验阻塞时长小于 Redis 命令超时。
     *
     * <b>这个检查存在的原因</b>：曾经 {@code stream-block-millis=2000} 而
     * Lettuce 的命令超时是 1000ms，于是每次 {@code XREADGROUP} 都在阻塞期间超时：
     *
     * <pre>Realtime stream poll failed reason=QueryTimeoutException</pre>
     *
     * 结果是跨实例投递完全不工作，而<b>单实例下全部测试照常通过</b>——
     * 单实例的消费者收到的都是自己发的事件并直接跳过，消费失败不影响任何功能。
     * 那次是真起两个实例才发现的。
     *
     * 启动时失败而不是运行期静默降级：这两个值的关系错了，跨实例投递就是坏的，
     * 而它的症状（"偶尔收不到消息"）极难与网络问题区分。
     */
    @PostConstruct
    public void validateTimeoutRelationship() {
        if (blockTimeout.toMillis() >= commandTimeoutMillis) {
            throw new IllegalStateException(String.format("""
                    配置冲突：openim.realtime.stream-block-millis (%d) 必须小于 \
                    openim.redis.command-timeout-millis (%d)。

                    XREADGROUP 会阻塞前者那么久，而超过后者时 Lettuce 会抛
                    QueryTimeoutException——每一轮消费都失败，跨实例事件投递
                    完全不工作。而单实例部署下这个故障不可见（消费者收到的都是
                    自己发的事件），因此不会有任何测试失败。
                    """, blockTimeout.toMillis(), commandTimeoutMillis));
        }
    }

    /** 启动消费：建组后启动虚拟线程进入轮询循环 */
    @PostConstruct
    public void start() {
        ensureConsumerGroup();
        running.set(true);
        // 虚拟线程：轮询绝大部分时间阻塞在 XREADGROUP 上，不该占用一个平台线程
        pollThread = Thread.ofVirtual()
                .name("realtime-stream-consumer")
                .start(this::pollLoop);
        log.info("Realtime stream consumer started group={} stream={}",
                groupName(), RealtimeEventStream.STREAM_KEY);
    }

    /** 停止消费：中断阻塞中的 XREADGROUP，让优雅停机不必等满一个 block 超时 */
    @PreDestroy
    public void stop() {
        running.set(false);
        if (pollThread != null) {
            // 中断阻塞中的 XREADGROUP，让优雅停机不必等满一个 block 超时
            pollThread.interrupt();
        }
        log.info("Realtime stream consumer stopped group={}", groupName());
    }

    /**
     * 创建消费组。
     *
     * 从 {@code $}（当前末尾）而非 {@code 0} 开始：新实例不需要重放历史事件——
     * 那些事件产生时它上面还没有任何连接，投递出去也没有接收者。
     * {@code MKSTREAM} 让流不存在时一并创建，避免首个实例启动时报错。
     */
    private void ensureConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(
                    RealtimeEventStream.STREAM_KEY, ReadOffset.latest(), groupName());
        } catch (RedisSystemException | InvalidDataAccessApiUsageException exception) {
            String message = String.valueOf(exception.getMessage())
                    + String.valueOf(exception.getCause() == null
                            ? "" : exception.getCause().getMessage());
            if (message.contains(BUSYGROUP)) {
                log.debug("Consumer group already exists group={}", groupName());
                return;
            }
            // 组建不起来时消费者无法工作，但不应阻止应用启动——
            // 本地投递仍然可用，轮询循环会在下一轮重试建组
            log.warn("Failed to create consumer group={}, will retry in poll loop", groupName(),
                    exception);
        }
    }

    /**
     * 消费主循环：阻塞读取事件流并逐条处理。
     *
     * 只有这个线程读流，同一时刻只有一批记录在途，处理天然串行——不会出现
     * 两条记录并发投递同一连接的问题。消费组消失（NOGROUP：被清理任务误删
     * 或 Redis 被清空）时标记 {@code groupReady=false}，下一轮先重建组再继续。
     * 任何异常都退避后重试，不终止循环；停机过程中的中断属正常退出。
     */
    private void pollLoop() {
        boolean groupReady = true;
        while (running.get()) {
            try {
                if (!groupReady) {
                    ensureConsumerGroup();
                    groupReady = true;
                }
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        Consumer.from(groupName(), consumerName()),
                        StreamReadOptions.empty().count(batchSize).block(blockTimeout),
                        StreamOffset.create(RealtimeEventStream.STREAM_KEY, ReadOffset.lastConsumed())
                );
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    handleRecord(record);
                }
            } catch (Exception exception) {
                if (!running.get()) {
                    return; // 停机过程中的中断，属正常
                }
                // NOGROUP：消费组被清理任务误删或 Redis 被清空，下一轮重建
                String message = String.valueOf(exception.getMessage());
                if (message.contains("NOGROUP")) {
                    groupReady = false;
                    log.warn("Consumer group disappeared, recreating group={}", groupName());
                } else {
                    log.warn("Realtime stream poll failed reason={}",
                            exception.getClass().getSimpleName());
                }
                sleepQuietly(errorBackoffMillis);
            }
        }
    }

    /**
     * 处理单条记录：投递后确认。
     *
     * 确认<b>只在投递成功后</b>执行。投递抛出异常时不 ACK，记录留在 pending，
     * 由 {@code XAUTOCLAIM} 重投——这是整条链路 at-least-once 语义的落点。
     */
    private void handleRecord(MapRecord<String, Object, Object> record) {
        try {
            Object raw = record.getValue().get(RealtimeEventStream.FIELD_DATA);
            if (raw == null) {
                acknowledge(record.getId()); // 结构不认识的记录，确认掉避免无限重投
                return;
            }
            Map<String, Object> message = objectMapper.readValue(String.valueOf(raw), MAP_TYPE);

            // 本实例发布的事件在发布时已同步投递给本地连接，这里跳过避免重复
            if (instanceId.value().equals(message.get("originInstanceId"))) {
                acknowledge(record.getId());
                return;
            }

            // 去重标识：优先用业务标识（识别 Outbox 的重复投递），
            // 没有则退回流记录 ID（识别 XAUTOCLAIM 对同一条记录的重投）
            Object dedupKey = message.get("dedupKey");
            String key = dedupKey == null
                    ? "record:" + record.getId().getValue()
                    : String.valueOf(dedupKey);

            if (deduplicator.shouldDeliver(key)) {
                dispatch(message);
                consumed.increment();
            }
            acknowledge(record.getId());
        } catch (Exception exception) {
            processingFailures.increment();
            log.warn("Failed to process realtime stream record id={} reason={}",
                    record.getId(), exception.getClass().getSimpleName());
            // 不 ACK：留给 XAUTOCLAIM 重投
        }
    }

    /** 按 targetType 分发到本实例的本地连接：USER / DEVICE / CONVERSATION 三类 */
    private void dispatch(Map<String, Object> message) {
        String targetType = String.valueOf(message.get("targetType"));
        String event = (String) message.get("event");
        Object payload = message.get("payload");

        switch (targetType) {
            case "USER" -> {
                String userId = (String) message.get("userId");
                if (userId != null) {
                    localHandler.emitToUser(userId, event, payload);
                }
            }
            case "DEVICE" -> {
                String userId = (String) message.get("userId");
                String deviceId = (String) message.get("deviceId");
                if (userId != null && deviceId != null) {
                    localHandler.emitToDevice(userId, deviceId, event, payload);
                }
            }
            case "CONVERSATION" -> {
                String conversationId = (String) message.get("conversationId");
                if (conversationId != null) {
                    localHandler.emitToConversation(conversationId, event, payload);
                }
            }
            default -> log.warn("Unknown realtime event targetType={}", targetType);
        }
    }

    /** 确认记录：只在处理成功后调用；未确认的记录留在 pending 列表等待重投 */
    private void acknowledge(RecordId id) {
        redisTemplate.opsForStream()
                .acknowledge(RealtimeEventStream.STREAM_KEY, groupName(), id);
    }

    /**
     * 重新认领超时未确认的记录。
     *
     * 覆盖两种情况：
     * <ul>
     *   <li>投递抛出异常，记录从未被 ACK</li>
     *   <li>投递成功但进程在 ACK 前崩溃</li>
     * </ul>
     * 后者会造成一次重复投递，由 {@link EventDeduplicator} 按记录 ID 挡掉——
     * 这正是"至少一次 + 去重"取代"至多一次"的完整形态。
     *
     * 闲置阈值默认 30 秒，远大于正常处理耗时，不会与正在处理的记录竞争。
     */
    @Scheduled(
            fixedDelayString = "${openim.realtime.stream-reclaim-interval-millis:30000}",
            initialDelayString = "${openim.realtime.stream-reclaim-initial-delay-millis:30000}"
    )
    public void reclaimPendingMessages() {
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream()
                    .pending(RealtimeEventStream.STREAM_KEY, groupName());
            if (summary == null || summary.getTotalPendingMessages() == 0) {
                pendingCount.set(0);
                return;
            }
            pendingCount.set(summary.getTotalPendingMessages());

            PendingMessages pending = redisTemplate.opsForStream().pending(
                    RealtimeEventStream.STREAM_KEY,
                    Consumer.from(groupName(), consumerName()),
                    Range.unbounded(),
                    batchSize);

            for (org.springframework.data.redis.connection.stream.PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery().compareTo(minIdleBeforeReclaim) < 0) {
                    continue; // 可能仍在处理中
                }
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .range(RealtimeEventStream.STREAM_KEY,
                                Range.closed(message.getIdAsString(), message.getIdAsString()));
                if (records == null || records.isEmpty()) {
                    // 记录已被 XTRIM 修剪掉，确认它以免永远留在 pending 里
                    acknowledge(message.getId());
                    continue;
                }
                reclaimed.increment();
                records.forEach(this::handleRecord);
            }
        } catch (RuntimeException exception) {
            log.debug("Pending reclaim skipped reason={}", exception.getClass().getSimpleName());
        }
    }

    /** 退避等待：被中断时恢复中断标记并立即返回，不吞掉中断 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
