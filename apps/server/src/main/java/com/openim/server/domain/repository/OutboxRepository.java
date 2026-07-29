package com.openim.server.domain.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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
