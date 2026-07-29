package com.openim.server.domain.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;

/**
 * 审计日志写入
 *
 * 只追加，不提供更新与删除接口。登录、设备变更、账务交易和策略变更必须留痕
 * （见 docs/02-features/SECURITY.md §8）。
 *
 * 注意不要把令牌、消息正文或完整 IP 写进 metadata；IP 只存哈希。
 */
@Repository
public class AuditRepository {
    private final JdbcClient jdbcClient;

    public AuditRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void record(
            String actorType,
            UUID actorId,
            String action,
            String targetType,
            UUID targetId,
            String requestId,
            Map<String, Object> metadata
    ) {
        jdbcClient.sql("""
                        INSERT INTO audit_logs
                            (id, actor_type, actor_id, action, target_type, target_id,
                             request_id, metadata)
                        VALUES
                            (:id, :actorType, :actorId, :action, :targetType, :targetId,
                             :requestId, CAST(:metadata AS jsonb))
                        """)
                .param("id", UUID.randomUUID())
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("requestId", requestId)
                .param("metadata", Mappers.toJson(metadata == null ? Map.of() : metadata))
                .update();
    }

    /** 用户相关的审计事件，最常见的写入形态 */
    public void recordUserAction(UUID userId, String action, Map<String, Object> metadata) {
        record("USER", userId, action, null, null, null, metadata);
    }

    /** 统计某个动作的发生次数，供测试与安全巡检使用 */
    public long countByAction(String action) {
        return jdbcClient.sql("SELECT count(*) FROM audit_logs WHERE action = :action")
                .param("action", action)
                .query(Long.class)
                .single();
    }
}
