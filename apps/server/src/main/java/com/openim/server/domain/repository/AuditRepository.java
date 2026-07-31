package com.openim.server.domain.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
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

    /** 便捷形态：不记录来源 IP 哈希，委托完整形态执行 */
    public void record(
            String actorType,
            UUID actorId,
            String action,
            String targetType,
            UUID targetId,
            String requestId,
            Map<String, Object> metadata
    ) {
        record(actorType, actorId, action, targetType, targetId, requestId, null, metadata);
    }

    /**
     * 完整形态，含来源 IP 哈希。
     *
     * {@code ip_hash} 列自建表起就存在，但在补上密码重置流程之前从未被写入过——
     * 审计记录里没有任何来源信息，排查"这批请求来自哪里"时无从下手。
     * 哈希由 {@code web/ClientIpHasher} 生成，带部署级盐值。
     */
    public void record(
            String actorType,
            UUID actorId,
            String action,
            String targetType,
            UUID targetId,
            String requestId,
            String ipHash,
            Map<String, Object> metadata
    ) {
        jdbcClient.sql("""
                        INSERT INTO audit_logs
                            (id, actor_type, actor_id, action, target_type, target_id,
                             request_id, ip_hash, metadata)
                        VALUES
                            (:id, :actorType, :actorId, :action, :targetType, :targetId,
                             :requestId, :ipHash, CAST(:metadata AS jsonb))
                        """)
                .param("id", UUID.randomUUID())
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("requestId", requestId)
                .param("ipHash", ipHash)
                .param("metadata", Mappers.toJson(metadata == null ? Map.of() : metadata))
                .update();
    }

    /** 用户相关的审计事件，最常见的写入形态 */
    public void recordUserAction(UUID userId, String action, Map<String, Object> metadata) {
        record("USER", userId, action, null, null, null, metadata);
    }

    /**
     * 无法归属到某个用户的审计事件。
     *
     * 典型场景：针对不存在账号的密码重置请求。这类事件<b>必须记录</b>——
     * 大量针对未注册邮箱的重置请求本身就是账号枚举的信号，
     * 而按用户归属的审计恰好会漏掉它们（没有用户可归属）。
     */
    public void recordSystemAction(String action, Map<String, Object> metadata) {
        record("SYSTEM", null, action, null, null, null, metadata);
    }

    /** 带来源哈希的用户事件，用于登录、改密、重置这类需要追溯来源的动作 */
    public void recordUserAction(
            UUID userId,
            String action,
            String ipHash,
            Map<String, Object> metadata
    ) {
        record("USER", userId, action, null, null, null, ipHash, metadata);
    }

    /** 带来源哈希的系统事件，用于无法归属到用户的可疑请求 */
    public void recordSystemAction(String action, String ipHash, Map<String, Object> metadata) {
        record("SYSTEM", null, action, null, null, null, ipHash, metadata);
    }

    /** 统计某个动作的发生次数，供测试与安全巡检使用 */
    public long countByAction(String action) {
        return jdbcClient.sql("SELECT count(*) FROM audit_logs WHERE action = :action")
                .param("action", action)
                .query(Long.class)
                .single();
    }

    /**
     * 按保留期删除旧审计记录。
     *
     * <b>为什么审计也需要保留期</b>：审计表只追加，没有保留期就会无限增长。
     * 但删除审计记录是一个需要谨慎对待的操作——它是合规与事故排查的依据。
     * 因此保留期应当由合规要求决定（默认 180 天），而不是由存储成本决定，
     * 并且这个动作本身会被记录（见 {@code DataRetentionWorker}）。
     *
     * <b>资金相关的审计不在此删除范围内</b>：{@code wallet.*} 与
     * {@code red_packet.*} 开头的动作会被保留，理由是账务记录的追溯期
     * 通常远长于一般操作日志，且它们的数据量远小于消息类事件。
     *
     * @param limit 单次删除上限，分批进行避免长事务锁住大量行
     */
    public int deleteOlderThan(Instant cutoff, int limit) {
        return jdbcClient.sql("""
                        DELETE FROM audit_logs
                         WHERE id IN (
                             SELECT id FROM audit_logs
                              WHERE created_at < :cutoff
                                AND action NOT LIKE 'wallet.%'
                                AND action NOT LIKE 'red_packet.%'
                              LIMIT :limit
                         )
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .update();
    }
}
