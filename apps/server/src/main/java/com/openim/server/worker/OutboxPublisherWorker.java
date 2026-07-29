package com.openim.server.worker;

import com.openim.server.domain.repository.OutboxRepository;
import com.openim.server.domain.repository.OutboxRepository.OutboxEvent;
import com.openim.server.realtime.RealtimeBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Outbox 事件发布 Worker
 *
 * 负责消费 {@code outbox_events} 表中的待发布事件，通过 Redis Pub/Sub 广播到所有实例。
 *
 * 这是"可靠实时投递"的补偿路径：
 * <ul>
 *   <li>主路径（{@link RealtimeBroadcastService}）在请求处理期间同步推送，延迟最低</li>
 *   <li>本 Worker 作为补偿：如果进程在事务提交后、主路径推送前崩溃，
 *       下次启动时 Worker 会重新发布漏掉的事件</li>
 * </ul>
 *
 * 消费者（{@link com.openim.server.realtime.RealtimeEventSubscriber}）按事件 ID 幂等处理，
 * 因此同一事件被发布两次不会造成重复推送到客户端（客户端也有幂等键）。
 */
@Component
public class OutboxPublisherWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherWorker.class);
    private static final int BATCH_SIZE = 100;

    /** Outbox 转发到 Redis 的事件频道前缀，订阅方按事件类型过滤 */
    static final String OUTBOX_CHANNEL_PREFIX = "openim:outbox:";

    private final OutboxRepository outboxRepository;
    private final RealtimeBroadcastService broadcastService;

    public OutboxPublisherWorker(
            OutboxRepository outboxRepository,
            RealtimeBroadcastService broadcastService
    ) {
        this.outboxRepository = outboxRepository;
        this.broadcastService = broadcastService;
    }

    /**
     * 扫描并发布未投递的 Outbox 事件。
     *
     * 按事件类型路由到不同的广播方法；未识别的事件类型跳过（不失败）。
     * 单个事件失败不中断本轮，记录错误后继续。
     */
    @Scheduled(fixedDelayString = "${openim.worker.outbox-publish-interval:5000}")
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.lockPendingBatch(BATCH_SIZE);
        if (events.isEmpty()) return;

        int published = 0;
        for (OutboxEvent event : events) {
            try {
                routeEvent(event);
                outboxRepository.markPublished(event.id());
                published++;
            } catch (RuntimeException exception) {
                outboxRepository.markFailed(event.id(), exception.getMessage());
                log.error("Failed to publish outbox event id={} type={}",
                        event.id(), event.eventType(), exception);
            }
        }
        if (published > 0) {
            log.debug("Published {} outbox events", published);
        }
    }

    private void routeEvent(OutboxEvent event) {
        String payload = String.valueOf(event.payload());
        switch (event.eventType()) {
            case "message:created" -> {
                String conversationId = String.valueOf(
                        event.payload().getOrDefault("conversationId", ""));
                if (!conversationId.isBlank()) {
                    broadcastService.broadcastToConversation(conversationId, event.eventType(),
                            Map.of("eventId", event.id().toString(), "outboxId", event.id().toString(),
                                   "payload", event.payload()));
                }
            }
            case "red_packet:created", "red_packet:claimed", "red_packet:refunded" -> {
                String conversationId = String.valueOf(
                        event.payload().getOrDefault("conversationId", ""));
                if (!conversationId.isBlank()) {
                    broadcastService.broadcastToConversation(conversationId, event.eventType(),
                            event.payload());
                }
            }
            default ->
                // 其他事件类型（通话状态等）暂不处理，留待扩展
                log.debug("Skipping unknown outbox event type={}", event.eventType());
        }
    }
}
