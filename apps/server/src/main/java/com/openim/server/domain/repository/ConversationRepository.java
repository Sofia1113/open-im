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

    /** 某用户的会话列表，按最近活跃时间倒序 */
    public List<Conversation> listForUser(UUID userId) {
        return jdbcClient.sql(SELECT_CONVERSATION + " ORDER BY c.last_active_at DESC")
                .param("userId", userId)
                .query(ConversationRepository::mapConversation)
                .list();
    }

    /**
     * 单个会话视图。
     *
     * <b>{@code WHERE} 不能写成追加一个 {@code AND}。</b>{@code SELECT_CONVERSATION}
     * 以 {@code LEFT JOIN LATERAL (...) peer ON c.type = 'direct'} 结尾，
     * 直接拼接 {@code " AND c.id = :conversationId"} 会让这个条件落在 <b>JOIN 的
     * ON 子句</b>上而不是 WHERE 上——结果是"取该用户的<i>全部</i>会话，
     * 但只有 id 匹配的那个才关联 peer"。
     *
     * 这个缺陷此前不可见：唯一的调用方是红包与通话的成员校验，而它们跑在演示
     * 种子上，每个用户恰好只有少量会话且调用方只用 {@code isPresent()} 判断。
     * 直到会话创建路径开始用它返回<b>单个</b>会话视图，
     * {@code optional()} 才因为拿到多行而抛 {@code IncorrectResultSizeDataAccessException}。
     */
    public Optional<Conversation> findForUser(UUID conversationId, UUID userId) {
        return jdbcClient.sql(SELECT_CONVERSATION + " WHERE c.id = :conversationId")
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

    /** 是否为会话的有效成员（未退出），用于轻量鉴权 */
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

    /**
     * 一次查询同时判定"会话是否存在"与"是否为有效成员"。
     *
     * 鉴权此前需要两次往返（{@code exists} + {@code isActiveMember}），而它出现在
     * 每一次发消息、拉历史、领红包的开头——是全服务调用最频繁的查询。
     * 两个 {@code EXISTS} 子查询合并到一条语句里，往返减半。
     *
     * <b>刻意不加缓存</b>：这是权限判断。会话成员缓存（见 {@code ConversationMemberCache}）
     * 服务于消息扇出——把一条消息多投给刚退群的人，代价是一次多余的推送；
     * 而把鉴权结果缓存下来，代价是被踢出的人在整个 TTL 内还能读写会话。
     * 两者的失败后果不在一个量级，因此扇出走缓存，鉴权走数据库。
     */
    public MembershipStatus checkMembership(UUID conversationId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                                   SELECT 1 FROM conversations WHERE id = :conversationId
                               ) AS conversation_exists,
                               EXISTS (
                                   SELECT 1 FROM conversation_members
                                    WHERE conversation_id = :conversationId
                                      AND user_id = :userId
                                      AND left_at IS NULL
                               ) AS is_member
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query((rs, rowNum) -> {
                    if (rs.getBoolean("is_member")) {
                        return MembershipStatus.MEMBER;
                    }
                    return rs.getBoolean("conversation_exists")
                            ? MembershipStatus.NOT_A_MEMBER
                            : MembershipStatus.CONVERSATION_NOT_FOUND;
                })
                .single();
    }

    /**
     * 成员资格判定结果。
     *
     * 区分"会话不存在"与"不是成员"是有意的：前端依赖 NOT_A_MEMBER 区分
     * "你退群了"和"这个会话被删了"两种提示。
     */
    public enum MembershipStatus {
        MEMBER,
        NOT_A_MEMBER,
        CONVERSATION_NOT_FOUND
    }

    /** 有效成员 ID 列表，按入群先后排序，供消息扇出使用 */
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

    // ================================================================
    // 写路径
    //
    // 这一段在本次改造之前完全不存在：会话与成员全部来自演示种子，
    // 而种子在 prod profile 下不加载。于是生产环境注册出来的账号
    // 拿不到任何会话，也没有任何接口能创建一个——产品在 prod 下发不出
    // 第一条消息。这不是"群管理功能待补"，是主流程缺了一段。
    // ================================================================

    /**
     * 创建会话行。
     *
     * {@code lastMessage} 传入空串而非 NULL：契约把它声明为非空 string
     * （见 packages/contracts 的 conversationSchema），而新建会话此刻确实
     * 还没有任何消息。让数据库存 NULL 再指望每个读取方各自兜底，是把一个
     * 本可以在写入时消除的空值扩散到所有调用方。
     */
    public void insertConversation(UUID id, String type, String title, String avatar, UUID ownerId) {
        jdbcClient.sql("""
                        INSERT INTO conversations
                            (id, type, title, avatar, owner_id, next_seq, last_message, last_active_at)
                        VALUES
                            (:id, :type, :title, :avatar, :ownerId, 1, '', now())
                        """)
                .param("id", id)
                .param("type", type)
                .param("title", title)
                .param("avatar", avatar)
                .param("ownerId", ownerId)
                .update();
    }

    /**
     * 登记单聊的规范化唯一键，冲突时不报错。
     *
     * 主键 {@code (user_low_id, user_high_id)} 是"两人之间只有一个单聊"的
     * <b>唯一</b>保障——先查后写挡不住并发，约束才是正确性来源。
     *
     * <b>为什么用 ON CONFLICT DO NOTHING 而不是捕获 DuplicateKeyException</b>：
     * PostgreSQL 的唯一约束冲突是语句级错误，它会把<b>整个事务</b>置为 aborted，
     * 此后同一事务内的任何语句都返回 {@code 25P02 current transaction is aborted}。
     * 也就是说"捕获异常后回查已存在的记录"这个写法在 PostgreSQL 上不成立——
     * 那次回查自己就会失败（除非事先设置 SAVEPOINT，而 Spring 的声明式事务
     * 默认不会）。让冲突根本不抛异常，回查才有机会执行。
     *
     * @return 1 表示本次抢到了这对用户的单聊，0 表示已被并发的另一方登记
     */
    public int tryInsertDirectKey(UUID lowId, UUID highId, UUID conversationId) {
        return jdbcClient.sql("""
                        INSERT INTO direct_conversation_keys
                            (user_low_id, user_high_id, conversation_id)
                        VALUES (:lowId, :highId, :conversationId)
                        ON CONFLICT (user_low_id, user_high_id) DO NOTHING
                        """)
                .param("lowId", lowId)
                .param("highId", highId)
                .param("conversationId", conversationId)
                .update();
    }

    /**
     * 删除一个刚创建、尚未被任何行引用的会话。
     *
     * 只用于单聊创建时的并发退让：外键要求 {@code direct_conversation_keys}
     * 引用的会话必须先存在，所以必须先建会话行再抢唯一键；抢不到时这行会话
     * 就是垃圾，必须在同一事务内清掉，否则每一次并发冲突都留下一个没有成员、
     * 没有消息、任何人都看不到的会话。
     *
     * <b>不是通用的删除会话接口</b>：真正的会话删除涉及消息、红包、通话记录，
     * 那需要独立的归档流程。
     */
    public void deleteEmptyConversation(UUID conversationId) {
        jdbcClient.sql("""
                        DELETE FROM conversations
                         WHERE id = :id
                           AND NOT EXISTS (
                               SELECT 1 FROM conversation_members WHERE conversation_id = :id
                           )
                           AND NOT EXISTS (
                               SELECT 1 FROM messages WHERE conversation_id = :id
                           )
                        """)
                .param("id", conversationId)
                .update();
    }

    /** 查两人之间已有的单聊。调用方负责按字典序规范化入参 */
    public Optional<UUID> findDirectConversationId(UUID lowId, UUID highId) {
        return jdbcClient.sql("""
                        SELECT conversation_id FROM direct_conversation_keys
                        WHERE user_low_id = :lowId AND user_high_id = :highId
                        """)
                .param("lowId", lowId)
                .param("highId", highId)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("conversation_id")))
                .optional();
    }

    /**
     * 加入成员，已退出的成员会被"复活"。
     *
     * 退群是软删（{@code left_at} 置位）而非删行，因此重新入群必须是 UPDATE。
     * 这里用 {@code ON CONFLICT ... DO UPDATE ... WHERE left_at IS NOT NULL} 一次
     * 完成，返回 0 即表示"此人已经是有效成员"——把判断交给数据库而不是先查后写，
     * 并发加同一个人时不会出现两次都认为"他还不在"的窗口。
     *
     * 复活时 {@code join_seq} 与 {@code read_seq} 都重置为当前序号：
     * 退群期间产生的消息对他不可见，也不该显示成未读。
     *
     * @return 1 表示新增或复活，0 表示此人已在群内
     */
    public int addMember(UUID conversationId, UUID userId, String role, long joinSeq) {
        return jdbcClient.sql("""
                        INSERT INTO conversation_members
                            (conversation_id, user_id, role, joined_at, left_at, read_seq, join_seq)
                        VALUES
                            (:conversationId, :userId, :role, now(), NULL, :joinSeq, :joinSeq)
                        ON CONFLICT (conversation_id, user_id) DO UPDATE
                           SET left_at   = NULL,
                               joined_at = now(),
                               role      = EXCLUDED.role,
                               read_seq  = EXCLUDED.read_seq,
                               join_seq  = EXCLUDED.join_seq
                         WHERE conversation_members.left_at IS NOT NULL
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .param("role", role)
                .param("joinSeq", joinSeq)
                .update();
    }

    /**
     * 移出成员（软删）。
     *
     * 条件里带 {@code left_at IS NULL}，因此重复移出返回 0 而不是"成功"——
     * 调用方据此区分"确实移出了一个人"与"这个人本来就不在"，
     * 后者不应产生成员变更事件与审计记录。
     *
     * @return 1 表示确实移出，0 表示此人当前不是成员
     */
    public int removeMember(UUID conversationId, UUID userId) {
        return jdbcClient.sql("""
                        UPDATE conversation_members
                           SET left_at = now()
                         WHERE conversation_id = :conversationId
                           AND user_id = :userId
                           AND left_at IS NULL
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
    }

    /** 修改成员角色。只作用于有效成员，返回 0 表示此人不在群内 */
    public int updateMemberRole(UUID conversationId, UUID userId, String role) {
        return jdbcClient.sql("""
                        UPDATE conversation_members
                           SET role = :role
                         WHERE conversation_id = :conversationId
                           AND user_id = :userId
                           AND left_at IS NULL
                        """)
                .param("role", role)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
    }

    /** 有效成员的角色，不存在或已退出返回空 */
    public Optional<String> findActiveMemberRole(UUID conversationId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT role FROM conversation_members
                         WHERE conversation_id = :conversationId
                           AND user_id = :userId
                           AND left_at IS NULL
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(String.class)
                .optional();
    }

    /**
     * 成员的历史可见下界。
     *
     * 不是成员时返回空——调用方必须把它当作"无权查看"，而不是退化成 0。
     * 退化成 0 意味着鉴权失败时反而放开了全部历史。
     */
    public Optional<Long> findJoinSeq(UUID conversationId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT join_seq FROM conversation_members
                         WHERE conversation_id = :conversationId
                           AND user_id = :userId
                           AND left_at IS NULL
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .query(Long.class)
                .optional();
    }

    /** 有效成员数量，用于校验群人数上限等规则 */
    public int countActiveMembers(UUID conversationId) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM conversation_members
                         WHERE conversation_id = :conversationId AND left_at IS NULL
                        """)
                .param("conversationId", conversationId)
                .query(Integer.class)
                .single();
    }

    /** 成员列表（含角色），按入群先后排序 */
    public List<ConversationMember> listMembers(UUID conversationId) {
        return jdbcClient.sql("""
                        SELECT cm.user_id, cm.role, cm.joined_at,
                               u.username, u.display_name, u.avatar
                          FROM conversation_members cm
                          JOIN users u ON u.id = cm.user_id
                         WHERE cm.conversation_id = :conversationId
                           AND cm.left_at IS NULL
                         ORDER BY cm.joined_at, u.username
                        """)
                .param("conversationId", conversationId)
                .query((rs, rowNum) -> new ConversationMember(
                        rs.getString("user_id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("avatar"),
                        rs.getString("role"),
                        Mappers.instant(rs, "joined_at")))
                .list();
    }

    /**
     * 当前已分配的最大序号（{@code next_seq - 1}）。
     *
     * 新成员的 {@code join_seq} 取这个值：它之后产生的消息才对他可见。
     * 刻意<b>不</b>用 {@code allocateNextSeq}——那会自增序号，凭空制造一个
     * 没有消息占用的空洞，而客户端的补拉逻辑按 seq 连续性判断是否漏收。
     */
    public long currentSeq(UUID conversationId) {
        return jdbcClient.sql("SELECT next_seq - 1 FROM conversations WHERE id = :id")
                .param("id", conversationId)
                .query(Long.class)
                .single();
    }

    /** 转让群主时更新会话上的 owner_id，与成员角色一并变更 */
    public void updateOwner(UUID conversationId, UUID ownerId) {
        jdbcClient.sql("UPDATE conversations SET owner_id = :ownerId WHERE id = :id")
                .param("ownerId", ownerId)
                .param("id", conversationId)
                .update();
    }

    /** 修改群名称 */
    public void updateTitle(UUID conversationId, String title) {
        jdbcClient.sql("UPDATE conversations SET title = :title WHERE id = :id")
                .param("title", title)
                .param("id", conversationId)
                .update();
    }

    /** 行映射：单聊用对端昵称/头像替换会话标题与头像，标题为空时兜底为空串 */
    private static Conversation mapConversation(ResultSet rs, int rowNum) throws SQLException {
        boolean direct = "direct".equals(rs.getString("type"));
        String peerDisplayName = rs.getString("peer_display_name");
        String peerAvatar = rs.getString("peer_avatar");
        return new Conversation(
                rs.getString("id"),
                rs.getString("type"),
                direct && peerDisplayName != null ? peerDisplayName : nullToEmpty(rs.getString("title")),
                direct && peerAvatar != null ? peerAvatar : nullToEmpty(rs.getString("avatar")),
                readTextArray(rs, "member_ids"),
                // 契约把这三个字段声明为非空 string，而 title/avatar/last_message 在
                // 数据库里都是可空列。写入路径已经保证不写 NULL，这里的兜底针对的是
                // 迁移之前留下的行——让契约在读取侧也成立，而不是指望每个客户端各自处理。
                nullToEmpty(rs.getString("last_message")),
                Mappers.instant(rs, "last_active_at"),
                rs.getInt("unread_count")
        );
    }

    /** 空值兜底：契约把这些字段声明为非空 string，而数据库列本身可空 */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 读取 PostgreSQL 的 text 数组列；用完必须释放 JDBC Array，否则连接池会泄漏底层资源 */
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

    /**
     * 单聊唯一键的规范化顺序。
     *
     * <b>必须用无符号比较，不能用 {@link UUID#compareTo}。</b>
     *
     * {@code UUID.compareTo} 把两个 64 位分量当作<b>有符号</b> long 比较，
     * 而 PostgreSQL 的 uuid 类型按 16 字节做<b>无符号</b>字节序比较（memcmp 语义）。
     * 两者在首字节 ≥ 0x80 时得出相反的结论——于是 Java 认为 (a, b) 有序、
     * 数据库却认为 (b, a) 才有序，写入直接撞上 {@code chk_direct_key_order}。
     *
     * 随机 UUID 有大约一半的概率触发。<b>演示种子恰好掩盖了它</b>：
     * 种子用户的 ID 是 {@code 1111…}/{@code 2222…}/{@code 3333…}，首字节都远小于
     * 0x80，两种比较方式给出相同结果。这个缺陷是在用随机 UUID 建测试用户时
     * 才暴露出来的。
     *
     * 规范化收敛在这里而不是让每个调用方各自排序：服务端与测试各写一份的话，
     * 两份实现同时写错就测不出任何问题。
     */
    public record DirectKey(UUID lowId, UUID highId) {
        /** 按无符号顺序规范化两个 ID 组成唯一键，保证与数据库侧的排序一致 */
        public static DirectKey of(UUID a, UUID b) {
            return compareUnsigned(a, b) < 0 ? new DirectKey(a, b) : new DirectKey(b, a);
        }

        /** 与 PostgreSQL uuid_cmp 一致的顺序 */
        private static int compareUnsigned(UUID a, UUID b) {
            int high = Long.compareUnsigned(
                    a.getMostSignificantBits(), b.getMostSignificantBits());
            return high != 0
                    ? high
                    : Long.compareUnsigned(
                            a.getLeastSignificantBits(), b.getLeastSignificantBits());
        }
    }

    /**
     * 会话成员视图，对外暴露。
     *
     * 带上 username/displayName/avatar 是为了让"查看群成员"只需一次请求——
     * 只返回 userId 会迫使客户端再去拉一遍用户列表，而那个接口目前返回全站用户。
     */
    public record ConversationMember(
            String userId,
            String username,
            String displayName,
            String avatar,
            String role,
            Instant joinedAt
    ) {
    }
}
