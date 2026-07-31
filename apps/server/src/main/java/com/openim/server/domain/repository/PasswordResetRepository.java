package com.openim.server.domain.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 密码重置令牌数据访问
 *
 * 与刷新令牌一致：只存 SHA-256 哈希。明文令牌只在发送给用户的那一次存在，
 * 服务端不留存——数据库泄漏时攻击者拿不到可用于重置的凭据。
 */
@Repository
public class PasswordResetRepository {
    private final JdbcClient jdbcClient;

    public PasswordResetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 写入一条重置令牌记录，只存令牌哈希与来源 IP 哈希，不存明文 */
    public void insert(
            UUID id,
            UUID userId,
            String tokenHash,
            Instant expiresAt,
            String requestedIpHash
    ) {
        jdbcClient.sql("""
                        INSERT INTO password_reset_tokens
                            (id, user_id, token_hash, expires_at, requested_ip_hash)
                        VALUES (:id, :userId, :hash, :expiresAt, :ipHash)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("hash", tokenHash)
                // Timestamp.from 而非直接传 Instant：PostgreSQL JDBC 驱动无法为
                // Instant 推断 SQL 类型。与 DeviceRepository 的处理方式一致。
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("ipHash", requestedIpHash)
                .update();
    }

    /**
     * 按哈希查找令牌。
     *
     * 返回全部状态字段而不是只返回"是否有效"：调用方需要区分
     * 过期、已用、有效三种情况来决定审计记录的内容，
     * 但对外的响应必须统一（见 PasswordResetService）。
     */
    public Optional<ResetToken> findByHash(String tokenHash) {
        return jdbcClient.sql("""
                        SELECT t.id, t.user_id, t.expires_at, t.used_at
                          FROM password_reset_tokens t
                          JOIN users u ON u.id = t.user_id
                         WHERE t.token_hash = :hash
                           AND u.status = 'ACTIVE'
                        """)
                .param("hash", tokenHash)
                .query((rs, rowNum) -> new ResetToken(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("user_id")),
                        Mappers.instant(rs, "expires_at"),
                        Mappers.instant(rs, "used_at")))
                .optional();
    }

    /**
     * 标记令牌已使用。
     *
     * {@code WHERE used_at IS NULL} 是并发保护：两个请求同时提交同一个令牌时，
     * 只有一个能把它标记为已用，另一个更新 0 行。调用方据此判定失败——
     * 少了这个条件，一个令牌可以被并发使用多次。
     *
     * @return 是否成功标记（false 表示已被其他请求用掉）
     */
    public boolean markUsed(UUID id) {
        return jdbcClient.sql("""
                        UPDATE password_reset_tokens
                        SET used_at = now()
                        WHERE id = :id AND used_at IS NULL
                        """)
                .param("id", id)
                .update() == 1;
    }

    /**
     * 作废某用户全部未使用的令牌。
     *
     * 请求新令牌时调用：否则用户连续点三次"忘记密码"会得到三个同时有效的令牌，
     * 攻击面是三倍，而用户只会用最新那一封邮件里的。
     */
    public int invalidateAllForUser(UUID userId) {
        return jdbcClient.sql("""
                        UPDATE password_reset_tokens
                        SET used_at = now()
                        WHERE user_id = :userId AND used_at IS NULL
                        """)
                .param("userId", userId)
                .update();
    }

    /**
     * 删除已过期或已使用的旧令牌。
     *
     * 保留一段时间而不是立即删除：排查"账号是谁重置的"需要这些记录。
     * 但不能永久保留——它是一张只增不减的表。
     */
    public int deleteExpiredBefore(Instant cutoff) {
        return jdbcClient.sql("""
                        DELETE FROM password_reset_tokens
                        WHERE (used_at IS NOT NULL AND used_at < :cutoff)
                           OR (used_at IS NULL AND expires_at < :cutoff)
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    /**
     * 删除某用户的全部重置令牌。
     *
     * 账号注销时调用。与 {@link #invalidateAllForUser} 不同——那个只标记已用
     * （保留记录用于排查），这个是真删。注销后没有"排查这个账号被谁重置过"的需求了，
     * 而令牌记录里的 IP 哈希仍然是与该用户关联的数据。
     */
    public int deleteAllForUser(UUID userId) {
        return jdbcClient.sql("DELETE FROM password_reset_tokens WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    public record ResetToken(
            UUID id,
            UUID userId,
            Instant expiresAt,
            Instant usedAt
    ) {
        /** 是否已过期；expiresAt 为空视为已过期（不应发生的兜底） */
        public boolean expired() {
            return expiresAt == null || expiresAt.isBefore(Instant.now());
        }

        /** 是否已被使用过 */
        public boolean used() {
            return usedAt != null;
        }

        /** 是否仍可使用：未被用过且未过期 */
        public boolean usable() {
            return !used() && !expired();
        }
    }
}
