package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.DomainResults.MembershipChange;
import com.openim.server.domain.MemberRole;
import com.openim.server.domain.Requests.CreateGroupConversation;
import com.openim.server.domain.repository.AuditRepository;
import com.openim.server.domain.repository.ConversationRepository;
import com.openim.server.domain.repository.ConversationRepository.ConversationMember;
import com.openim.server.domain.repository.ConversationRepository.ConversationMeta;
import com.openim.server.domain.repository.ConversationRepository.DirectKey;
import com.openim.server.domain.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 会话的创建、成员管理与关系校验
 *
 * 所有涉及会话资源的操作都必须先经过 {@link #requireMembership}，
 * 权限判断以服务端成员关系为准，不信任客户端传入的任何角色信息。
 *
 * 成员关系读取经过 {@link ConversationMemberCache}：它同时是消息投递的扇出路径
 * 和每个会话类请求的鉴权路径，是全服务被调用最频繁的查询。
 *
 * <h2>本轮补上的能力</h2>
 *
 * 在此之前这个类只有查询和校验，没有任何写入——会话与成员全部来自演示种子，
 * 而种子在 {@code prod} profile 下不加载。于是生产环境的用户注册完成后拿到一个
 * 空会话列表，且没有任何接口能创建会话：<b>产品在 prod 下发不出第一条消息</b>。
 * 这不是"群管理待补"，是主流程缺了一段，而演示数据恰好把它盖住了——
 * 149 个测试全绿，E2E 也全绿，因为它们都用种子里已存在的会话。
 */
@Service
public class ConversationService {

    /**
     * 单个群的成员上限默认值，与 {@code packages/contracts} 的 MAX_GROUP_MEMBERS 一致。
     *
     * 这个数字约束的是<b>扇出成本</b>而非存储：一条群消息要投递给每个在线成员，
     * 成员数直接决定单条消息的放大倍数。500 是在当前架构（会话成员缓存 +
     * 每实例独立消费组）下不需要额外分片就能支撑的量级。
     *
     * 需要更大的群（万人群）不是把这个值调大就能解决的——那需要读扩散改写扩散、
     * 成员列表分页、消息按需拉取，属于另一个量级的设计。<b>调大它不会让系统
     * 支持更大的群，只会让扇出成本超出压测过的范围。</b>
     */
    public static final int DEFAULT_MAX_GROUP_MEMBERS = 500;

    /**
     * 单聊在数据库里的兜底标题与头像。
     *
     * 单聊的展示名取<b>对端</b>的昵称（见 {@code SELECT_CONVERSATION} 的 peer 关联），
     * 所以这两个值正常永远不会被读到。但 conversations.title/avatar 是可空列，而契约
     * 把它们声明为非空 string——写入兜底值使"数据库里没有 NULL"成为不变量，
     * 而不是依赖每个读取方各自处理空值。
     */
    private static final String DIRECT_TITLE = "单聊";
    private static final String DIRECT_AVATAR = "对";

    private final ConversationRepository conversationRepository;
    private final ConversationMemberCache memberCache;
    private final UserRepository userRepository;
    private final AuditRepository auditRepository;
    private final int maxGroupMembers;

    public ConversationService(
            ConversationRepository conversationRepository,
            ConversationMemberCache memberCache,
            UserRepository userRepository,
            AuditRepository auditRepository,
            @Value("${openim.conversation.max-group-members:" + DEFAULT_MAX_GROUP_MEMBERS + "}")
            int maxGroupMembers
    ) {
        this.conversationRepository = conversationRepository;
        this.memberCache = memberCache;
        this.userRepository = userRepository;
        this.auditRepository = auditRepository;
        this.maxGroupMembers = maxGroupMembers;
    }

    /** 当前生效的群成员上限，供接口层给出准确的错误信息 */
    public int maxGroupMembers() {
        return maxGroupMembers;
    }

    /** 我的会话列表，按最近活跃时间倒序。已退群/被移出的会话不再出现在结果里 */
    @Transactional(readOnly = true)
    public List<Conversation> listConversations(UUID userId) {
        return conversationRepository.listForUser(userId);
    }

    /**
     * 单个会话视图。
     *
     * 成员变更事件只带 conversationId，客户端据此拉取这一个会话。没有这个入口的话
     * 每次变更都要重新拉全量会话列表——一个 500 人群加一个人就是 500 次全量查询。
     *
     * 先 {@code requireMembership} 再取视图，是为了区分"会话不存在"（404）与
     * "你不是成员"（403）。直接用视图查询的话两者都是空结果，只能一律报 404，
     * 而前端依赖这个区别提示"你已退群"还是"会话已删除"。
     */
    @Transactional(readOnly = true)
    public Conversation getConversation(UUID conversationId, UUID userId) {
        requireMembership(conversationId, userId);
        return requireView(conversationId, userId);
    }

    /**
     * 校验用户是会话的有效成员。
     *
     * 会话不存在与无权访问返回不同状态码，与迁移前的行为保持一致：
     * 前端依赖 NOT_A_MEMBER 区分"退群了"和"会话被删了"两种提示。
     *
     * <b>为什么这里不走缓存</b>：这是权限判断，而权限判断的陈旧代价与扇出不同。
     * 扇出用陈旧成员表，后果是给刚退群的人多推一条消息；鉴权用陈旧成员表，
     * 后果是被踢出的人在整个 TTL 内仍能读写会话内容。后者是越权，不是体验瑕疵。
     *
     * 性能上改为单次查询（{@link ConversationRepository#checkMembership} 用两个
     * EXISTS 子查询合并了原先的 exists + isActiveMember 两次往返），
     * 因此不加缓存也比改造前更快。
     */
    public void requireMembership(UUID conversationId, UUID userId) {
        switch (conversationRepository.checkMembership(conversationId, userId)) {
            case MEMBER -> {
                // 通过
            }
            case CONVERSATION_NOT_FOUND -> throw DomainErrors.notFound("会话不存在");
            case NOT_A_MEMBER -> throw DomainErrors.forbidden("NOT_A_MEMBER", "你不是该会话成员");
        }
    }

    /** 会话元数据（类型、人数等），先做成员鉴权。需要元数据的业务分支都从这里取 */
    @Transactional(readOnly = true)
    public ConversationMeta requireMeta(UUID conversationId, UUID userId) {
        requireMembership(conversationId, userId);
        return conversationRepository.findMeta(conversationId)
                .orElseThrow(() -> DomainErrors.notFound("会话不存在"));
    }

    /**
     * 会话的有效成员，用于实时事件扇出。
     *
     * 不加 {@code @Transactional}：缓存命中时不接触数据库，为此开启一个事务
     * 只会白占一个连接池槽位——而这条路径的调用频率正是引入缓存的原因。
     */
    public List<UUID> listActiveMemberIds(UUID conversationId) {
        return memberCache.listActiveMemberIds(conversationId);
    }

    /**
     * 会话成员变更后失效缓存。
     *
     * 每一个写 {@code conversation_members} 的事务都必须调用它，否则新成员收不到
     * 消息、被移出的成员仍能收到，且症状会延迟一个 TTL 才消失，极难与"消息投递
     * 本身出问题"区分开。本类中所有变更成员的方法都已调用；新增变更路径时
     * 必须一并调用。
     *
     * 失效在事务提交后执行（见 {@link ConversationMemberCache#invalidate}），
     * 避免其他实例回源读到未提交的旧成员表。
     */
    public void invalidateMembers(UUID conversationId) {
        memberCache.invalidate(conversationId);
    }

    /** 推进已读游标。未读数由游标推导，因此这是唯一需要写入的已读状态 */
    @Transactional
    public void markRead(UUID conversationId, UUID userId, long readSeq) {
        requireMembership(conversationId, userId);
        conversationRepository.advanceReadSeq(conversationId, userId, Math.max(0, readSeq));
    }

    /**
     * 成员的历史可见下界。
     *
     * 不是成员时抛 403 而不是返回 0：返回 0 意味着鉴权失败反而放开了全部历史。
     */
    public long requireJoinSeq(UUID conversationId, UUID userId) {
        return conversationRepository.findJoinSeq(conversationId, userId)
                .orElseThrow(() -> {
                    // 区分"会话不存在"与"不是成员"，与 requireMembership 的语义一致
                    requireMembership(conversationId, userId);
                    // requireMembership 通过却查不到 join_seq 是不可能的（同一张表、
                    // 同一个条件）。真出现说明成员行在两次查询之间被删了，按无权处理。
                    return DomainErrors.forbidden("NOT_A_MEMBER", "你不是该会话成员");
                });
    }

    // ================================================================
    // 创建
    // ================================================================

    /**
     * 创建或复用与某人的单聊。
     *
     * <b>幂等而不是报冲突</b>：这个接口的语义是"打开与某人的对话"。第二次点开
     * 同一个人时返回已存在的会话，而不是 409——让客户端为了打开一个对话
     * 先试创建、失败再去列表里找，是把服务端能一步做完的事推给每个客户端。
     *
     * <b>并发安全</b>：两个人同时点开对方时，唯一键 {@code (user_low_id, user_high_id)}
     * 只允许一方登记成功。抢不到的一方删掉自己刚建的空会话行并回查对方建的那个，
     * 双方最终拿到同一个会话。这里刻意不用"捕获唯一约束异常再回查"——
     * PostgreSQL 的约束冲突会让整个事务变为 aborted，那次回查自己就会失败
     * （详见 {@link ConversationRepository#tryInsertDirectKey}）。
     */
    @Transactional
    public Conversation createDirectConversation(UUID actorId, String peerIdRaw) {
        UUID peerId = DomainErrors.parseId(peerIdRaw, "用户");
        if (actorId.equals(peerId)) {
            throw DomainErrors.badRequest("SELF_CONVERSATION", "不能与自己创建单聊");
        }
        if (!userRepository.existsActive(peerId)) {
            throw DomainErrors.notFound("用户不存在");
        }

        // 规范化顺序，与 chk_direct_key_order 约束一致。
        // 注意 DirectKey 用的是无符号比较——UUID.compareTo 与 PostgreSQL 的 uuid
        // 排序在首字节 ≥ 0x80 时结论相反，详见 DirectKey 的注释。
        DirectKey key = DirectKey.of(actorId, peerId);
        UUID lowId = key.lowId();
        UUID highId = key.highId();

        UUID existing = conversationRepository.findDirectConversationId(lowId, highId).orElse(null);
        if (existing != null) {
            return requireView(existing, actorId);
        }

        UUID conversationId = UUID.randomUUID();
        // 必须先建会话行：direct_conversation_keys.conversation_id 有外键
        conversationRepository.insertConversation(
                conversationId, "direct", DIRECT_TITLE, DIRECT_AVATAR, null);

        if (conversationRepository.tryInsertDirectKey(lowId, highId, conversationId) == 0) {
            // 并发的另一方先登记了。刚建的会话此刻没有成员也没有消息，清掉它。
            conversationRepository.deleteEmptyConversation(conversationId);
            UUID winner = conversationRepository.findDirectConversationId(lowId, highId)
                    .orElseThrow(() -> DomainErrors.conflict(
                            "CONVERSATION_CONFLICT", "会话创建冲突，请重试"));
            return requireView(winner, actorId);
        }

        // 单聊没有管理者，两人都是 MEMBER；join_seq 为 0——会话是新建的，没有历史
        conversationRepository.addMember(conversationId, actorId, MemberRole.MEMBER.name(), 0);
        conversationRepository.addMember(conversationId, peerId, MemberRole.MEMBER.name(), 0);
        invalidateMembers(conversationId);

        auditRepository.record("USER", actorId, "conversation.created",
                "conversation", conversationId, null,
                Map.of("type", "direct", "peerId", peerId.toString()));

        return requireView(conversationId, actorId);
    }

    /**
     * 创建群聊。创建者成为 OWNER，其余成员为 MEMBER。
     *
     * 被邀请者<b>直接入群</b>，不需要确认。这是一个产品决策而非技术约束：
     * 需要"受邀确认"时应当引入待处理邀请表，而不是让加人接口静默地什么都不做。
     */
    @Transactional
    public Conversation createGroupConversation(UUID actorId, CreateGroupConversation input) {
        // 去重并剔除创建者：他必然是 OWNER，出现在 memberIds 里不该覆盖成 MEMBER
        Set<UUID> invitees = parseUserIds(input.memberIds());
        invitees.remove(actorId);
        if (invitees.isEmpty()) {
            throw DomainErrors.badRequest("EMPTY_GROUP", "群聊至少需要一位其他成员");
        }
        requireAllActive(invitees);

        int total = invitees.size() + 1;
        if (total > maxGroupMembers) {
            throw DomainErrors.badRequest("GROUP_MEMBER_LIMIT",
                    "群成员不能超过 " + maxGroupMembers + " 人");
        }

        String title = input.title().trim();
        if (title.isEmpty()) {
            // @NotBlank 允许 " "，而 chk_group_has_title 只检查非 NULL
            throw DomainErrors.validation("群名称不能为空");
        }

        UUID conversationId = UUID.randomUUID();
        conversationRepository.insertConversation(
                conversationId, "group", title, avatarFor(input.avatar(), title), actorId);
        conversationRepository.addMember(conversationId, actorId, MemberRole.OWNER.name(), 0);
        for (UUID invitee : invitees) {
            conversationRepository.addMember(conversationId, invitee, MemberRole.MEMBER.name(), 0);
        }
        invalidateMembers(conversationId);

        auditRepository.record("USER", actorId, "conversation.created",
                "conversation", conversationId, null,
                Map.of("type", "group", "memberCount", total));

        return requireView(conversationId, actorId);
    }

    // ================================================================
    // 成员管理
    // ================================================================

    /** 成员列表（含角色）。任何有效成员都可以查看 */
    @Transactional(readOnly = true)
    public List<ConversationMember> listMembers(UUID conversationId, UUID actorId) {
        requireMembership(conversationId, actorId);
        return conversationRepository.listMembers(conversationId);
    }

    /**
     * 批量加人。
     *
     * 已在群内的用户被静默跳过而不是报错：批量操作里"其中一个已经在了"不该让
     * 另外九个也失败，而重复邀请同一个人本身是无害的。真正加入的人由返回值给出，
     * 只有他们会触发成员变更事件与审计。
     */
    @Transactional
    public MembershipChange addMembers(UUID conversationId, UUID actorId, List<String> userIdsRaw) {
        MemberRole actorRole = requireGroupRole(conversationId, actorId);
        if (!actorRole.canAddMembers()) {
            throw DomainErrors.forbidden("INSUFFICIENT_ROLE", "只有群主或管理员可以添加成员");
        }

        Set<UUID> candidates = parseUserIds(userIdsRaw);
        requireAllActive(candidates);

        // 上限校验必须在写入之前按"当前人数 + 本次新增"计算。逐个加人时各自
        // 检查一遍上限也拦不住一次请求里带 100 个人的情况。
        int current = conversationRepository.countActiveMembers(conversationId);
        if (current + candidates.size() > maxGroupMembers) {
            throw DomainErrors.badRequest("GROUP_MEMBER_LIMIT",
                    "群成员不能超过 " + maxGroupMembers + " 人");
        }

        // 新成员的历史可见下界取当前序号：他们看不到入群前的消息。
        // 在循环外取一次——循环内取会让先后加入的人得到不同的下界，
        // 而他们是同一次操作加进来的。
        long joinSeq = conversationRepository.currentSeq(conversationId);

        List<String> joined = new ArrayList<>();
        for (UUID candidate : candidates) {
            if (conversationRepository.addMember(
                    conversationId, candidate, MemberRole.MEMBER.name(), joinSeq) == 1) {
                joined.add(candidate.toString());
            }
        }

        if (joined.isEmpty()) {
            // 全部已在群内，没有任何变更：不失效缓存、不写审计、不发事件
            return MembershipChange.joined(conversationId.toString(), List.of());
        }

        invalidateMembers(conversationId);
        auditRepository.record("USER", actorId, "conversation.member_added",
                "conversation", conversationId, null,
                Map.of("joined", joined, "joinSeq", joinSeq));

        return MembershipChange.joined(conversationId.toString(), joined);
    }

    /**
     * 移出成员。
     *
     * 权限矩阵见 {@link MemberRole#canRemove}。移出自己不走这里——那是退群，
     * 有独立的规则（群主不能直接退群）。
     */
    @Transactional
    public MembershipChange removeMember(UUID conversationId, UUID actorId, String targetIdRaw) {
        UUID targetId = DomainErrors.parseId(targetIdRaw, "用户");
        MemberRole actorRole = requireGroupRole(conversationId, actorId);

        if (actorId.equals(targetId)) {
            throw DomainErrors.badRequest("USE_LEAVE_ENDPOINT", "退出会话请使用退群接口");
        }

        MemberRole targetRole = MemberRole
                .parse(conversationRepository.findActiveMemberRole(conversationId, targetId)
                        .orElseThrow(() -> DomainErrors.notFound("该用户不是会话成员")))
                .orElseThrow(() -> DomainErrors.conflict("INVALID_ROLE", "成员角色数据异常"));

        if (!actorRole.canRemove(targetRole)) {
            throw DomainErrors.forbidden("INSUFFICIENT_ROLE",
                    targetRole == MemberRole.OWNER
                            ? "不能移出群主"
                            : "权限不足，无法移出该成员");
        }

        if (conversationRepository.removeMember(conversationId, targetId) == 0) {
            // 并发下另一个请求已经把他移出了。幂等地当作成功，但不产生事件——
            // 事件已经由那个请求发过了。
            return MembershipChange.joined(conversationId.toString(), List.of());
        }

        invalidateMembers(conversationId);
        auditRepository.record("USER", actorId, "conversation.member_removed",
                "conversation", conversationId, null,
                Map.of("targetId", targetId.toString(), "targetRole", targetRole.name()));

        return MembershipChange.removed(conversationId.toString(), targetId.toString());
    }

    /**
     * 主动退群。
     *
     * 群主不能直接退出（{@link MemberRole#canLeave}）：群会失去唯一的管理者，
     * 此后没人能加人、改名或指派管理员。必须先转让。
     *
     * <b>单聊不能退出</b>：单聊没有"离开"的语义——对方仍会看到这个会话并继续
     * 发消息，而消息投递按成员表扇出，退出的一方从此收不到任何消息却还能在
     * 列表里看到它（会话视图 JOIN 的是自己的成员行）。要"不再接收"应当是
     * 屏蔽或删除会话，那是另一个功能。
     */
    @Transactional
    public MembershipChange leave(UUID conversationId, UUID actorId) {
        MemberRole role = requireGroupRole(conversationId, actorId);
        if (!role.canLeave()) {
            throw DomainErrors.badRequest("OWNER_MUST_TRANSFER",
                    "群主需要先转让群主身份才能退群");
        }

        if (conversationRepository.removeMember(conversationId, actorId) == 0) {
            throw DomainErrors.forbidden("NOT_A_MEMBER", "你不是该会话成员");
        }

        invalidateMembers(conversationId);
        auditRepository.record("USER", actorId, "conversation.member_left",
                "conversation", conversationId, null,
                Map.of("role", role.name()));

        return MembershipChange.removed(conversationId.toString(), actorId.toString());
    }

    /**
     * 指派或撤销管理员。
     *
     * 只接受 ADMIN 与 MEMBER。转让群主是另一个端点——把它塞进这里意味着
     * 一个方法在 role 取值不同时走两条差别极大的分支（一条只改一行，
     * 另一条要改三处并降级调用者自己），那是权限漏洞的典型藏身处。
     */
    @Transactional
    public void updateMemberRole(
            UUID conversationId,
            UUID actorId,
            String targetIdRaw,
            String roleRaw
    ) {
        UUID targetId = DomainErrors.parseId(targetIdRaw, "用户");
        MemberRole newRole = MemberRole.parse(roleRaw)
                .orElseThrow(() -> DomainErrors.validation("角色取值无效"));
        if (newRole == MemberRole.OWNER) {
            throw DomainErrors.badRequest("USE_TRANSFER_ENDPOINT", "转让群主请使用专用接口");
        }

        MemberRole actorRole = requireGroupRole(conversationId, actorId);
        if (!actorRole.canAssignRoles()) {
            throw DomainErrors.forbidden("INSUFFICIENT_ROLE", "只有群主可以变更成员角色");
        }
        if (actorId.equals(targetId)) {
            // 群主给自己设 ADMIN 等于放弃群主身份而群里不再有 OWNER
            throw DomainErrors.badRequest("CANNOT_CHANGE_OWN_ROLE", "不能变更自己的角色");
        }

        MemberRole targetRole = MemberRole
                .parse(conversationRepository.findActiveMemberRole(conversationId, targetId)
                        .orElseThrow(() -> DomainErrors.notFound("该用户不是会话成员")))
                .orElseThrow(() -> DomainErrors.conflict("INVALID_ROLE", "成员角色数据异常"));
        if (targetRole == MemberRole.OWNER) {
            throw DomainErrors.forbidden("INSUFFICIENT_ROLE", "不能变更群主的角色");
        }
        if (targetRole == newRole) {
            return;
        }

        conversationRepository.updateMemberRole(conversationId, targetId, newRole.name());
        // 角色不参与消息扇出（扇出只看"是不是有效成员"），因此这里不必失效
        // 成员缓存——缓存里存的是 ID 列表，内容没有变化。
        auditRepository.record("USER", actorId, "conversation.role_changed",
                "conversation", conversationId, null,
                Map.of("targetId", targetId.toString(),
                        "from", targetRole.name(),
                        "to", newRole.name()));
    }

    /**
     * 转让群主。
     *
     * 原群主降为 ADMIN 而不是 MEMBER：转让通常发生在"我要退群"或"换人管理"，
     * 降到 MEMBER 会让原群主连协助管理都做不到，而这一步是不可逆的。
     *
     * 三处写入必须在同一事务内：新群主的角色、原群主的角色、
     * {@code conversations.owner_id}。只改其中一部分会得到"两个 OWNER"或
     * "owner_id 指向一个 MEMBER"这类无法通过接口修复的状态。
     */
    @Transactional
    public void transferOwnership(UUID conversationId, UUID actorId, String targetIdRaw) {
        UUID targetId = DomainErrors.parseId(targetIdRaw, "用户");
        MemberRole actorRole = requireGroupRole(conversationId, actorId);
        if (!actorRole.canTransferOwnership()) {
            throw DomainErrors.forbidden("INSUFFICIENT_ROLE", "只有群主可以转让群主身份");
        }
        if (actorId.equals(targetId)) {
            throw DomainErrors.badRequest("ALREADY_OWNER", "你已经是群主");
        }
        if (conversationRepository.findActiveMemberRole(conversationId, targetId).isEmpty()) {
            throw DomainErrors.notFound("该用户不是会话成员");
        }

        conversationRepository.updateMemberRole(conversationId, targetId, MemberRole.OWNER.name());
        conversationRepository.updateMemberRole(conversationId, actorId, MemberRole.ADMIN.name());
        conversationRepository.updateOwner(conversationId, targetId);

        auditRepository.record("USER", actorId, "conversation.owner_transferred",
                "conversation", conversationId, null,
                Map.of("targetId", targetId.toString()));
    }

    /** 修改群资料。群主与管理员可改 */
    @Transactional
    public Conversation updateProfile(UUID conversationId, UUID actorId, String titleRaw) {
        MemberRole actorRole = requireGroupRole(conversationId, actorId);
        if (!actorRole.canEditProfile()) {
            throw DomainErrors.forbidden("INSUFFICIENT_ROLE", "只有群主或管理员可以修改群资料");
        }
        String title = titleRaw.trim();
        if (title.isEmpty()) {
            throw DomainErrors.validation("群名称不能为空");
        }

        conversationRepository.updateTitle(conversationId, title);
        auditRepository.record("USER", actorId, "conversation.profile_updated",
                "conversation", conversationId, null, Map.of("title", title));
        return requireView(conversationId, actorId);
    }

    // ================================================================
    // 内部工具
    // ================================================================

    /**
     * 取操作者在<b>群聊</b>中的角色，顺带拒绝对单聊的成员管理。
     *
     * 单聊的两个成员都是 MEMBER，没有管理者。若不在这里拦住，
     * 单聊会走进群聊的权限判断并因为"MEMBER 没有权限"而返回 403——
     * 那个错误信息（"只有群主或管理员可以…"）对单聊毫无意义，
     * 会让人以为是权限配置问题而不是"这个操作对单聊不存在"。
     */
    private MemberRole requireGroupRole(UUID conversationId, UUID actorId) {
        ConversationMeta meta = requireMeta(conversationId, actorId);
        if (!"group".equals(meta.type())) {
            throw DomainErrors.badRequest("NOT_A_GROUP", "该操作仅适用于群聊");
        }
        return MemberRole
                .parse(conversationRepository.findActiveMemberRole(conversationId, actorId)
                        .orElseThrow(() -> DomainErrors.forbidden("NOT_A_MEMBER", "你不是该会话成员")))
                .orElseThrow(() -> DomainErrors.conflict("INVALID_ROLE", "成员角色数据异常"));
    }

    /** 会话视图。查不到说明调用者不是成员——创建/变更之后这不该发生 */
    private Conversation requireView(UUID conversationId, UUID userId) {
        return conversationRepository.findForUser(conversationId, userId)
                .orElseThrow(() -> DomainErrors.notFound("会话不存在"));
    }

    /** 解析并去重。保持传入顺序，便于错误信息与审计记录可复现 */
    private Set<UUID> parseUserIds(List<String> raw) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (String value : raw) {
            ids.add(DomainErrors.parseId(value, "用户"));
        }
        return ids;
    }

    /**
     * 校验这批用户都存在且可用。
     *
     * 一次查询而不是逐个 {@code existsActive}：这个校验发生在持有会话行锁的
     * 事务里，往返次数直接决定锁的持有时长。
     */
    private void requireAllActive(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Set<UUID> active = userRepository.findActiveIds(ids);
        if (active.size() == ids.size()) {
            return;
        }
        // 指出具体是谁不存在。"其中有人不存在"对调用方没有任何帮助——
        // 客户端无法据此提示用户，只能把整批重试一遍。
        String missing = ids.stream()
                .filter(id -> !active.contains(id))
                .map(UUID::toString)
                .findFirst()
                .orElse("unknown");
        throw DomainErrors.notFound("用户不存在：" + missing);
    }

    /**
     * 群头像是一个短字符标签（varchar(16)）。未指定时取标题首字符。
     *
     * 按 code point 而不是 char 截取：{@code substring(0, 1)} 会把 emoji 和
     * 部分汉字（增补平面）的代理对切成一半，存进数据库就是一个乱码字符。
     */
    private String avatarFor(String avatar, String title) {
        if (avatar != null && !avatar.isBlank()) {
            return avatar.trim();
        }
        return title.substring(0, title.offsetByCodePoints(0, 1));
    }
}
