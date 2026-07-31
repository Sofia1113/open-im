package com.openim.server.domain.repository;

import com.openim.server.domain.DomainModels.CallParticipant;
import com.openim.server.domain.DomainModels.CallRoom;
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
 * 通话房间数据访问
 *
 * 通话状态本身是短暂的，但房间与参与者需要落库：一是多实例部署下信令授权必须有共享事实，
 * 二是通话记录属于需要保留的业务元数据（保留期见 DATA_MODEL.md §5）。
 */
@Repository
public class CallRepository {
    private static final String SELECT_ROOM = """
            SELECT id, conversation_id, creator_id, media_type, status,
                   created_at, expires_at, started_at, ended_at, end_reason
              FROM call_rooms
            """;

    private final JdbcClient jdbcClient;

    public CallRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 创建通话房间，初始状态为 ringing；幂等性由 idempotencyKey 保证（见 findIdByCreatorIdempotency） */
    public void insertRoom(
            UUID id,
            UUID conversationId,
            UUID creatorId,
            String mediaType,
            Instant createdAt,
            Instant expiresAt,
            String idempotencyKey
    ) {
        jdbcClient.sql("""
                        INSERT INTO call_rooms
                            (id, conversation_id, creator_id, media_type, topology, status,
                             idempotency_key, created_at, expires_at)
                        VALUES
                            (:id, :conversationId, :creatorId, :mediaType, 'MESH', 'ringing',
                             :idempotencyKey, :createdAt, :expiresAt)
                        """)
                .param("id", id)
                .param("conversationId", conversationId)
                .param("creatorId", creatorId)
                .param("mediaType", mediaType)
                .param("idempotencyKey", idempotencyKey)
                .param("createdAt", Timestamp.from(createdAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    /** 记录一个参与者加入房间；joinedAt 可空（未接通时不落时间） */
    public void insertParticipant(UUID roomId, UUID userId, String state, String joinedDeviceId, Instant joinedAt) {
        jdbcClient.sql("""
                        INSERT INTO call_participants (room_id, user_id, state, joined_device_id, joined_at)
                        VALUES (:roomId, :userId, :state, :joinedDeviceId, :joinedAt)
                        """)
                .param("roomId", roomId)
                .param("userId", userId)
                .param("state", state)
                .param("joinedDeviceId", joinedDeviceId)
                .param("joinedAt", joinedAt == null ? null : Timestamp.from(joinedAt))
                .update();
    }

    /** 读取房间并附带参与者列表，供状态查询使用 */
    public Optional<CallRoom> findById(UUID roomId) {
        return jdbcClient.sql(SELECT_ROOM + " WHERE id = :id")
                .param("id", roomId)
                .query(CallRepository::mapRoom)
                .optional()
                .map(this::withParticipants);
    }

    /** 锁定房间行，用于接听/拒绝/离开等需要串行判定状态的操作 */
    public Optional<CallRoom> findByIdForUpdate(UUID roomId) {
        return jdbcClient.sql(SELECT_ROOM + " WHERE id = :id FOR UPDATE")
                .param("id", roomId)
                .query(CallRepository::mapRoom)
                .optional()
                .map(this::withParticipants);
    }

    /** 按创建者与幂等键回查房间 ID，客户端重试发起时复用已有房间 */
    public Optional<UUID> findIdByCreatorIdempotency(UUID creatorId, String idempotencyKey) {
        return jdbcClient.sql("""
                        SELECT id FROM call_rooms
                        WHERE creator_id = :creatorId AND idempotency_key = :idempotencyKey
                        """)
                .param("creatorId", creatorId)
                .param("idempotencyKey", idempotencyKey)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .optional();
    }

    /** 忙线检测：用户是否已加入某个未结束的通话 */
    public boolean isUserBusy(UUID userId) {
        return jdbcClient.sql("""
                        SELECT 1
                          FROM call_participants p
                          JOIN call_rooms r ON r.id = p.room_id
                         WHERE p.user_id = :userId
                           AND p.state = 'joined'
                           AND r.status <> 'ended'
                         LIMIT 1
                        """)
                .param("userId", userId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** 更新参与者状态；joined/left 的时间戳由 SQL 内的 CASE 按状态自动落，避免应用层忘记 */
    public void updateParticipantState(UUID roomId, UUID userId, String state, String joinedDeviceId) {
        jdbcClient.sql("""
                        UPDATE call_participants
                        SET state = :state,
                            joined_device_id = :joinedDeviceId,
                            joined_at = CASE WHEN :state = 'joined' THEN COALESCE(joined_at, now()) ELSE joined_at END,
                            left_at = CASE WHEN :state IN ('left', 'rejected') THEN now() ELSE left_at END
                        WHERE room_id = :roomId AND user_id = :userId
                        """)
                .param("state", state)
                .param("joinedDeviceId", joinedDeviceId)
                .param("roomId", roomId)
                .param("userId", userId)
                .update();
    }

    /** 标记通话接通；已结束的房间不会被改写 */
    public void markActive(UUID roomId) {
        jdbcClient.sql("""
                        UPDATE call_rooms
                        SET status = 'active', started_at = COALESCE(started_at, now())
                        WHERE id = :id AND status <> 'ended'
                        """)
                .param("id", roomId)
                .update();
    }

    /** 结束通话并记录原因；只对未结束的房间生效，重复调用不覆盖已存在的结果 */
    public void endRoom(UUID roomId, String reason) {
        jdbcClient.sql("""
                        UPDATE call_rooms
                        SET status = 'ended', ended_at = now(), end_reason = :reason
                        WHERE id = :id AND status <> 'ended'
                        """)
                .param("reason", reason)
                .param("id", roomId)
                .update();
    }

    /** 房间内处于指定状态的参与者数量，用于判断是否需要自动结束通话 */
    public int countParticipantsInState(UUID roomId, String state) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM call_participants
                        WHERE room_id = :roomId AND state = :state
                        """)
                .param("roomId", roomId)
                .param("state", state)
                .query(Integer.class)
                .single();
    }

    /** 读取单个参与者的状态，用于信令授权判断 */
    public Optional<CallParticipant> findParticipant(UUID roomId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT user_id, state, joined_device_id FROM call_participants
                        WHERE room_id = :roomId AND user_id = :userId
                        """)
                .param("roomId", roomId)
                .param("userId", userId)
                .query(CallRepository::mapParticipant)
                .optional();
    }

    /** 扫描超时未接听的通话邀请，供 Worker 收敛状态 */
    public List<UUID> lockExpiredRoomIds(int limit) {
        return jdbcClient.sql("""
                        SELECT id FROM call_rooms
                        WHERE status <> 'ended' AND expires_at <= now()
                        ORDER BY expires_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .list();
    }

    /** 为房间行补齐参与者列表，按加入先后排序 */
    private CallRoom withParticipants(CallRoom room) {
        List<CallParticipant> participants = jdbcClient.sql("""
                        SELECT user_id, state, joined_device_id FROM call_participants
                        WHERE room_id = :roomId
                        ORDER BY joined_at NULLS LAST, user_id
                        """)
                .param("roomId", UUID.fromString(room.id()))
                .query(CallRepository::mapParticipant)
                .list();
        return new CallRoom(
                room.id(),
                room.conversationId(),
                room.creatorId(),
                room.mediaType(),
                room.status(),
                participants,
                room.createdAt(),
                room.expiresAt(),
                room.endedAt(),
                room.endReason()
        );
    }

    /** 行映射：call_rooms 表到房间模型，参与者列表留空由调用方补齐 */
    private static CallRoom mapRoom(ResultSet rs, int rowNum) throws SQLException {
        return new CallRoom(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getString("creator_id"),
                rs.getString("media_type"),
                rs.getString("status"),
                List.of(),
                Mappers.instant(rs, "created_at"),
                Mappers.instant(rs, "expires_at"),
                Mappers.instant(rs, "ended_at"),
                rs.getString("end_reason")
        );
    }

    /** 行映射：call_participants 表到参与者模型 */
    private static CallParticipant mapParticipant(ResultSet rs, int rowNum) throws SQLException {
        return new CallParticipant(
                rs.getString("user_id"),
                rs.getString("state"),
                rs.getString("joined_device_id")
        );
    }
}
