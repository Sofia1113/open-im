package com.openim.server.domain.repository;

import com.openim.server.domain.DomainModels.User;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户数据访问
 *
 * 注意 API 契约中的 {@code User.status} 承载的是实时在线状态（presence），
 * 而数据库的 {@code users.status} 是账号状态（ACTIVE/FROZEN/DELETED）。
 * 两者在此处完成映射，不向上层泄漏该差异。
 */
@Repository
public class UserRepository {
    private final JdbcClient jdbcClient;

    public UserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<User> findAllActive() {
        return jdbcClient.sql("""
                        SELECT id, username, display_name, avatar, presence, last_seen_at
                        FROM users
                        WHERE status = 'ACTIVE'
                        ORDER BY username
                        """)
                .query(UserRepository::mapUser)
                .list();
    }

    public Optional<User> findById(UUID id) {
        return jdbcClient.sql("""
                        SELECT id, username, display_name, avatar, presence, last_seen_at
                        FROM users
                        WHERE id = :id AND status <> 'DELETED'
                        """)
                .param("id", id)
                .query(UserRepository::mapUser)
                .optional();
    }

    public Optional<User> findByUsername(String username) {
        return jdbcClient.sql("""
                        SELECT id, username, display_name, avatar, presence, last_seen_at
                        FROM users
                        WHERE username = :username AND status = 'ACTIVE'
                        """)
                .param("username", username)
                .query(UserRepository::mapUser)
                .optional();
    }

    /** 判断账号是否存在且处于可用状态，用于鉴权链路上的轻量校验 */
    public boolean existsActive(UUID id) {
        Integer count = jdbcClient.sql("SELECT 1 FROM users WHERE id = :id AND status = 'ACTIVE'")
                .param("id", id)
                .query(Integer.class)
                .optional()
                .orElse(null);
        return count != null;
    }

    /**
     * 更新下线时间戳。
     *
     * presence（在线/离线状态）已迁移到 Redis，由 PresenceService 维护；
     * 数据库只保留 last_seen_at 作为历史记录，不再存储实时状态。
     */
    public void updateLastSeenAt(UUID id, java.time.Instant lastSeenAt) {
        jdbcClient.sql("""
                        UPDATE users
                        SET last_seen_at = :lastSeenAt, updated_at = now()
                        WHERE id = :id
                        """)
                .param("lastSeenAt", java.sql.Timestamp.from(lastSeenAt))
                .param("id", id)
                .update();
    }

    static User mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new User(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("avatar"),
                rs.getString("presence"),
                Mappers.instant(rs, "last_seen_at")
        );
    }
}
