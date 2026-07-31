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

    /** 写入一条消息并返回领域模型。客户端重试时由数据库唯一约束去重，此处不负责幂等判断 */
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

    /** 按消息 ID 读取，用于消息详情等场景 */
    public Optional<ImMessage> findById(UUID id) {
        return jdbcClient.sql(SELECT_MESSAGE + " WHERE id = :id")
                .param("id", id)
                .query(MessageRepository::mapMessage)
                .optional();
    }

    /**
     * 按会话序号向<b>后</b>拉取，用于断线补拉。
     *
     * 这是"我离线期间漏了什么"的语义：从已知位置往新的方向取。
     * 打开会话时不该用它——那会从最早的消息开始读，见 {@link #listLatest}。
     */
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

    /**
     * 会话最新的一页消息，用于<b>打开会话</b>。
     *
     * 此前打开会话走的是 {@code listAfterSeq(0)} 然后翻页翻到底——也就是把
     * <b>整个会话的全部历史</b>拉进内存。一个累积了五万条消息的会话，点开它就是
     * 500 次串行 HTTP 请求。这不只是"看不到更早的消息"，而是打开会话本身会越来越慢，
     * 且劣化程度与会话活跃度成正比。演示数据里每个会话两条消息，永远看不出来。
     *
     * {@code ORDER BY seq DESC} 命中 {@code idx_messages_conversation_seq}
     * （建索引时就是 {@code (conversation_id, seq DESC)}），因此取最新一页是
     * 一次索引前缀扫描，与会话总长度无关。
     *
     * 返回<b>升序</b>：调用方要的是可直接渲染的顺序，让每个调用方各自反转
     * 是在重复同一件事，而漏了反转的地方会静默地把消息倒着显示。
     */
    public List<ImMessage> listLatest(UUID conversationId, long afterSeqExclusive, int limit) {
        List<ImMessage> descending = jdbcClient.sql(SELECT_MESSAGE + """
                         WHERE conversation_id = :conversationId
                           AND seq > :afterSeqExclusive
                           AND status = 'ACTIVE'
                         ORDER BY seq DESC
                         LIMIT :limit
                        """)
                .param("conversationId", conversationId)
                .param("afterSeqExclusive", afterSeqExclusive)
                .param("limit", limit)
                .query(MessageRepository::mapMessage)
                .list();
        return reversed(descending);
    }

    /**
     * 比给定序号更早的一页消息，用于向上翻阅历史。
     *
     * {@code afterSeqExclusive} 是成员的历史可见下界（{@code join_seq}），
     * 由服务层传入而不是在这里默认为 0——把可见范围的判断留给调用方"记得传"，
     * 迟早会有一条路径忘记传而泄露入群前的历史。
     */
    public List<ImMessage> listBeforeSeq(
            UUID conversationId,
            long beforeSeq,
            long afterSeqExclusive,
            int limit
    ) {
        List<ImMessage> descending = jdbcClient.sql(SELECT_MESSAGE + """
                         WHERE conversation_id = :conversationId
                           AND seq < :beforeSeq
                           AND seq > :afterSeqExclusive
                           AND status = 'ACTIVE'
                         ORDER BY seq DESC
                         LIMIT :limit
                        """)
                .param("conversationId", conversationId)
                .param("beforeSeq", beforeSeq)
                .param("afterSeqExclusive", afterSeqExclusive)
                .param("limit", limit)
                .query(MessageRepository::mapMessage)
                .list();
        return reversed(descending);
    }

    /** 倒序查询结果转回升序，并返回不可变列表 */
    private static List<ImMessage> reversed(List<ImMessage> descending) {
        List<ImMessage> ascending = new java.util.ArrayList<>(descending);
        java.util.Collections.reverse(ascending);
        return List.copyOf(ascending);
    }

    /** 行映射：messages 表到领域模型，content 为 jsonb 需反序列化 */
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
