package com.openim.server.domain.repository;

import com.openim.server.domain.DomainModels.RedPacket;
import com.openim.server.domain.DomainModels.RedPacketClaim;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 红包数据访问
 *
 * 并发领取的正确性依赖三层保护，缺一不可：
 * <ol>
 *   <li>{@code FOR UPDATE SKIP LOCKED} 抢占未领份额，使并发领取各自拿到不同份额而不是相互阻塞</li>
 *   <li>份额更新带 {@code claimed_by IS NULL} 条件，避免同一份被重复认领</li>
 *   <li>{@code uq_red_packet_claims_packet_user} 唯一约束兜底，即使应用层判断失效数据库也会拒绝</li>
 * </ol>
 */
@Repository
public class RedPacketRepository {
    private static final String SELECT_PACKET = """
            SELECT id, conversation_id, sender_id, message_id, mode, total_amount, total_count,
                   claimed_amount, claimed_count, greeting, status, expires_at
              FROM red_packets
            """;

    private final JdbcClient jdbcClient;

    public RedPacketRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(
            UUID id,
            UUID conversationId,
            UUID senderId,
            UUID messageId,
            String mode,
            long totalAmount,
            int totalCount,
            String greeting,
            Instant expiresAt,
            String idempotencyKey
    ) {
        jdbcClient.sql("""
                        INSERT INTO red_packets
                            (id, conversation_id, sender_id, message_id, mode, total_amount, total_count,
                             greeting, status, expires_at, idempotency_key)
                        VALUES
                            (:id, :conversationId, :senderId, :messageId, :mode, :totalAmount, :totalCount,
                             :greeting, 'published', :expiresAt, :idempotencyKey)
                        """)
                .param("id", id)
                .param("conversationId", conversationId)
                .param("senderId", senderId)
                .param("messageId", messageId)
                .param("mode", mode)
                .param("totalAmount", totalAmount)
                .param("totalCount", totalCount)
                .param("greeting", greeting)
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("idempotencyKey", idempotencyKey)
                .update();
    }

    /** 批量写入预分配份额。创建时一次性分配，使后续每次领取只需一个短事务 */
    public void insertShares(UUID redPacketId, List<Long> amounts) {
        for (int ordinal = 0; ordinal < amounts.size(); ordinal++) {
            jdbcClient.sql("""
                            INSERT INTO red_packet_shares (id, red_packet_id, ordinal, amount)
                            VALUES (:id, :redPacketId, :ordinal, :amount)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("redPacketId", redPacketId)
                    .param("ordinal", ordinal)
                    .param("amount", amounts.get(ordinal))
                    .update();
        }
    }

    public Optional<RedPacket> findById(UUID id) {
        return jdbcClient.sql(SELECT_PACKET + " WHERE id = :id")
                .param("id", id)
                .query(RedPacketRepository::mapRow)
                .optional()
                .map(this::withClaims);
    }

    /** 锁定红包行，用于状态判定与过期退款等需要串行化的路径 */
    public Optional<RedPacketRow> findByIdForUpdate(UUID id) {
        return jdbcClient.sql(SELECT_PACKET + " WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(RedPacketRepository::mapRow)
                .optional();
    }

    public Optional<UUID> findIdBySenderIdempotency(UUID senderId, String idempotencyKey) {
        return jdbcClient.sql("""
                        SELECT id FROM red_packets
                        WHERE sender_id = :senderId AND idempotency_key = :idempotencyKey
                        """)
                .param("senderId", senderId)
                .param("idempotencyKey", idempotencyKey)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .optional();
    }

    public Optional<UUID> findMessageId(UUID redPacketId) {
        return jdbcClient.sql("SELECT message_id FROM red_packets WHERE id = :id")
                .param("id", redPacketId)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("message_id")))
                .optional();
    }

    /**
     * 抢占一个未领份额。
     *
     * {@code SKIP LOCKED} 是这里的关键：多个用户同时领取时，后到的事务会跳过已被锁定的行
     * 直接拿下一个可用份额，而不是排队等待。没有它，N 个并发领取会退化成串行。
     *
     * @return 抢到的份额；红包已领完时返回空
     */
    public Optional<ShareRow> claimNextShare(UUID redPacketId, UUID userId, Instant claimedAt) {
        Optional<ShareRow> share = jdbcClient.sql("""
                        SELECT id, amount FROM red_packet_shares
                        WHERE red_packet_id = :redPacketId AND claimed_by IS NULL
                        ORDER BY ordinal
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("redPacketId", redPacketId)
                .query((rs, rowNum) -> new ShareRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getLong("amount")))
                .optional();
        if (share.isEmpty()) {
            return Optional.empty();
        }
        int updated = jdbcClient.sql("""
                        UPDATE red_packet_shares
                        SET claimed_by = :userId, claimed_at = :claimedAt
                        WHERE id = :id AND claimed_by IS NULL
                        """)
                .param("userId", userId)
                .param("claimedAt", Timestamp.from(claimedAt))
                .param("id", share.get().id())
                .update();
        // 行已被锁定，理论上不会失败；真出现说明存在绕过锁的写入路径，宁可让事务失败也不要静默发钱
        if (updated != 1) {
            throw new IllegalStateException("Red packet share was claimed concurrently: " + share.get().id());
        }
        return share;
    }

    public void insertClaim(
            UUID id,
            UUID redPacketId,
            UUID shareId,
            UUID userId,
            long amount,
            String idempotencyKey,
            Instant claimedAt
    ) {
        jdbcClient.sql("""
                        INSERT INTO red_packet_claims
                            (id, red_packet_id, share_id, user_id, amount, idempotency_key, claimed_at)
                        VALUES
                            (:id, :redPacketId, :shareId, :userId, :amount, :idempotencyKey, :claimedAt)
                        """)
                .param("id", id)
                .param("redPacketId", redPacketId)
                .param("shareId", shareId)
                .param("userId", userId)
                .param("amount", amount)
                .param("idempotencyKey", idempotencyKey)
                .param("claimedAt", Timestamp.from(claimedAt))
                .update();
    }

    public Optional<RedPacketClaim> findClaim(UUID redPacketId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT id, user_id, amount, claimed_at FROM red_packet_claims
                        WHERE red_packet_id = :redPacketId AND user_id = :userId
                        """)
                .param("redPacketId", redPacketId)
                .param("userId", userId)
                .query(RedPacketRepository::mapClaim)
                .optional();
    }

    /**
     * 累加领取计数并推进状态。
     *
     * 计数在数据库内自增而不是读出来加一再写回，避免并发领取相互覆盖。
     */
    public String applyClaim(UUID redPacketId, long amount) {
        return jdbcClient.sql("""
                        UPDATE red_packets
                        SET claimed_amount = claimed_amount + :amount,
                            claimed_count = claimed_count + 1,
                            status = CASE
                                WHEN claimed_count + 1 >= total_count THEN 'exhausted'
                                ELSE 'partially_claimed'
                            END
                        WHERE id = :id
                        RETURNING status
                        """)
                .param("amount", amount)
                .param("id", redPacketId)
                .query(String.class)
                .single();
    }

    public void updateStatus(UUID redPacketId, String status) {
        jdbcClient.sql("UPDATE red_packets SET status = :status WHERE id = :id")
                .param("status", status)
                .param("id", redPacketId)
                .update();
    }

    /**
     * 扫描到期未退款的红包 ID，供过期退款 Worker 使用。
     *
     * 这里刻意不加锁：Worker 会为每个红包开独立事务并在其中 {@code FOR UPDATE}，
     * 在扫描阶段持锁既没有意义（事务一结束就释放），也会拉长锁持有时间。
     * 并发 Worker 处理到同一个红包时，由 {@link #findByIdForUpdate} 的行锁和状态判定去重。
     */
    public List<UUID> findExpiredIds(int limit) {
        return jdbcClient.sql("""
                        SELECT id FROM red_packets
                        WHERE status IN ('published', 'partially_claimed')
                          AND expires_at <= now()
                        ORDER BY expires_at
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .list();
    }

    private RedPacket withClaims(RedPacketRow row) {
        List<RedPacketClaim> claims = jdbcClient.sql("""
                        SELECT id, user_id, amount, claimed_at FROM red_packet_claims
                        WHERE red_packet_id = :redPacketId
                        ORDER BY claimed_at
                        """)
                .param("redPacketId", row.id())
                .query(RedPacketRepository::mapClaim)
                .list();
        return row.toView(claims);
    }

    private static RedPacketRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RedPacketRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("conversation_id")),
                UUID.fromString(rs.getString("sender_id")),
                UUID.fromString(rs.getString("message_id")),
                rs.getString("mode"),
                rs.getLong("total_amount"),
                rs.getInt("total_count"),
                rs.getLong("claimed_amount"),
                rs.getInt("claimed_count"),
                rs.getString("greeting"),
                rs.getString("status"),
                Mappers.instant(rs, "expires_at")
        );
    }

    private static RedPacketClaim mapClaim(ResultSet rs, int rowNum) throws SQLException {
        return new RedPacketClaim(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getLong("amount"),
                Mappers.instant(rs, "claimed_at")
        );
    }

    /** 红包行，携带对外视图不暴露的 messageId 等内部字段 */
    public record RedPacketRow(
            UUID id,
            UUID conversationId,
            UUID senderId,
            UUID messageId,
            String mode,
            long totalAmount,
            int totalCount,
            long claimedAmount,
            int claimedCount,
            String greeting,
            String status,
            Instant expiresAt
    ) {
        public RedPacket toView(List<RedPacketClaim> claims) {
            return new RedPacket(
                    id.toString(),
                    conversationId.toString(),
                    senderId.toString(),
                    mode,
                    totalAmount,
                    totalCount,
                    claimedAmount,
                    claimedCount,
                    greeting,
                    status,
                    expiresAt,
                    claims
            );
        }

        /** 未领取金额，过期时退还给发送者 */
        public long remainingAmount() {
            return totalAmount - claimedAmount;
        }
    }

    public record ShareRow(UUID id, long amount) {
    }
}
