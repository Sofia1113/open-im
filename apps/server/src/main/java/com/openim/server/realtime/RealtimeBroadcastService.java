package com.openim.server.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 实时事件广播服务
 *
 * 统一处理两条投递路径：
 * <ol>
 *   <li>本地 WebSocket 会话（同实例用户），发布时同步完成，延迟最低</li>
 *   <li>{@link RealtimeEventStream}（其他实例上的用户），由
 *       {@link RealtimeEventStreamConsumer} 消费后本地分发</li>
 * </ol>
 *
 * <b>去重位置的修正</b>：去重此前在<i>发布前</i>做，且用的是跨实例共享的 Redis 键。
 * 那意味着主路径抢到键之后，Outbox 补偿路径会被判定为重复而整个丢弃——
 * 补偿路径从未补偿过任何东西。
 *
 * 现在的规则是：<b>去重只决定"本实例是否投递给本地连接"，不决定"是否发布到流"</b>。
 * 补偿路径即使在本实例被去重挡下，也照常写入流，让那些当初没收到的实例有机会
 * 收到它。这正是补偿存在的意义。
 *
 * <b>循环依赖说明</b>：此 Service 持有 {@link RealtimeWebSocketHandler}（本地投递），
 * Handler 反过来通过 {@code @Lazy} 字段注入此 Service（信令转发用）。
 */
@Service
public class RealtimeBroadcastService {
    private static final Logger log = LoggerFactory.getLogger(RealtimeBroadcastService.class);

    private final RealtimeWebSocketHandler localHandler;
    private final RealtimeEventStream eventStream;
    private final EventDeduplicator deduplicator;

    public RealtimeBroadcastService(
            RealtimeWebSocketHandler localHandler,
            RealtimeEventStream eventStream,
            EventDeduplicator deduplicator
    ) {
        this.localHandler = localHandler;
        this.eventStream = eventStream;
        this.deduplicator = deduplicator;
    }

    /** 向某用户的所有在线设备广播（含其他实例） */
    public void broadcastToUser(String userId, String event, Object payload) {
        localHandler.emitToUser(userId, event, payload);
        publishQuietly("USER", Map.of("userId", userId), event, payload, null);
    }

    /** 向某用户的指定设备广播（含其他实例） */
    public void broadcastToDevice(String userId, String deviceId, String event, Object payload) {
        localHandler.emitToDevice(userId, deviceId, event, payload);
        publishQuietly("DEVICE", Map.of("userId", userId, "deviceId", deviceId),
                event, payload, null);
    }

    /** 向某会话内所有在线成员广播（含其他实例） */
    public void broadcastToConversation(String conversationId, String event, Object payload) {
        localHandler.emitToConversation(conversationId, event, payload);
        publishQuietly("CONVERSATION", Map.of("conversationId", conversationId),
                event, payload, null);
    }

    /**
     * 向会话广播一个可去重的事件——<b>主路径</b>使用。
     *
     * 流写入失败不抛出：调用方此时已经把消息落库并即将向客户端返回 ACK，
     * 因为一次扇出失败就告诉用户"发送失败"是错的——消息确实已经存在了。
     * 漏掉的投递由 {@link com.openim.server.worker.OutboxPublisherWorker} 补偿，
     * 那才是它的职责。
     */
    public void broadcastToConversation(String conversationId, RealtimeEvent event) {
        deliverLocally(conversationId, event);
        publishQuietly("CONVERSATION", Map.of("conversationId", conversationId),
                event.name(), event.payload(), event.dedupKey());
    }

    /**
     * 向会话广播一个可去重的事件——<b>补偿路径</b>使用。
     *
     * 与主路径的唯一区别是<b>流写入失败会抛出</b>。Outbox Worker 必须据此把记录
     * 标记为失败并重试；若在这里静默失败，Worker 会把一条从未发出的事件标记为
     * 已发布，那正是本次改造要消除的缺陷。
     *
     * @throws RealtimeEventStream.RealtimePublishException 写入流失败
     */
    public void republishToConversation(String conversationId, RealtimeEvent event) {
        deliverLocally(conversationId, event);
        // 即使本地被去重挡下也要发布：当初没收到这个事件的实例正等着它。
        eventStream.publish("CONVERSATION", Map.of("conversationId", conversationId),
                event.name(), event.payload(), event.dedupKey());
    }

    /**
     * 投递给本实例的本地连接，按去重标识跳过已投递过的。
     *
     * 去重是<b>本实例本地</b>的判断，不影响其他实例——它们各自持有自己的去重表，
     * 各自决定是否投递。
     */
    private void deliverLocally(String conversationId, RealtimeEvent event) {
        if (!deduplicator.shouldDeliver(event.dedupKey())) {
            log.debug("Skipping locally duplicated realtime event key={}", event.dedupKey());
            return;
        }
        localHandler.emitToConversation(conversationId, event.name(), event.payload());
    }

    /**
     * 发布到事件流，失败时静默降级为仅本地投递。
     *
     * 不抛出：主路径的业务操作已经成功，不应因一次扇出失败而回滚或报错；
     * 漏掉的跨实例投递由客户端按 {@code seq} 补拉或 Outbox 补偿路径兜底。
     * 补偿路径（{@link #republishToConversation}）不走此方法——它必须抛出
     * 才能驱动 Outbox Worker 把记录标记为失败并重试。
     */
    private void publishQuietly(
            String targetType,
            Map<String, String> target,
            String event,
            Object payload,
            String dedupKey
    ) {
        try {
            eventStream.publish(targetType, target, event, payload, dedupKey);
        } catch (RuntimeException exception) {
            // 降级为仅本地投递。跨实例的用户这一次收不到，但：
            //   * 消息类事件由客户端按 seq 补拉兜底
            //   * 写过 outbox 的事件由补偿 Worker 重投
            // 不抛出：主路径的业务操作已经成功，不应因扇出失败而回滚或报错。
            log.warn("Failed to publish realtime event to stream event={} reason={}",
                    event, exception.getClass().getSimpleName());
        }
    }
}
