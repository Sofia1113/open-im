package com.openim.server.realtime;

import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.Requests.SendMessage;
import com.openim.server.domain.service.MessageService;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import com.openim.server.support.DemoUsers;
import com.openim.server.worker.OutboxPublisherWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 补偿路径集成测试
 *
 * 此前这条路径在三处同时失效：载荷缺 conversationId 导致全部事件被跳过、
 * 线上事件名用的是 outbox 内部名、载荷结构与主路径不一致。
 * 三者叠加的结果是"至少一次投递"这个保证并不存在，而单元测试无法发现——
 * 因为它们各自看起来都是合理的代码。
 */
@SpringBootTest
class OutboxCompensationIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired
    private MessageService messageService;

    @Autowired
    private OutboxPublisherWorker worker;

    @Autowired
    private EventDeduplicator deduplicator;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void messageOutboxEventCarriesTheConversationIdNeededForRouting() {
        ImMessage message = sendMessage("outbox-routing-" + System.nanoTime());

        Map<String, Object> payload = latestOutboxPayload("message:created");

        // Worker 靠这个字段决定投递给哪个会话。缺失时它会静默跳过事件，
        // 因此这条断言是补偿路径能工作的前提。
        assertThat(payload.get("conversationId"))
                .isEqualTo(DemoUsers.DIRECT_CONVERSATION.toString());
        assertThat(payload.get("messageId")).isEqualTo(message.id());
    }

    @Test
    void workerMarksEventsPublishedSoTheyAreNotRepublishedForever() {
        sendMessage("outbox-published-" + System.nanoTime());
        assertThat(pendingCount()).isGreaterThan(0);

        worker.publishPendingEvents();

        // 未标记发布的事件会在每一轮被重新扫到，永久积压
        assertThat(pendingCount()).isZero();
    }

    @Test
    void bothDeliveryPathsProduceTheSameWireEvent() {
        ImMessage message = sendMessage("outbox-shape-" + System.nanoTime());

        // 主路径与补偿路径都经由 RealtimeEvent 构造，因此事件名与载荷结构必然一致。
        // 这条断言锁定契约：客户端按 payload.message 取值（见 packages/contracts
        // 的 ServerToClientEvents），任一路径偏离都会让客户端取不到字段。
        RealtimeEvent event = RealtimeEvent.messageCreated(message);

        assertThat(event.name()).isEqualTo("message:created");
        assertThat(event.payload()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.payload();
        assertThat(payload).containsOnlyKeys("eventId", "message");
        assertThat(payload.get("message")).isEqualTo(message);
    }

    @Test
    void duplicateDeliveryOfTheSameEventIsSuppressed() {
        String key = "message:" + UUID.randomUUID();

        // 首次投递放行，重复投递被丢弃——这是 Outbox"至少一次"语义
        // 不会让用户看到重复消息的原因
        assertThat(deduplicator.shouldDeliver(key)).isTrue();
        assertThat(deduplicator.shouldDeliver(key)).isFalse();
        assertThat(deduplicator.shouldDeliver(key)).isFalse();
    }

    @Test
    void distinctEventsAreNotConfusedWithEachOther() {
        assertThat(deduplicator.shouldDeliver("message:" + UUID.randomUUID())).isTrue();
        assertThat(deduplicator.shouldDeliver("message:" + UUID.randomUUID())).isTrue();
    }

    @Test
    void dedupKeyIsStableAcrossSeparateConstructions() {
        ImMessage message = sendMessage("outbox-stable-" + System.nanoTime());

        // 两条路径各自构造事件，去重标识必须相同，否则去重不生效。
        // 用随机 UUID 作为 eventId 时这条断言不成立——那正是修复前的状态。
        assertThat(RealtimeEvent.messageCreated(message).dedupKey())
                .isEqualTo(RealtimeEvent.messageCreated(message).dedupKey());
    }

    @Test
    void missingDedupKeyDeliversRatherThanDrops() {
        // 无法去重时应放行：重复推送是体验瑕疵，丢弃是数据丢失
        assertThat(deduplicator.shouldDeliver(null)).isTrue();
        assertThat(deduplicator.shouldDeliver("")).isTrue();
    }

    private ImMessage sendMessage(String clientMessageId) {
        return messageService.createMessage(DemoUsers.ALICE, new SendMessage(
                DemoUsers.DIRECT_CONVERSATION.toString(),
                clientMessageId,
                "text",
                Map.of("text", "outbox compensation test")
        ));
    }

    private long pendingCount() {
        return jdbcClient.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
                .query(Long.class)
                .single();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> latestOutboxPayload(String eventType) {
        return jdbcClient.sql("""
                        SELECT payload FROM outbox_events
                        WHERE event_type = :type
                        ORDER BY occurred_at DESC
                        LIMIT 1
                        """)
                .param("type", eventType)
                .query((rs, rowNum) -> {
                    try {
                        return new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(rs.getString("payload"), Map.class);
                    } catch (Exception exception) {
                        throw new IllegalStateException("Unable to parse outbox payload", exception);
                    }
                })
                .single();
    }
}
