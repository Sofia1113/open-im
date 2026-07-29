package com.openim.server.domain.repository;

import com.openim.server.domain.DomainModels.Conversation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话数据访问
 *
 * 会话列表通过一次查询同时取回成员列表、未读数和单聊对端信息，避免逐会话回查造成 N+1。
 */
@Repository
public class ConversationRepository {
    /**
     * 会话视图查询。
     *
     * <ul>
     *   <li>{@code member_ids} 用子查询聚合，避免与主查询 JOIN 后产生笛卡尔积</li>
     *   <li>{@code peer} 仅对单聊生效，用于把会话标题替换为对端昵称</li>
     *   <li>未读数由 {@code next_seq - 1 - read_seq} 推导，不单独维护计数字段</li>
     * </ul>
     */
    private static final String SELECT_CONVERSATION = """
            SELECT c.id,
                   c.type,
                   c.title,
                   c.avatar,
                   c.last_message,
                   c.last_active_at,
                   GREATEST(c.next_seq - 1 - cm.read_seq, 0) AS unread_count,
                   peer.display_name AS peer_display_name,
                   peer.avatar       AS peer_avatar,
                   (SELECT array_agg(inner_member.user_id::text ORDER BY inner_member.joined_at)
                      FROM conversation_members inner_member
                     WHERE inner_member.conversation_id = c.id
                       AND inner_member.left_at IS NULL) AS member_ids
              FROM conversations c
              JOIN conversation_members cm
                ON cm.conversation_id = c.id
               AND cm.user_id = :userId
               AND cm.left_at IS NULL
              LEFT JOIN LATERAL (
                   SELECT u.display_name, u.avatar
                     FROM conversation_members peer_member
                     JOIN users u ON u.id = peer_member.user_id
                    WHERE peer_member.conversation_id = c.id
                      AND peer_member.user_id <> :userId
                      AND peer_member.left_at IS NULL
                    ORDER BY peer_member.joined_at
                    LIMIT 1
              ) peer ON c.type = 'direct'
            """;

    private final JdbcClient jdbcClient;

    public ConversationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Conversation> listForUser(UUID userId) {
        return jdbcClient.sql(SELECT_CONVERSATION + " ORDER BY c.last_active_at DESC")
                .param("userId", userId)
                .query(ConversationRepository::mapConversation)
                .list();
    }

    public Optional<Conversation> findForUser(UUID conversationId, UUID userId) {
        return jdbcClient.sql(SELECT_CONVERSATION + " AND c.id = :conversationId")
                .param("userId", userId)
                .param("conversationId", conversationId)
                .query(ConversationRepository::mapConversation)
                .optional();
    }

    /** 会话是否存在（不含成员关系判断），用于区分 404 与 403 */
    public boolean exists(UUID conversationId) {
        return jdbcClient.sql("SELECT 1 FROM conversations WHERE id = :id")
                .param("id", conversationId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** 读取会话元信息，用于校验红包份数上限、单聊/群聊规则等 */
    public Optional<ConversationMeta> findMeta(UUID conversationId) {
        return jdbcClient.sql("""
                        SELECT c.id,
                               c.type,
                               (SELECT count(*)
                                  FROM conversation_members cm
                                 WHERE cm.conversation_id = c.id
                                   AND cm.left_at IS NULL) AS member_count
                          FROM conversations c
                         WHERE c.id = :id
                        """)
                .param("id", conversationId)
                .query((rs, rowNum) -> new ConversationMeta(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("type"),
                        rs.getInt("member_count")))
                .optional();
    }

    public boolean isActiveMember(UUID conversationId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT 1 FROM conversation_members
                        WHERE conversation_id = :conversationId
                          AND user_id = :userId
                          AND left_at IS NULL
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public List<UUID> listActiveMemberIds(UUID conversationId) {
        return jdbcClient.sql("""
                        SELECT user_id FROM conversation_members
                        WHERE conversation_id = :conversationId AND left_at IS NULL
                        ORDER BY joined_at
                        """)
                .param("conversationId", conversationId)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("user_id")))
                .list();
    }

    /**
     * 原子地分配下一个会话序号。
     *
     * {@code UPDATE ... RETURNING} 在单条语句内完成加锁、自增和读取，
     * 相比 SELECT FOR UPDATE 再 UPDATE 少一次往返，且不存在中间态被其他事务读到的窗口。
     * 并发写同一会话时后到的事务会阻塞在行锁上，从而保证 seq 连续无空洞。
     */
    public long allocateNextSeq(UUID conversationId) {
        return jdbcClient.sql("""
                        UPDATE conversations
                        SET next_seq = next_seq + 1
                        WHERE id = :id
                        RETURNING next_seq - 1
                        """)
                .param("id", conversationId)
                .query(Long.class)
                .single();
    }

    /** 更新会话摘要，用于会话列表展示 */
    public void updateSummary(UUID conversationId, String lastMessage, Instant lastActiveAt) {
        jdbcClient.sql("""
                        UPDATE conversations
                        SET last_message = :lastMessage, last_active_at = :lastActiveAt
                        WHERE id = :id
                        """)
                .param("lastMessage", lastMessage)
                .param("lastActiveAt", java.sql.Timestamp.from(lastActiveAt))
                .param("id", conversationId)
                .update();
    }

    /** 推进已读游标，只允许前进不允许回退 */
    public void advanceReadSeq(UUID conversationId, UUID userId, long readSeq) {
        jdbcClient.sql("""
                        UPDATE conversation_members
                        SET read_seq = GREATEST(read_seq, :readSeq)
                        WHERE conversation_id = :conversationId AND user_id = :userId
                        """)
                .param("readSeq", readSeq)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
    }

    private static Conversation mapConversation(ResultSet rs, int rowNum) throws SQLException {
        boolean direct = "direct".equals(rs.getString("type"));
        String peerDisplayName = rs.getString("peer_display_name");
        String peerAvatar = rs.getString("peer_avatar");
        return new Conversation(
                rs.getString("id"),
                rs.getString("type"),
                direct && peerDisplayName != null ? peerDisplayName : rs.getString("title"),
                direct && peerAvatar != null ? peerAvatar : rs.getString("avatar"),
                readTextArray(rs, "member_ids"),
                rs.getString("last_message"),
                Mappers.instant(rs, "last_active_at"),
                rs.getInt("unread_count")
        );
    }

    private static List<String> readTextArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            Object[] values = (Object[]) array.getArray();
            return java.util.Arrays.stream(values)
                    .map(String::valueOf)
                    .toList();
        } finally {
            array.free();
        }
    }

    /** 会话元信息，仅供服务端规则校验使用，不对外暴露 */
    public record ConversationMeta(UUID id, String type, int memberCount) {
    }
}
