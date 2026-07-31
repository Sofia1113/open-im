package com.openim.server.realtime;

import com.openim.server.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实时事件流集成测试
 *
 * 这些用例针对的是 Pub/Sub <b>做不到</b>而 Streams 能做到的事。
 * 改造前的链路在这里的每一条断言上都会失败——不是因为实现有 bug，
 * 而是因为 Pub/Sub 本身不提供这些保证：
 * <ul>
 *   <li>没有持久化：消费者不在线时发布的消息直接消失</li>
 *   <li>没有确认：消息发出即"完成"，处理失败无从得知</li>
 *   <li>没有重投：丢了就是丢了</li>
 * </ul>
 *
 * 用独立的消费组名，与应用自身的消费者（组名为 instanceId）互不干扰——
 * 消费组各自持有独立位点，这也正是"每实例一个消费组"能实现广播的原因。
 */
@SpringBootTest
class RealtimeEventStreamIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private RealtimeEventStream eventStream;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String group;
    private String consumer;

    @BeforeEach
    void createIsolatedGroup() {
        group = "test-group-" + UUID.randomUUID();
        consumer = "test-consumer";
        // 从流末尾开始：只关心本用例之后发布的事件
        redisTemplate.opsForStream()
                .createGroup(RealtimeEventStream.STREAM_KEY, ReadOffset.latest(), group);
    }

    /**
     * 改造的核心价值：消费者离线期间发布的事件不会丢失。
     *
     * Pub/Sub 下这个场景必然丢消息——发布时没有活跃订阅者，消息就消失了，
     * 而这恰恰是滚动更新、Redis 抖动、网络分区期间的常态。
     */
    @Test
    void eventsPublishedWhileConsumerIsOfflineAreStillDeliveredLater() {
        String conversationId = UUID.randomUUID().toString();
        String dedupKey = "message:" + UUID.randomUUID();

        // 此刻没有任何 XREADGROUP 在这个组上运行——消费者"离线"
        eventStream.publish(
                "CONVERSATION",
                Map.of("conversationId", conversationId),
                "message:created",
                Map.of("eventId", dedupKey, "message", Map.of("id", "m-1")),
                dedupKey);

        // 消费者稍后上线，仍然读得到那条事件
        List<MapRecord<String, Object, Object>> records = readNew();

        assertThat(records).isNotEmpty();
        assertThat(payloadOf(records.get(0))).contains(conversationId).contains(dedupKey);
    }

    /**
     * 未确认的记录留在 pending 列表中等待重投。
     *
     * 这是 at-least-once 的落点：确认发生在<b>处理之后</b>。
     * 处理失败或进程崩溃时记录不会被确认，因此不会丢失。
     */
    @Test
    void unacknowledgedRecordsStayPendingUntilAcknowledged() {
        publishOne();

        List<MapRecord<String, Object, Object>> records = readNew();
        assertThat(records).hasSize(1);
        RecordId id = records.get(0).getId();

        // 读取了但未确认——模拟"投递抛异常"或"ACK 前进程崩溃"
        PendingMessagesSummary beforeAck = redisTemplate.opsForStream()
                .pending(RealtimeEventStream.STREAM_KEY, group);
        assertThat(beforeAck).isNotNull();
        assertThat(beforeAck.getTotalPendingMessages()).isEqualTo(1);

        // 确认之后才从 pending 移除
        redisTemplate.opsForStream()
                .acknowledge(RealtimeEventStream.STREAM_KEY, group, id);

        PendingMessagesSummary afterAck = redisTemplate.opsForStream()
                .pending(RealtimeEventStream.STREAM_KEY, group);
        assertThat(afterAck).isNotNull();
        assertThat(afterAck.getTotalPendingMessages()).isZero();
    }

    /**
     * 未确认的记录可以被重新读出来投递。
     *
     * {@code RealtimeEventStreamConsumer.reclaimPendingMessages} 依赖这个能力：
     * 闲置超时的 pending 记录按 ID 重新取回并再次处理。
     */
    @Test
    void pendingRecordsCanBeReReadForRedelivery() {
        publishOne();
        List<MapRecord<String, Object, Object>> first = readNew();
        assertThat(first).hasSize(1);
        String id = first.get(0).getId().getValue();

        // 不确认，直接按 ID 重新取回——这是重投路径读取记录内容的方式
        List<MapRecord<String, Object, Object>> reread = redisTemplate.opsForStream()
                .range(RealtimeEventStream.STREAM_KEY,
                        org.springframework.data.domain.Range.closed(id, id));

        assertThat(reread).hasSize(1);
        assertThat(reread.get(0).getId().getValue()).isEqualTo(id);
        assertThat(payloadOf(reread.get(0))).isEqualTo(payloadOf(first.get(0)));
    }

    /**
     * 每个消费组持有独立位点，因此同一条事件会被<b>每一个</b>组读到。
     *
     * 这是"每实例一个消费组"实现广播的机制：N 个实例 = N 个组 = 每条事件被读 N 次，
     * 每个实例投递给自己本地的连接。若所有实例共用一个组，就变成竞争消费——
     * 一条消息只有一个实例能收到，其余实例上的用户全部收不到。
     */
    @Test
    void eachConsumerGroupReceivesEveryEventIndependently() {
        String secondGroup = "test-group-" + UUID.randomUUID();
        redisTemplate.opsForStream()
                .createGroup(RealtimeEventStream.STREAM_KEY, ReadOffset.latest(), secondGroup);

        publishOne();

        List<MapRecord<String, Object, Object>> firstGroupRecords = readNew();
        List<MapRecord<String, Object, Object>> secondGroupRecords = redisTemplate.opsForStream()
                .read(Consumer.from(secondGroup, consumer),
                        StreamReadOptions.empty().count(10),
                        StreamOffset.create(RealtimeEventStream.STREAM_KEY,
                                ReadOffset.lastConsumed()));

        // 同一条事件，两个组各读到一次
        assertThat(firstGroupRecords).hasSize(1);
        assertThat(secondGroupRecords).hasSize(1);
        assertThat(secondGroupRecords.get(0).getId())
                .isEqualTo(firstGroupRecords.get(0).getId());
    }

    /** 发布携带的 originInstanceId 让消费端能跳过自己发出的事件，避免本地重复投递 */
    @Test
    void publishedEventCarriesOriginInstanceIdForSelfSkip() {
        publishOne();

        List<MapRecord<String, Object, Object>> records = readNew();

        assertThat(records).hasSize(1);
        assertThat(payloadOf(records.get(0))).contains("originInstanceId");
    }

    private void publishOne() {
        eventStream.publish(
                "CONVERSATION",
                Map.of("conversationId", UUID.randomUUID().toString()),
                "message:created",
                Map.of("eventId", "e-1"),
                "message:" + UUID.randomUUID());
    }

    private List<MapRecord<String, Object, Object>> readNew() {
        return redisTemplate.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(RealtimeEventStream.STREAM_KEY, ReadOffset.lastConsumed()));
    }

    private static String payloadOf(MapRecord<String, Object, Object> record) {
        return String.valueOf(record.getValue().get(RealtimeEventStream.FIELD_DATA));
    }
}
