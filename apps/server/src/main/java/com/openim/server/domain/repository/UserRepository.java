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
     * 更新实时在线状态
     *
     * 单实例部署下由 WebSocket 连接注册表驱动。迁移到多实例后，presence 应改由
     * Redis 维护并以此表作为兜底快照，避免实例崩溃后状态永久停留在 online。
     */
    public void updatePresence(UUID id, boolean online) {
        jdbcClient.sql("""
                        UPDATE users
                        SET presence = :presence,
                            last_seen_at = CASE WHEN :online THEN NULL ELSE now() END,
                            updated_at = now()
                        WHERE id = :id
                        """)
                .param("presence", online ? "online" : "offline")
                .param("online", online)
                .param("id", id)
                .update();
    }

    /** 进程启动时把残留的 online 状态清零，防止上次非正常退出留下的脏状态 */
    public int resetAllPresence() {
        return jdbcClient.sql("""
                        UPDATE users
                        SET presence = 'offline', last_seen_at = now(), updated_at = now()
                        WHERE presence = 'online'
                        """)
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
