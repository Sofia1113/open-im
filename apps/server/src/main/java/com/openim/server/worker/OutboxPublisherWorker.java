package com.openim.server.worker;

import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.DomainModels.RedPacket;
import com.openim.server.domain.repository.MessageRepository;
import com.openim.server.domain.repository.OutboxRepository;
import com.openim.server.domain.repository.OutboxRepository.OutboxEvent;
import com.openim.server.domain.repository.RedPacketRepository;
import com.openim.server.realtime.RealtimeBroadcastService;
import com.openim.server.realtime.RealtimeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbox 事件发布 Worker
 *
 * 消费 {@code outbox_events} 表中的待发布事件，通过 Redis Pub/Sub 广播到所有实例。
 *
 * 这是"可靠实时投递"的补偿路径：
 * <ul>
 *   <li>主路径（Controller / WebSocket handler 内的同步推送）延迟最低</li>
 *   <li>本 Worker 作为补偿：主路径写流失败、或进程在事务提交后崩溃时，
 *       下一轮扫描会把漏掉的事件重新发出</li>
 * </ul>
 *
 * 两条路径通过 {@link RealtimeEvent} 的静态工厂构造事件，因此线上事件名与载荷
 * 结构完全一致；重复投递由各实例本地的
 * {@link com.openim.server.realtime.EventDeduplicator} 按稳定业务标识丢弃。
 *
 * <b>为什么 markPublished 现在才是诚实的</b>：投递通道原先是 Redis Pub/Sub，
 * {@code convertAndSend} 返回只代表"已发给当前订阅者"，不代表任何人收到——
 * 据此标记已发布，等于把 at-most-once 当成了 at-least-once。改用 Redis Streams 后，
 * {@code XADD} 成功意味着事件<b>已经落到 Redis 的数据结构里</b>，消费者按自己的
 * 位点读取、处理完才 {@code XACK}，未确认的记录会被重投。此时"写入成功即标记发布"
 * 才成立。
 *
 * <b>此前的实现有三个缺陷</b>，已一并修正：
 * <ul>
 *   <li>路由依据 {@code payload.conversationId}，而写入方从未提供该字段，
 *       因此所有消息类事件都走到"会话 ID 为空"的分支被静默跳过——
 *       补偿路径从未真正投递过任何事件</li>
 *   <li>把 outbox 内部事件名（{@code red_packet:claimed}）当作线上事件名发布，
 *       而客户端监听的是 {@code red-packet:updated}，两者永不匹配</li>
 *   <li>载荷结构与主路径不一致（{@code {eventId, outboxId, payload}} 对
 *       {@code {eventId, message}}），客户端按契约取字段时取不到</li>
 * </ul>
 */
@Component
public class OutboxPublisherWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherWorker.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepository;
    private final MessageRepository messageRepository;
    private final RedPacketRepository redPacketRepository;
    private final RealtimeBroadcastService broadcastService;

    public OutboxPublisherWorker(
            OutboxRepository outboxRepository,
            MessageRepository messageRepository,
            RedPacketRepository redPacketRepository,
            RealtimeBroadcastService broadcastService
    ) {
        this.outboxRepository = outboxRepository;
        this.messageRepository = messageRepository;
        this.redPacketRepository = redPacketRepository;
        this.broadcastService = broadcastService;
    }

    /**
     * 扫描并发布未投递的 Outbox 事件。
     *
     * 整轮在一个事务内：{@code lockPendingBatch} 用 {@code FOR UPDATE SKIP LOCKED}
     * 持有行锁，锁必须保持到 {@code markPublished} 提交为止，否则多个实例的
     * Worker 会同时选中同一批事件。此前没有事务注解，行锁在查询语句结束时
     * 就被释放了。
     *
     * 单个事件失败不中断本轮，记录错误后继续；失败的事件按指数退避重试。
     */
    @Scheduled(fixedDelayString = "${openim.worker.outbox-publish-interval:5000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.lockPendingBatch(BATCH_SIZE);
        if (events.isEmpty()) return;

        int published = 0;
        int skipped = 0;
        for (OutboxEvent event : events) {
            try {
                if (routeEvent(event)) {
                    published++;
                } else {
                    skipped++;
                }
                // 无论是否成功路由都标记为已发布：无法路由的事件重试一万次
                // 结果也一样，留在表里只会让积压指标失去意义
                outboxRepository.markPublished(event.id());
            } catch (RuntimeException exception) {
                outboxRepository.markFailed(event.id(), exception.getMessage());
                log.error("Failed to publish outbox event id={} type={}",
                        event.id(), event.eventType(), exception);
            }
        }
        if (published > 0 || skipped > 0) {
            log.debug("Outbox published={} skipped={}", published, skipped);
        }
    }

    /**
     * 把 outbox 记录转换为线上事件并投递。
     *
     * 从数据库重新读取聚合而不是使用载荷快照：补偿投递发生在事件产生之后，
     * 客户端应当收到<b>当前</b>状态。红包尤其明显——一个已被领完的红包，
     * 补偿推送时应该显示"已领完"，而不是它刚被领第一份时的样子。
     *
     * @return 是否成功投递；false 表示聚合已不存在或载荷缺少必要字段
     */
    private boolean routeEvent(OutboxEvent event) {
        String conversationId = text(event, "conversationId");
        if (conversationId.isBlank()) {
            log.warn("Outbox event lacks conversationId id={} type={}",
                    event.id(), event.eventType());
            return false;
        }

        return switch (event.eventType()) {
            case "message:created" -> republishMessage(event, conversationId);
            case "red_packet:created", "red_packet:claimed", "red_packet:refunded" ->
                    republishRedPacket(event, conversationId);
            default -> {
                // 通话状态等事件目前只走主路径，没有补偿需求
                log.debug("No realtime projection for outbox event type={}", event.eventType());
                yield false;
            }
        };
    }

    /**
     * 重新发布消息创建事件。
     *
     * 从数据库重新读取消息而不是使用载荷快照，确保补偿投递的是<b>当前</b>状态；
     * 写流失败会抛出异常，由外层 {@code markFailed} 记录失败并等待指数退避重试。
     *
     * @return 是否成功投递；消息不存在或缺少 messageId 时返回 false
     */
    private boolean republishMessage(OutboxEvent event, String conversationId) {
        UUID messageId = uuid(event, "messageId");
        if (messageId == null) {
            log.warn("Outbox message event lacks messageId id={}", event.id());
            return false;
        }
        Optional<ImMessage> message = messageRepository.findById(messageId);
        if (message.isEmpty()) {
            log.warn("Outbox references a missing message messageId={}", messageId);
            return false;
        }
        // republish 而非 broadcast：写流失败必须抛出，由外层 markFailed 重试
        broadcastService.republishToConversation(
                conversationId, RealtimeEvent.messageCreated(message.get()));
        return true;
    }

    /**
     * 重新发布红包事件。
     *
     * 与 {@link #republishMessage} 同理，从数据库重新读取红包的当前状态——
     * 已被领完的红包在补偿推送时应显示"已领完"而非过期快照。
     *
     * @return 是否成功投递；红包不存在或缺少 redPacketId 时返回 false
     */
    private boolean republishRedPacket(OutboxEvent event, String conversationId) {
        UUID redPacketId = uuid(event, "redPacketId");
        if (redPacketId == null) {
            log.warn("Outbox red packet event lacks redPacketId id={}", event.id());
            return false;
        }
        Optional<RedPacket> redPacket = redPacketRepository.findById(redPacketId);
        if (redPacket.isEmpty()) {
            log.warn("Outbox references a missing red packet redPacketId={}", redPacketId);
            return false;
        }
        broadcastService.republishToConversation(
                conversationId, RealtimeEvent.redPacketUpdated(redPacket.get()));
        return true;
    }

    /** 从事件载荷中读取字符串字段，缺失时返回空串 */
    private static String text(OutboxEvent event, String field) {
        Object value = event.payload().get(field);
        return value == null ? "" : String.valueOf(value);
    }

    /** 从事件载荷中读取 UUID 字段，缺失或格式非法时返回 null */
    private static UUID uuid(OutboxEvent event, String field) {
        String raw = text(event, field);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
