package com.openim.server.domain.repository;

import com.openim.server.domain.DomainModels.User;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /**
     * 与我有过会话往来的人。
     *
     * <b>取代原先的"返回全站所有用户"。</b>{@code findAllActive} 是一次无
     * {@code LIMIT} 的全表扫描，而它的调用方是客户端加载时就会请求的
     * {@code GET /api/v1/users}。两个问题叠加：任何登录用户可以枚举全部注册者的
     * 用户名与在线状态（隐私），且响应体与注册量成正比（容量）——十万用户时
     * 这一个接口就能打满连接池。
     *
     * 新语义是"我的联系人"：至少与我共处过一个会话的人。这不是好友关系
     * （本项目没有好友模型），而是<b>可推导的最小可见集</b>——他们的昵称在会话
     * 成员列表里本来就对我可见，所以这个接口不再扩大任何暴露面。
     *
     * 与我无任何往来的人不再出现在这里；要主动找到某个人用
     * {@link #searchByUsername}，它要求提供完整用户名。
     *
     * {@code DISTINCT} 而非 {@code GROUP BY}：一个人可能与我在多个会话里同时出现。
     * 排序按 {@code display_name} 而不是 {@code username}——这是给人看的列表。
     */
    public List<User> findContacts(UUID userId, int limit) {
        return jdbcClient.sql("""
                        SELECT DISTINCT u.id, u.username, u.display_name, u.avatar,
                               u.presence, u.last_seen_at
                          FROM conversation_members mine
                          JOIN conversation_members theirs
                            ON theirs.conversation_id = mine.conversation_id
                           AND theirs.user_id <> mine.user_id
                           AND theirs.left_at IS NULL
                          JOIN users u ON u.id = theirs.user_id
                         WHERE mine.user_id = :userId
                           AND mine.left_at IS NULL
                           AND u.status = 'ACTIVE'
                         ORDER BY u.display_name, u.username
                         LIMIT :limit
                        """)
                .param("userId", userId)
                .param("limit", limit)
                .query(UserRepository::mapUser)
                .list();
    }

    /**
     * 按<b>完整</b>用户名查找，用于主动添加一个还没有往来的人。
     *
     * 刻意<b>不做前缀或模糊匹配</b>：`LIKE 'ab%'` 允许攻击者用几百次请求枚举出
     * 大部分用户名，那等于把刚刚移除的全站列表又还回去了，只是慢一点。
     * 要求提供完整用户名意味着调用方必须<b>已经知道</b>要找谁——
     * 这正是"通过用户名加好友"这个交互的前提。
     *
     * 返回 Optional 而不是列表：完整用户名唯一，返回列表会让调用方以为
     * 可以拿到多个结果，进而催生"那就模糊匹配吧"的改动。
     *
     * 按 {@code lower(username)} 匹配：注册时限定小写，但用户可能输入大写。
     * 唯一约束建在原始列上，因此这里不能命中索引——用户名搜索是低频操作
     * （每次加人一次），且已由 {@code USER_SEARCH} 限流保护。
     */
    public Optional<User> searchByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                        SELECT id, username, display_name, avatar, presence, last_seen_at
                          FROM users
                         WHERE lower(username) = lower(:username)
                           AND status = 'ACTIVE'
                        """)
                .param("username", username.trim())
                .query(UserRepository::mapUser)
                .optional();
    }

    /**
     * 按 ID 批量取用户资料，用于渲染<b>已知</b>的人。
     *
     * 消息发送者、红包领取者、通话参与者的 ID 由服务端在各自的载荷里给出，
     * 客户端需要把它们换成昵称和头像。这条路径不是"发现新的人"，因此不需要
     * 可见性边界——ID 本身已经通过一个鉴权过的接口交给了客户端。
     *
     * 没有这个接口的话，收窄联系人列表会让历史消息里已退群成员的昵称
     * 变成一串 UUID：他们不在我的联系人里，而消息还在。
     */
    public List<User> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT id, username, display_name, avatar, presence, last_seen_at
                          FROM users
                         WHERE id IN (:ids) AND status <> 'DELETED'
                        """)
                .param("ids", ids)
                .query(UserRepository::mapUser)
                .list();
    }

    /** 按 ID 读取未注销的账号，用于用户资料展示 */
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

    /** 按完整用户名精确查找活跃账号，供登录等内部路径使用 */
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

    /**
     * 按邮箱查找账号，用于密码重置。
     *
     * <b>返回专用类型而不是 {@link User}</b>：邮箱刻意<i>不</i>放进通用的 User 模型。
     * User 会随会话成员列表、消息发送者、通话参与者等接口返回给客户端，
     * 一旦加上 email 字段，任何群成员都能看到其他成员的邮箱——
     * 一个只为密码重置服务的字段没有理由承担这个暴露面。
     *
     * 匹配按 {@code lower(email)}，与 {@code uq_users_email_lower} 的唯一性判定一致，
     * 因此用户用大小写混合的邮箱也能找回密码。
     */
    public Optional<ResetTarget> findActiveByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                        SELECT id, username, display_name, email
                          FROM users
                         WHERE lower(email) = lower(:email)
                           AND status = 'ACTIVE'
                        """)
                .param("email", email)
                .query((rs, rowNum) -> new ResetTarget(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("email")))
                .optional();
    }

    /**
     * 密码重置的目标账号。
     *
     * 只包含重置流程需要的字段，且这个类型不会出现在任何 HTTP 响应里——
     * 它存在的意义就是把 email 限制在服务端内部。
     */
    public record ResetTarget(
            UUID userId,
            String username,
            String displayName,
            String email
    ) {
    }

    /**
     * 匿名化账号，履行删除权。
     *
     * 不是 DELETE：{@code users} 被 14 处外键引用，硬删除会破坏账务完整性
     * 或让其他用户的会话历史出现空洞。详见
     * {@code V6__account_deletion.sql} 与 {@code AccountDeletionService}。
     *
     * {@code email} 置 NULL 后不会与新注册的同邮箱账号冲突——唯一索引
     * {@code uq_users_email_lower} 带 {@code status <> 'DELETED'} 条件，
     * 这个条件当初就是为此预留的。
     *
     * {@code presence} 一并置为离线：匿名化后的账号不应出现在任何在线列表里。
     */
    public void anonymize(UUID userId, String anonymizedUsername, String displayName,
                          String avatar) {
        jdbcClient.sql("""
                        UPDATE users
                           SET username      = :username,
                               display_name  = :displayName,
                               avatar        = :avatar,
                               email         = NULL,
                               status        = 'DELETED',
                               presence      = 'offline',
                               deleted_at    = coalesce(deleted_at, now()),
                               anonymized_at = now()
                         WHERE id = :userId
                        """)
                .param("username", anonymizedUsername)
                .param("displayName", displayName)
                .param("avatar", avatar)
                .param("userId", userId)
                .update();
    }

    /**
     * 创建账号。
     *
     * 用户名与邮箱的唯一性由数据库约束保证（{@code users_username_key} 与
     * {@code uq_users_email_lower}），冲突时抛 {@link org.springframework.dao.DuplicateKeyException}
     * 交由上层转换为业务错误。不在此处先查后写：并发注册同一用户名时
     * 预检查会让两个请求都通过。
     *
     * 同时创建钱包账户：用户没有钱包时红包功能会在运行期抛
     * "钱包不存在"，而这个前置条件跟账号创建是同一件事，
     * 分两步做只会留下一类不可用的账号。
     */
    public void insertWithCredentials(
            UUID id,
            String username,
            String email,
            String displayName,
            String avatar
    ) {
        jdbcClient.sql("""
                        INSERT INTO users (id, username, email, display_name, avatar, status)
                        VALUES (:id, :username, :email, :displayName, :avatar, 'ACTIVE')
                        """)
                .param("id", id)
                .param("username", username)
                .param("email", email)
                .param("displayName", displayName)
                .param("avatar", avatar)
                .update();
        jdbcClient.sql("""
                        INSERT INTO wallet_accounts
                            (id, owner_type, user_id, purpose, currency, available_balance)
                        VALUES (:accountId, 'USER', :userId, 'GENERAL', 'CNY', 0)
                        """)
                .param("accountId", UUID.randomUUID())
                .param("userId", id)
                .update();
    }

    /**
     * 从给定 ID 中筛出确实存在且可用的那些。
     *
     * 批量加人时逐个调用 {@link #existsActive} 是 N 次往返，而这个校验发生在
     * 一个持有会话行锁的事务里——往返次数直接决定锁的持有时长，期间该会话的
     * 消息发送全部阻塞。一次查询拿回全部结果，调用方用差集算出哪些 ID 无效。
     *
     * 返回 Set 而不是 boolean：调用方需要知道<b>具体哪个</b>用户不存在才能
     * 给出可用的错误信息，"其中有人不存在"对使用者没有任何帮助。
     */
    public Set<UUID> findActiveIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            // IN () 是语法错误。空集合的答案是空集合，不需要访问数据库
            return Set.of();
        }
        return new HashSet<>(jdbcClient.sql("""
                        SELECT id FROM users
                         WHERE id IN (:ids) AND status = 'ACTIVE'
                        """)
                .param("ids", ids)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .list());
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

    /** 行映射：users 表到领域模型（presence 是实时状态，见类注释） */
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
