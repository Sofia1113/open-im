package com.openim.server.domain.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 事务 Outbox
 *
 * 领域变更与待发布事件写入同一个事务，因此"数据已改但事件没发出"这种不一致不会出现。
 * Worker 轮询未发布事件并推送，消费端按 eventId 幂等处理，投递语义是至少一次。
 */
@Repository
public class OutboxRepository {
    private final JdbcClient jdbcClient;

    public OutboxRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 在当前业务事务内追加一条待发布事件。
     *
     * 调用方必须已经处于事务中，否则事件会先于业务数据提交，失去 Outbox 的意义。
     */
    public UUID append(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO outbox_events
                            (id, aggregate_type, aggregate_id, event_type, payload)
                        VALUES
                            (:id, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))
                        """)
                .param("id", id)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", Mappers.toJson(payload))
                .update();
        return id;
    }

    /**
     * 领取一批待发布事件。
     *
     * {@code SKIP LOCKED} 让多个 Worker 实例并行消费不同事件而不重复处理，
     * 是这个表能水平扩展的前提。
     */
    public List<OutboxEvent> lockPendingBatch(int limit) {
        return jdbcClient.sql("""
                        SELECT id, aggregate_type, aggregate_id, event_type, payload, occurred_at, attempts
                          FROM outbox_events
                         WHERE published_at IS NULL
                           AND next_attempt_at <= now()
                         ORDER BY occurred_at
                         LIMIT :limit
                         FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query(OutboxRepository::mapEvent)
                .list();
    }

    /** 标记事件已发布并计入投递次数，消费端据此不会再拾取 */
    public void markPublished(UUID id) {
        jdbcClient.sql("UPDATE outbox_events SET published_at = now(), attempts = attempts + 1 WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * 记录一次投递失败并按指数退避安排下次尝试。
     *
     * 退避上限 5 分钟，避免持续失败的事件把 Worker 的每轮批次占满。
     */
    public void markFailed(UUID id, String error) {
        jdbcClient.sql("""
                        UPDATE outbox_events
                        SET attempts = attempts + 1,
                            last_error = :error,
                            next_attempt_at = now() + LEAST(power(2, attempts) * interval '1 second',
                                                            interval '5 minutes')
                        WHERE id = :id
                        """)
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 1000)))
                .param("id", id)
                .update();
    }

    /** 未发布事件积压量，作为可观测性指标暴露 */
    public long countPending() {
        return jdbcClient.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
                .query(Long.class)
                .single();
    }

    /**
     * 删除已发布的旧事件。
     *
     * <b>为什么必需</b>：{@code outbox_events} 此前是一张只增不减的表。
     * 每条消息、每次红包状态变化都往里写一行，而没有任何东西删过一行。
     * 索引持续膨胀，{@code countPending()} 这个每次采集指标都要跑的查询
     * 会越来越慢，最终备份体积里绝大部分是已经没有用的历史事件。
     *
     * 只删已发布的：未发布的事件还等着投递，删掉就是永久丢失。
     *
     * @param limit 单次删除上限。分批删除避免一次锁住大量行——
     *              首次启用时表里可能已经积累了几百万行。
     */
    public int deletePublishedBefore(Instant cutoff, int limit) {
        return jdbcClient.sql("""
                        DELETE FROM outbox_events
                         WHERE id IN (
                             SELECT id FROM outbox_events
                              WHERE published_at IS NOT NULL
                                AND published_at < :cutoff
                              LIMIT :limit
                         )
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .update();
    }

    /** 行映射：outbox_events 表到事件模型，payload 为 jsonb 需反序列化 */
    private static OutboxEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxEvent(
                UUID.fromString(rs.getString("id")),
                rs.getString("aggregate_type"),
                UUID.fromString(rs.getString("aggregate_id")),
                rs.getString("event_type"),
                Mappers.json(rs, "payload"),
                Mappers.instant(rs, "occurred_at"),
                rs.getInt("attempts")
        );
    }

    public record OutboxEvent(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Map<String, Object> payload,
            java.time.Instant occurredAt,
            int attempts
    ) {
    }
}
