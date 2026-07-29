package com.openim.server.domain.repository;

import com.openim.server.domain.DomainModels.ImMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 消息数据访问
 *
 * jsonb 参数统一使用 {@code CAST(:param AS jsonb)} 而非 {@code :param::jsonb}，
 * 避免命名参数解析器与 PostgreSQL 转换语法的歧义。
 */
@Repository
public class MessageRepository {
    private static final String SELECT_MESSAGE = """
            SELECT id, conversation_id, seq, sender_id, client_message_id, type, content, sent_at
              FROM messages
            """;

    private final JdbcClient jdbcClient;

    public MessageRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ImMessage insert(
            UUID id,
            UUID conversationId,
            long seq,
            UUID senderId,
            String clientMessageId,
            String type,
            Map<String, Object> content,
            Instant sentAt
    ) {
        jdbcClient.sql("""
                        INSERT INTO messages
                            (id, conversation_id, seq, sender_id, client_message_id, type, content, sent_at)
                        VALUES
                            (:id, :conversationId, :seq, :senderId, :clientMessageId, :type,
                             CAST(:content AS jsonb), :sentAt)
                        """)
                .param("id", id)
                .param("conversationId", conversationId)
                .param("seq", seq)
                .param("senderId", senderId)
                .param("clientMessageId", clientMessageId)
                .param("type", type)
                .param("content", Mappers.toJson(content))
                .param("sentAt", Timestamp.from(sentAt))
                .update();
        return new ImMessage(
                id.toString(),
                conversationId.toString(),
                seq,
                senderId.toString(),
                clientMessageId,
                type,
                content,
                sentAt
        );
    }

    /**
     * 按客户端幂等键查找已写入的消息。
     *
     * 正常路径依赖数据库唯一约束去重；这里用于重试请求命中约束后回查已有结果，
     * 以及创建红包前的预检查。
     */
    public Optional<ImMessage> findByClientMessageId(UUID senderId, String clientMessageId) {
        return jdbcClient.sql(SELECT_MESSAGE + " WHERE sender_id = :senderId AND client_message_id = :clientMessageId")
                .param("senderId", senderId)
                .param("clientMessageId", clientMessageId)
                .query(MessageRepository::mapMessage)
                .optional();
    }

    public Optional<ImMessage> findById(UUID id) {
        return jdbcClient.sql(SELECT_MESSAGE + " WHERE id = :id")
                .param("id", id)
                .query(MessageRepository::mapMessage)
                .optional();
    }

    /** 按会话序号增量拉取，用于历史加载和断线补拉 */
    public List<ImMessage> listAfterSeq(UUID conversationId, long afterSeq, int limit) {
        return jdbcClient.sql(SELECT_MESSAGE + """
                         WHERE conversation_id = :conversationId
                           AND seq > :afterSeq
                           AND status = 'ACTIVE'
                         ORDER BY seq
                         LIMIT :limit
                        """)
                .param("conversationId", conversationId)
                .param("afterSeq", afterSeq)
                .param("limit", limit)
                .query(MessageRepository::mapMessage)
                .list();
    }

    private static ImMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
        return new ImMessage(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getLong("seq"),
                rs.getString("sender_id"),
                rs.getString("client_message_id"),
                rs.getString("type"),
                Mappers.json(rs, "content"),
                Mappers.instant(rs, "sent_at")
        );
    }
}
