package com.openim.server.domain.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 密码凭证数据访问
 *
 * 只暴露哈希，不提供任何返回明文或可逆值的方法——表里本来也没有。
 *
 * 失败计数与锁定放在数据库而非 Redis：多实例下必须共享同一份计数才能真正
 * 限制撞库，而 Redis 在本项目中是"可降级"组件（限流会在故障时放行），
 * 把账号锁定建立在可降级的存储上等于给撞库留了一个开关。
 */
@Repository
public class CredentialRepository {
    private final JdbcClient jdbcClient;

    public CredentialRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 按用户名或邮箱查找登录凭证。
     *
     * 一次 JOIN 同时取出账号状态与凭证，避免登录路径上两次往返。
     * 邮箱按 {@code lower()} 匹配，与 {@code uq_users_email_lower} 的唯一性判定一致。
     *
     * 只返回 ACTIVE 账号：FROZEN/DELETED 的凭证不参与比对，
     * 但调用方仍会执行一次哈希校验（见 AuthenticationService），
     * 否则响应时间会泄漏"该账号是否存在且可用"。
     */
    public Optional<Credential> findByLoginIdentifier(String identifier) {
        return jdbcClient.sql("""
                        SELECT c.user_id,
                               c.password_hash,
                               c.password_changed_at,
                               c.failed_attempts,
                               c.locked_until
                          FROM user_credentials c
                          JOIN users u ON u.id = c.user_id
                         WHERE u.status = 'ACTIVE'
                           AND (u.username = :identifier
                                OR lower(u.email) = lower(:identifier))
                        """)
                .param("identifier", identifier)
                .query(CredentialRepository::mapCredential)
                .optional();
    }

    /** 按用户 ID 读取凭证，供登录成功后的失败计数清零等场景 */
    public Optional<Credential> findByUserId(UUID userId) {
        return jdbcClient.sql("""
                        SELECT user_id, password_hash, password_changed_at,
                               failed_attempts, locked_until
                          FROM user_credentials
                         WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query(CredentialRepository::mapCredential)
                .optional();
    }

    /** 首次设置凭证。用户已存在但还没有密码时使用（注册流程的第二步） */
    public void insert(UUID userId, String passwordHash) {
        jdbcClient.sql("""
                        INSERT INTO user_credentials (user_id, password_hash)
                        VALUES (:userId, :hash)
                        """)
                .param("userId", userId)
                .param("hash", passwordHash)
                .update();
    }

    /**
     * 更新密码并解除锁定。
     *
     * 清零失败计数：用户已经证明了身份，此前的失败记录没有意义。
     * 让旧会话失效不在这里做——改密流程会调用
     * {@code SessionService.logoutAllDevices()} 撤销全部设备，
     * 那是唯一能同时作废访问令牌和刷新令牌的路径。
     */
    public void updatePassword(UUID userId, String passwordHash) {
        jdbcClient.sql("""
                        UPDATE user_credentials
                        SET password_hash       = :hash,
                            password_changed_at = now(),
                            failed_attempts     = 0,
                            locked_until        = NULL,
                            updated_at          = now()
                        WHERE user_id = :userId
                        """)
                .param("hash", passwordHash)
                .param("userId", userId)
                .update();
    }

    /**
     * 记录一次登录失败，达到阈值时锁定账号。
     *
     * 计数与锁定在同一条 UPDATE 内完成，因此并发的多次失败尝试不会因为
     * "读-改-写"的竞争而漏计——那正是撞库工具会利用的窗口。
     *
     * @return 本次失败之后的状态，供调用方决定是否告知锁定
     */
    public LockState recordFailure(UUID userId, int maxAttempts, long lockSeconds) {
        return jdbcClient.sql("""
                        UPDATE user_credentials
                        SET failed_attempts = failed_attempts + 1,
                            locked_until    = CASE
                                WHEN failed_attempts + 1 >= :maxAttempts
                                THEN now() + make_interval(secs => :lockSeconds)
                                ELSE locked_until
                            END,
                            updated_at      = now()
                        WHERE user_id = :userId
                        RETURNING failed_attempts, locked_until
                        """)
                .param("maxAttempts", maxAttempts)
                .param("lockSeconds", (double) lockSeconds)
                .param("userId", userId)
                .query((rs, rowNum) -> new LockState(
                        rs.getInt("failed_attempts"),
                        Mappers.instant(rs, "locked_until")))
                .single();
    }

    /** 登录成功：清零失败计数、解锁并记录时间 */
    public void recordSuccess(UUID userId) {
        jdbcClient.sql("""
                        UPDATE user_credentials
                        SET failed_attempts = 0,
                            locked_until    = NULL,
                            last_login_at   = now(),
                            updated_at      = now()
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .update();
    }

    /**
     * 删除凭证。
     *
     * 账号注销时调用。凭证是唯一一类<b>整行删除</b>而非匿名化的用户数据——
     * 保留一个已注销账号的密码哈希没有任何用途，只是多一份可被离线破解的材料。
     */
    public void deleteByUserId(UUID userId) {
        jdbcClient.sql("DELETE FROM user_credentials WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    /** 供管理员或风控解除锁定 */
    public void unlock(UUID userId) {
        jdbcClient.sql("""
                        UPDATE user_credentials
                        SET failed_attempts = 0, locked_until = NULL, updated_at = now()
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .update();
    }

    /** 行映射：user_credentials 表到凭证模型 */
    private static Credential mapCredential(ResultSet rs, int rowNum) throws SQLException {
        return new Credential(
                UUID.fromString(rs.getString("user_id")),
                rs.getString("password_hash"),
                Mappers.instant(rs, "password_changed_at"),
                rs.getInt("failed_attempts"),
                Mappers.instant(rs, "locked_until")
        );
    }

    public record Credential(
            UUID userId,
            String passwordHash,
            Instant passwordChangedAt,
            int failedAttempts,
            Instant lockedUntil
    ) {
        /** 是否处于锁定状态。锁定到期后无需显式解锁，时间比较即可 */
        public boolean locked() {
            return lockedUntil != null && lockedUntil.isAfter(Instant.now());
        }
    }

    public record LockState(int failedAttempts, Instant lockedUntil) {
        /** 是否处于锁定状态。锁定到期后无需显式解锁，时间比较即可 */
        public boolean locked() {
            return lockedUntil != null && lockedUntil.isAfter(Instant.now());
        }
    }
}
