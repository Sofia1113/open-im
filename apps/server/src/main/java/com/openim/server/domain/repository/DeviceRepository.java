package com.openim.server.domain.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 设备会话数据访问
 *
 * 只存储刷新令牌的哈希，不存明文：数据库泄漏时攻击者无法直接拿哈希换取新会话。
 */
@Repository
public class DeviceRepository {
    private final JdbcClient jdbcClient;

    public DeviceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 登记设备并写入当前刷新令牌哈希。
     *
     * 同一 deviceId 重新登录时覆盖原记录并清除撤销标记——用户重新通过了身份验证，
     * 此前因令牌重放触发的撤销不应阻止其正常登录。
     */
    public void upsert(
            String deviceId,
            UUID userId,
            String name,
            String refreshTokenHash,
            Instant refreshTokenExpiresAt
    ) {
        jdbcClient.sql("""
                        INSERT INTO devices
                            (id, user_id, platform, name, refresh_token_hash,
                             refresh_token_expires_at, refresh_token_rotated_at, last_active_at)
                        VALUES
                            (:id, :userId, 'WEB', :name, :hash, :expiresAt, now(), now())
                        ON CONFLICT (id) DO UPDATE SET
                            user_id                  = EXCLUDED.user_id,
                            name                     = EXCLUDED.name,
                            refresh_token_hash       = EXCLUDED.refresh_token_hash,
                            refresh_token_expires_at = EXCLUDED.refresh_token_expires_at,
                            refresh_token_rotated_at = now(),
                            last_active_at           = now(),
                            revoked_at               = NULL,
                            revoked_reason           = NULL
                        """)
                .param("id", deviceId)
                .param("userId", userId)
                .param("name", name)
                .param("hash", refreshTokenHash)
                .param("expiresAt", Timestamp.from(refreshTokenExpiresAt))
                .update();
    }

    /** 按设备 ID 读取会话记录（含撤销状态），供令牌校验与撤销判定使用 */
    public Optional<DeviceSession> findById(String deviceId) {
        return jdbcClient.sql("""
                        SELECT id, user_id, refresh_token_hash, previous_refresh_token_hash,
                               refresh_token_expires_at, refresh_token_rotated_at,
                               revoked_at, revoked_reason
                          FROM devices
                         WHERE id = :id
                        """)
                .param("id", deviceId)
                .query(DeviceRepository::mapSession)
                .optional();
    }

    /**
     * 轮换刷新令牌。
     *
     * 更新条件带上旧哈希，使轮换成为一次比较并交换操作：
     * 两个并发刷新请求只有一个能成功，另一个会看到 0 行更新并被当作令牌重放处理。
     *
     * @return 是否轮换成功
     */
    public boolean rotateRefreshToken(
            String deviceId,
            String expectedCurrentHash,
            String newHash,
            Instant newExpiresAt
    ) {
        int updated = jdbcClient.sql("""
                        UPDATE devices
                        SET previous_refresh_token_hash = refresh_token_hash,
                            refresh_token_hash          = :newHash,
                            refresh_token_expires_at    = :expiresAt,
                            refresh_token_rotated_at    = now(),
                            last_active_at              = now()
                        WHERE id = :id
                          AND revoked_at IS NULL
                          AND refresh_token_hash = :expectedHash
                        """)
                .param("newHash", newHash)
                .param("expiresAt", Timestamp.from(newExpiresAt))
                .param("id", deviceId)
                .param("expectedHash", expectedCurrentHash)
                .update();
        return updated == 1;
    }

    /**
     * 在宽限期内用上一个令牌换取新令牌。
     *
     * 条件同时约束 previous 哈希与轮换时间，因此并发的两个请求里只有一个能成功，
     * 且超出窗口的旧令牌不会命中——它会落到调用方的重放分支。
     *
     * @return 是否交换成功
     */
    public boolean rotateWithinGracePeriod(
            String deviceId,
            String expectedPreviousHash,
            String newHash,
            Instant newExpiresAt,
            long graceSeconds
    ) {
        int updated = jdbcClient.sql("""
                        UPDATE devices
                        SET refresh_token_hash       = :newHash,
                            refresh_token_expires_at = :expiresAt,
                            last_active_at           = now()
                        WHERE id = :id
                          AND revoked_at IS NULL
                          AND previous_refresh_token_hash = :expectedHash
                          AND refresh_token_rotated_at > now() - make_interval(secs => :graceSeconds)
                        """)
                .param("newHash", newHash)
                .param("expiresAt", Timestamp.from(newExpiresAt))
                .param("id", deviceId)
                .param("expectedHash", expectedPreviousHash)
                .param("graceSeconds", (double) graceSeconds)
                .update();
        return updated == 1;
    }

    /** 撤销单个设备：清除刷新令牌并置撤销标记，重复撤销是幂等的 */
    public void revoke(String deviceId, String reason) {
        jdbcClient.sql("""
                        UPDATE devices
                        SET revoked_at = now(), revoked_reason = :reason, refresh_token_hash = NULL
                        WHERE id = :id AND revoked_at IS NULL
                        """)
                .param("reason", reason)
                .param("id", deviceId)
                .update();
    }

    /** 撤销用户全部设备，用于改密、风控处置和"退出所有设备" */
    public int revokeAllForUser(UUID userId, String reason) {
        return jdbcClient.sql("""
                        UPDATE devices
                        SET revoked_at = now(), revoked_reason = :reason, refresh_token_hash = NULL
                        WHERE user_id = :userId AND revoked_at IS NULL
                        """)
                .param("reason", reason)
                .param("userId", userId)
                .update();
    }

    /**
     * 设备是否仍可用于访问。
     *
     * 访问令牌校验时调用，使远程下线能立即生效，而不必等令牌自然过期。
     */
    public boolean isActive(String deviceId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT 1 FROM devices
                        WHERE id = :id AND user_id = :userId AND revoked_at IS NULL
                        """)
                .param("id", deviceId)
                .param("userId", userId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * 一次查询同时校验账号状态与设备授权。
     *
     * 鉴权在每个请求上都会执行，因此合并为单条 JOIN 而不是分别查 users 和 devices，
     * 避免每请求两次数据库往返。
     */
    public boolean isSessionActive(String deviceId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT 1
                          FROM devices d
                          JOIN users u ON u.id = d.user_id
                         WHERE d.id = :deviceId
                           AND d.user_id = :userId
                           AND d.revoked_at IS NULL
                           AND u.status = 'ACTIVE'
                        """)
                .param("deviceId", deviceId)
                .param("userId", userId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** 更新活跃时间，用于设备管理界面展示最近使用时间 */
    public void touch(String deviceId) {
        jdbcClient.sql("UPDATE devices SET last_active_at = now() WHERE id = :id")
                .param("id", deviceId)
                .update();
    }

    /** 行映射：devices 表到会话视图 */
    private static DeviceSession mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new DeviceSession(
                rs.getString("id"),
                UUID.fromString(rs.getString("user_id")),
                rs.getString("refresh_token_hash"),
                rs.getString("previous_refresh_token_hash"),
                Mappers.instant(rs, "refresh_token_expires_at"),
                Mappers.instant(rs, "refresh_token_rotated_at"),
                Mappers.instant(rs, "revoked_at"),
                rs.getString("revoked_reason")
        );
    }

    public record DeviceSession(
            String deviceId,
            UUID userId,
            String refreshTokenHash,
            String previousRefreshTokenHash,
            Instant refreshTokenExpiresAt,
            Instant refreshTokenRotatedAt,
            Instant revokedAt,
            String revokedReason
    ) {
        /** 是否已被撤销，供校验路径快速判定而不必逐字段判断 */
        public boolean revoked() {
            return revokedAt != null;
        }
    }
}
