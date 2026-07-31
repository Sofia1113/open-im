package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.MemberRole;
import com.openim.server.domain.Requests.CreateGroupConversation;
import com.openim.server.domain.Requests.SendMessage;
import com.openim.server.domain.repository.ConversationRepository;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import com.openim.server.support.DemoUsers;
import com.openim.server.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话创建与成员管理
 *
 * <b>这个测试类覆盖的是此前完全不存在的一段主流程。</b>在它之前，服务端没有任何
 * 创建会话的写路径——会话全部来自演示种子，而种子在 {@code prod} profile 下不加载。
 * 结果是生产环境注册出来的账号打开空列表、无法与任何人建立对话、一条消息都发不出去，
 * 而当时 149 个 Java 测试与 14 个 E2E 全绿：它们用的都是种子里已存在的会话。
 *
 * 因此这里刻意<b>不复用</b> {@code DemoUsers.GROUP_CONVERSATION} 之类的种子会话，
 * 每个用例都从"创建"开始。种子会话能掩盖的正是这一类缺陷。
 */
@SpringBootTest
class ConversationManagementIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private JdbcClient jdbcClient;

    // ================================================================
    // 单聊
    // ================================================================

    /**
     * 单聊是幂等的：第二次"打开与某人的对话"返回同一个会话。
     *
     * 报 409 让客户端自己去列表里找，是把服务端能一步做完的事推给每个客户端。
     */
    @Test
    void openingADirectConversationTwiceReturnsTheSameOne() {
        UUID alice = createUser("direct-a");
        UUID bob = createUser("direct-b");

        Conversation first = conversationService.createDirectConversation(alice, bob.toString());
        Conversation second = conversationService.createDirectConversation(alice, bob.toString());

        assertThat(second.id()).isEqualTo(first.id());
        // 反向也必须命中同一个：唯一键按字典序规范化，与谁先发起无关
        Conversation reversed = conversationService.createDirectConversation(bob, alice.toString());
        assertThat(reversed.id()).isEqualTo(first.id());

        assertThat(countDirectKeys(alice, bob)).isEqualTo(1);
    }

    /**
     * 两人同时点开对方：必须收敛到<b>同一个</b>会话，且不留下孤立的会话行。
     *
     * 这是这段代码最容易写错的地方。常见写法是"先查有没有，没有就建"，
     * 而它在并发下会建出两个会话——两个人各自看到一个，互相发的消息对方都收不到。
     *
     * 唯一键挡住了第二个，但正确处理"抢不到"这条分支同样重要：
     * PostgreSQL 的约束冲突会让整个事务进入 aborted，此后同一事务里的任何查询都
     * 返回 25P02。所以实现里用的是 {@code ON CONFLICT DO NOTHING} + 回查，
     * 而不是"捕获 DuplicateKeyException 再查一次"——后者在 PostgreSQL 上不成立。
     */
    @Test
    void concurrentDirectCreationConvergesToOneConversation() throws Exception {
        UUID alice = createUser("race-a");
        UUID bob = createUser("race-b");

        List<Conversation> results = runConcurrently(List.of(
                () -> conversationService.createDirectConversation(alice, bob.toString()),
                () -> conversationService.createDirectConversation(bob, alice.toString())
        ));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id())
                .as("并发创建必须收敛到同一个会话，否则双方各说各话")
                .isEqualTo(results.get(1).id());

        assertThat(countDirectKeys(alice, bob)).isEqualTo(1);
        // 抢输的那一方建过一个会话行，必须在同一事务里清掉，
        // 否则每次并发冲突都留下一个没有成员、谁也看不到的会话
        assertThat(countConversationsWithoutMembers())
                .as("不应留下没有成员的孤立会话")
                .isZero();
    }

    @Test
    void directConversationRejectsSelfAndUnknownPeers() {
        UUID alice = createUser("solo-a");

        assertThatThrownBy(() -> conversationService.createDirectConversation(alice, alice.toString()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能与自己");

        assertThatThrownBy(() -> conversationService.createDirectConversation(
                alice, UUID.randomUUID().toString()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("用户不存在");
    }

    /** 新建的会话必须能立刻发消息——这正是此前 prod 下走不通的那一步 */
    @Test
    void aFreshlyCreatedDirectConversationCanCarryMessages() {
        UUID alice = createUser("chat-a");
        UUID bob = createUser("chat-b");

        Conversation conversation = conversationService.createDirectConversation(alice, bob.toString());
        ImMessage message = messageService.createMessage(alice, new SendMessage(
                conversation.id(), "client-" + UUID.randomUUID(), "text", Map.of("text", "第一条消息")));

        assertThat(message.seq()).isEqualTo(1);
        assertThat(messageService.listMessages(UUID.fromString(conversation.id()), bob, 0))
                .extracting(ImMessage::id)
                .containsExactly(message.id());
    }

    /**
     * 单聊没有管理者，所有成员管理操作都不适用。
     *
     * 若不显式拦住，单聊成员（角色是 MEMBER）会走进群聊的权限判断并因"权限不足"
     * 被拒——那个错误信息会让人以为是角色配置问题，而实际上这个操作对单聊
     * 根本不存在。
     */
    @Test
    void directConversationsRejectMemberManagement() {
        UUID alice = createUser("nogroup-a");
        UUID bob = createUser("nogroup-b");
        UUID carol = createUser("nogroup-c");
        Conversation direct = conversationService.createDirectConversation(alice, bob.toString());
        UUID id = UUID.fromString(direct.id());

        assertThatThrownBy(() -> conversationService.addMembers(id, alice, List.of(carol.toString())))
                .hasMessageContaining("仅适用于群聊");
        assertThatThrownBy(() -> conversationService.removeMember(id, alice, bob.toString()))
                .hasMessageContaining("仅适用于群聊");
        assertThatThrownBy(() -> conversationService.leave(id, alice))
                .hasMessageContaining("仅适用于群聊");
        assertThatThrownBy(() -> conversationService.transferOwnership(id, alice, bob.toString()))
                .hasMessageContaining("仅适用于群聊");
    }

    // ================================================================
    // 群聊创建
    // ================================================================

    @Test
    void groupCreatorBecomesOwnerAndOthersBecomeMembers() {
        UUID owner = createUser("grp-owner");
        UUID member = createUser("grp-member");

        Conversation group = conversationService.createGroupConversation(owner,
                new CreateGroupConversation("测试群", null, List.of(member.toString())));
        UUID id = UUID.fromString(group.id());

        assertThat(group.type()).isEqualTo("group");
        assertThat(group.title()).isEqualTo("测试群");
        assertThat(group.avatar()).as("未指定头像时取标题首字符").isEqualTo("测");
        assertThat(roleOf(id, owner)).isEqualTo("OWNER");
        assertThat(roleOf(id, member)).isEqualTo("MEMBER");
        assertThat(ownerIdOf(id)).isEqualTo(owner);
    }

    /**
     * 创建者出现在 memberIds 里不该把自己降级成 MEMBER。
     *
     * 客户端很容易把"当前用户 + 选中的人"整个提交上来，那时列表里就有创建者。
     */
    @Test
    void listingTheCreatorAmongMembersDoesNotDowngradeThem() {
        UUID owner = createUser("dedupe-owner");
        UUID member = createUser("dedupe-member");

        Conversation group = conversationService.createGroupConversation(owner,
                new CreateGroupConversation("去重群", "群",
                        List.of(owner.toString(), member.toString(), member.toString())));
        UUID id = UUID.fromString(group.id());

        assertThat(roleOf(id, owner)).isEqualTo("OWNER");
        assertThat(conversationRepository.countActiveMembers(id))
                .as("重复的 ID 应被去重")
                .isEqualTo(2);
    }

    @Test
    void groupCreationRejectsUnknownMembersAndEmptyRosters() {
        UUID owner = createUser("reject-owner");

        assertThatThrownBy(() -> conversationService.createGroupConversation(owner,
                new CreateGroupConversation("群", null, List.of(UUID.randomUUID().toString()))))
                .hasMessageContaining("用户不存在");

        // 只有自己：去重后邀请列表为空
        assertThatThrownBy(() -> conversationService.createGroupConversation(owner,
                new CreateGroupConversation("群", null, List.of(owner.toString()))))
                .hasMessageContaining("至少需要一位");
    }

    // ================================================================
    // 权限矩阵（服务确实按 MemberRole 判断）
    // ================================================================

    @Test
    void onlyOwnersAndAdminsCanAddMembers() {
        Group group = newGroup();
        UUID outsider = createUser("add-outsider");

        assertThatThrownBy(() -> conversationService.addMembers(
                group.id(), group.member(), List.of(outsider.toString())))
                .hasMessageContaining("只有群主或管理员");

        // 提为管理员后即可
        conversationService.updateMemberRole(group.id(), group.owner(),
                group.member().toString(), "ADMIN");
        conversationService.addMembers(group.id(), group.member(), List.of(outsider.toString()));
        assertThat(roleOf(group.id(), outsider)).isEqualTo("MEMBER");
    }

    @Test
    void adminsCannotRemoveOtherAdmins() {
        Group group = newGroup();
        UUID secondAdmin = createUser("two-admins");
        conversationService.addMembers(group.id(), group.owner(), List.of(secondAdmin.toString()));
        conversationService.updateMemberRole(group.id(), group.owner(),
                group.member().toString(), "ADMIN");
        conversationService.updateMemberRole(group.id(), group.owner(),
                secondAdmin.toString(), "ADMIN");

        assertThatThrownBy(() -> conversationService.removeMember(
                group.id(), group.member(), secondAdmin.toString()))
                .as("管理员互踢的结果取决于谁的请求先到，这不该被允许")
                .hasMessageContaining("权限不足");

        // 群主可以
        conversationService.removeMember(group.id(), group.owner(), secondAdmin.toString());
        assertThat(conversationRepository.findActiveMemberRole(group.id(), secondAdmin)).isEmpty();
    }

    @Test
    void nobodyCanRemoveTheOwner() {
        Group group = newGroup();
        conversationService.updateMemberRole(group.id(), group.owner(),
                group.member().toString(), "ADMIN");

        assertThatThrownBy(() -> conversationService.removeMember(
                group.id(), group.member(), group.owner().toString()))
                .hasMessageContaining("不能移出群主");
    }

    @Test
    void ownersMustTransferBeforeLeaving() {
        Group group = newGroup();

        assertThatThrownBy(() -> conversationService.leave(group.id(), group.owner()))
                .hasMessageContaining("先转让");

        conversationService.transferOwnership(group.id(), group.owner(),
                group.member().toString());
        conversationService.leave(group.id(), group.owner());

        assertThat(conversationRepository.findActiveMemberRole(group.id(), group.owner())).isEmpty();
    }

    /**
     * 转让必须同时改三处：新群主角色、原群主角色、会话的 owner_id。
     *
     * 只改一部分会得到"两个 OWNER"或"owner_id 指向一个 MEMBER"这类
     * 无法通过接口修复的状态。
     */
    @Test
    void transferringOwnershipUpdatesBothRolesAndTheConversationOwner() {
        Group group = newGroup();

        conversationService.transferOwnership(group.id(), group.owner(), group.member().toString());

        assertThat(roleOf(group.id(), group.member())).isEqualTo("OWNER");
        assertThat(roleOf(group.id(), group.owner()))
                .as("原群主降为管理员而不是普通成员——转让常发生在'换人管理'，"
                        + "降到 MEMBER 会让他连协助管理都做不到，而这一步不可逆")
                .isEqualTo("ADMIN");
        assertThat(ownerIdOf(group.id())).isEqualTo(group.member());
        assertThat(countRole(group.id(), "OWNER")).as("群里只能有一个群主").isEqualTo(1);
    }

    @Test
    void onlyOwnersCanChangeRolesAndNeverTheirOwn() {
        Group group = newGroup();

        assertThatThrownBy(() -> conversationService.updateMemberRole(
                group.id(), group.member(), group.owner().toString(), "MEMBER"))
                .hasMessageContaining("只有群主");

        assertThatThrownBy(() -> conversationService.updateMemberRole(
                group.id(), group.owner(), group.owner().toString(), "ADMIN"))
                .as("群主给自己设 ADMIN 会让群里不再有 OWNER")
                .hasMessageContaining("不能变更自己的角色");

        assertThatThrownBy(() -> conversationService.updateMemberRole(
                group.id(), group.owner(), group.member().toString(), "OWNER"))
                .as("转让走独立端点，因为它还要降级原群主并改写 owner_id")
                .hasMessageContaining("专用接口");
    }

    @Test
    void removingYourselfIsRejectedInFavourOfTheLeaveEndpoint() {
        Group group = newGroup();

        assertThatThrownBy(() -> conversationService.removeMember(
                group.id(), group.member(), group.member().toString()))
                .hasMessageContaining("退群接口");
    }

    /** 加人是幂等的：已在群内的被静默跳过，且不产生变更 */
    @Test
    void addingAnExistingMemberIsANoOp() {
        Group group = newGroup();

        var change = conversationService.addMembers(
                group.id(), group.owner(), List.of(group.member().toString()));

        assertThat(change.joined()).isEmpty();
        assertThat(conversationRepository.countActiveMembers(group.id())).isEqualTo(2);
    }

    // ================================================================
    // 历史可见性（join_seq）
    // ================================================================

    /**
     * 新成员看不到入群之前的消息。
     *
     * 没有这个裁剪时，"把某人加进群"等同于"把这个群的全部历史交给他"——
     * 而加人只需要管理员权限，被泄露方（群里其他人）没有任何感知。
     * 客户端传 {@code afterSeq=0} 就能拿到全部历史，所以裁剪必须在服务端做。
     */
    @Test
    void newMembersCannotReadHistoryFromBeforeTheyJoined() {
        Group group = newGroup();
        UUID latecomer = createUser("latecomer");

        ImMessage before = send(group.id(), group.owner(), "入群前的秘密");
        conversationService.addMembers(group.id(), group.owner(), List.of(latecomer.toString()));
        ImMessage after = send(group.id(), group.owner(), "入群后的消息");

        assertThat(messageService.listMessages(group.id(), latecomer, 0))
                .extracting(ImMessage::id)
                .as("入群前的消息必须不可见")
                .containsExactly(after.id());

        // 原有成员不受影响
        assertThat(messageService.listMessages(group.id(), group.owner(), 0))
                .extracting(ImMessage::id)
                .containsExactly(before.id(), after.id());
    }

    /** 退群再入群的人看不到离开期间的消息 */
    @Test
    void rejoiningMembersCannotReadMessagesFromWhileTheyWereGone() {
        Group group = newGroup();

        ImMessage whileMember = send(group.id(), group.owner(), "在群时的消息");
        conversationService.leave(group.id(), group.member());
        ImMessage whileGone = send(group.id(), group.owner(), "离开期间的消息");
        conversationService.addMembers(group.id(), group.owner(), List.of(group.member().toString()));
        ImMessage afterRejoin = send(group.id(), group.owner(), "重新入群后的消息");

        assertThat(messageService.listMessages(group.id(), group.member(), 0))
                .extracting(ImMessage::id)
                .as("离开期间的消息不该因为重新入群而变得可见")
                .containsExactly(afterRejoin.id());
        assertThat(List.of(whileMember.id(), whileGone.id()))
                .doesNotContain(afterRejoin.id());
    }

    /** 被移出后立即失去读写权限——鉴权不走缓存，所以是立即的 */
    @Test
    void removedMembersLoseAccessImmediately() {
        Group group = newGroup();
        send(group.id(), group.owner(), "群内消息");

        conversationService.removeMember(group.id(), group.owner(), group.member().toString());

        assertThatThrownBy(() -> messageService.listMessages(group.id(), group.member(), 0))
                .hasMessageContaining("不是该会话成员");
        assertThatThrownBy(() -> messageService.createMessage(group.member(), new SendMessage(
                group.id().toString(), "client-" + UUID.randomUUID(), "text",
                Map.of("text", "还能发吗"))))
                .hasMessageContaining("不是该会话成员");
    }

    /**
     * 复活成员时未读数不该包含离开期间的消息。
     *
     * read_seq 与 join_seq 一同重置。只重置 join_seq 的话，用户看到一个
     * 显示 N 条未读、点开却只有一条消息的会话。
     */
    @Test
    void rejoiningResetsTheUnreadCursorToo() {
        Group group = newGroup();
        conversationService.leave(group.id(), group.member());
        send(group.id(), group.owner(), "离开期间 1");
        send(group.id(), group.owner(), "离开期间 2");
        conversationService.addMembers(group.id(), group.owner(), List.of(group.member().toString()));

        Conversation view = conversationService.getConversation(group.id(), group.member());
        assertThat(view.unreadCount())
                .as("未读数不该包含离开期间的消息")
                .isZero();
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 一个刚建好的群：owner 是群主，member 是普通成员 */
    private Group newGroup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID owner = createUser("g-own-" + suffix);
        UUID member = createUser("g-mem-" + suffix);
        Conversation group = conversationService.createGroupConversation(owner,
                new CreateGroupConversation("权限测试群", "权", List.of(member.toString())));
        return new Group(UUID.fromString(group.id()), owner, member);
    }

    private record Group(UUID id, UUID owner, UUID member) {
    }

    private ImMessage send(UUID conversationId, UUID senderId, String text) {
        return messageService.createMessage(senderId, new SendMessage(
                conversationId.toString(), "client-" + UUID.randomUUID(), "text",
                Map.of("text", text)));
    }

    /**
     * 创建一个测试用户。
     *
     * 刻意<b>不</b>复用演示种子里的 Alice/Bob/Carol：种子会话和种子用户之间已有
     * 成员关系，用它们测"创建会话"会让"单聊已存在"这类分支被意外命中，
     * 而失败原因看起来像是幂等逻辑出错。用户名带随机后缀以避开 UNIQUE 约束。
     */
    private UUID createUser(String prefix) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO users (id, username, display_name, avatar, status)
                        VALUES (:id, :username, :displayName, '测', 'ACTIVE')
                        """)
                .param("id", id)
                .param("username", prefix + "-" + id.toString().substring(0, 8))
                .param("displayName", prefix)
                .update();
        return id;
    }

    private String roleOf(UUID conversationId, UUID userId) {
        return conversationRepository.findActiveMemberRole(conversationId, userId)
                .orElseThrow(() -> new AssertionError("期望 " + userId + " 是会话成员"));
    }

    private UUID ownerIdOf(UUID conversationId) {
        return jdbcClient.sql("SELECT owner_id FROM conversations WHERE id = :id")
                .param("id", conversationId)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("owner_id")))
                .single();
    }

    private int countRole(UUID conversationId, String role) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM conversation_members
                         WHERE conversation_id = :id AND role = :role AND left_at IS NULL
                        """)
                .param("id", conversationId)
                .param("role", role)
                .query(Integer.class)
                .single();
    }

    /**
     * 用生产代码的 {@link ConversationRepository.DirectKey} 做规范化，而不是在测试里
     * 再写一遍排序。
     *
     * 测试自己排一遍的话，两份实现同时写错就测不出任何问题——而这里正是
     * {@code UUID.compareTo} 与 PostgreSQL uuid 排序不一致那个缺陷的所在之处。
     */
    private int countDirectKeys(UUID a, UUID b) {
        ConversationRepository.DirectKey key = ConversationRepository.DirectKey.of(a, b);
        return jdbcClient.sql("""
                        SELECT count(*) FROM direct_conversation_keys
                         WHERE user_low_id = :low AND user_high_id = :high
                        """)
                .param("low", key.lowId())
                .param("high", key.highId())
                .query(Integer.class)
                .single();
    }

    private int countConversationsWithoutMembers() {
        return jdbcClient.sql("""
                        SELECT count(*) FROM conversations c
                         WHERE NOT EXISTS (
                             SELECT 1 FROM conversation_members cm
                              WHERE cm.conversation_id = c.id
                         )
                        """)
                .query(Integer.class)
                .single();
    }

    /**
     * 并发执行并收集结果。
     *
     * 用 CountDownLatch 让所有任务尽可能同时进入临界区——顺序提交测不到竞争。
     */
    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = tasks.stream()
                    .map(task -> pool.submit(() -> {
                        start.await();
                        return task.call();
                    }))
                    .toList();
            start.countDown();
            List<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 取单个会话视图时必须只返回那一个会话。
     *
     * <b>这条断言锁定一个已经发生过的缺陷。</b>{@code SELECT_CONVERSATION} 以
     * {@code LEFT JOIN LATERAL (...) peer ON c.type = 'direct'} 结尾，而
     * {@code findForUser} 原先直接追加 {@code " AND c.id = :conversationId"}——
     * 那个条件落在 <b>JOIN 的 ON 子句</b>上而不是 WHERE 上，于是查询变成
     * "取该用户的全部会话"。
     *
     * 此前不可见：唯一的调用方是红包与通话的成员校验，它们只用 {@code isPresent()}
     * 判断，多返回几行不影响结论。用户有<b>两个以上</b>会话时才会暴露。
     */
    @Test
    void fetchingOneConversationDoesNotReturnTheOthers() {
        UUID alice = createUser("view-a");
        UUID bob = createUser("view-b");
        UUID carol = createUser("view-c");

        Conversation direct = conversationService.createDirectConversation(alice, bob.toString());
        Conversation group = conversationService.createGroupConversation(alice,
                new CreateGroupConversation("另一个会话", "另", List.of(carol.toString())));

        // 此时 alice 有两个会话。取其中一个必须只拿到那一个
        Conversation fetched = conversationService.getConversation(
                UUID.fromString(direct.id()), alice);
        assertThat(fetched.id()).isEqualTo(direct.id());
        assertThat(fetched.type()).isEqualTo("direct");

        Conversation fetchedGroup = conversationService.getConversation(
                UUID.fromString(group.id()), alice);
        assertThat(fetchedGroup.id()).isEqualTo(group.id());
        assertThat(fetchedGroup.title()).isEqualTo("另一个会话");

        assertThat(conversationService.listConversations(alice)).hasSize(2);
    }

    /**
     * 已读游标能让未读数下降，且只能前进。
     *
     * {@code advanceReadSeq} 在补上 {@code POST /conversations/{id}/read} 端点之前
     * <b>没有任何调用方</b>——未读数由 {@code next_seq - 1 - read_seq} 推导，
     * 因此它只增不减，用户看完消息后角标依然挂着。这是"实现了但不可达"的
     * 又一处：方法存在、逻辑正确、没人调用。
     */
    @Test
    void advancingTheReadCursorClearsUnreadAndNeverGoesBackwards() {
        Group group = newGroup();
        send(group.id(), group.owner(), "第一条");
        send(group.id(), group.owner(), "第二条");

        assertThat(conversationService.getConversation(group.id(), group.member()).unreadCount())
                .isEqualTo(2);

        conversationService.markRead(group.id(), group.member(), 2);
        assertThat(conversationService.getConversation(group.id(), group.member()).unreadCount())
                .isZero();

        // 回退的上报被忽略：乱序到达的请求不该让已经读过的消息重新变成未读
        conversationService.markRead(group.id(), group.member(), 1);
        assertThat(conversationService.getConversation(group.id(), group.member()).unreadCount())
                .as("游标只能前进")
                .isZero();

        // 新消息重新产生未读
        send(group.id(), group.owner(), "第三条");
        assertThat(conversationService.getConversation(group.id(), group.member()).unreadCount())
                .isEqualTo(1);
    }

    /** 非成员不能上报已读——否则它是一个可以用来探测会话是否存在的接口 */
    @Test
    void markReadRequiresMembership() {
        Group group = newGroup();
        UUID outsider = createUser("read-outsider");

        assertThatThrownBy(() -> conversationService.markRead(group.id(), outsider, 1))
                .hasMessageContaining("不是该会话成员");
    }

    /**
     * 单聊唯一键的规范化顺序必须与 PostgreSQL 的 uuid 比较一致。
     *
     * <b>这条断言锁定的是一个已经发生过的缺陷。</b>{@code UUID.compareTo} 把两个
     * 64 位分量当<b>有符号</b> long 比较，而 PostgreSQL 的 uuid 按 16 字节<b>无符号</b>
     * 比较。首字节 ≥ 0x80 时两者结论相反，写入直接违反 {@code chk_direct_key_order}。
     *
     * 演示种子完全掩盖了它：种子用户的 ID 是 1111…/2222…/3333…，首字节远小于 0x80，
     * 两种比较方式给出相同结果。只有用随机 UUID 才会以约一半的概率暴露。
     *
     * 让<b>数据库</b>来判定顺序，而不是在 Java 里再实现一遍预期——
     * 后者只能证明两段 Java 代码一致，而缺陷恰恰在于 Java 与数据库不一致。
     */
    @Test
    void directKeyOrderingMatchesPostgresUuidComparison() {
        // 确定性的边界用例：Java 认为 signedNegative < small，PostgreSQL 认为它更大
        UUID highFirstByte = new UUID(0x8000000000000000L, 0L);
        UUID lowFirstByte = new UUID(0x0000000000000001L, 0L);
        assertThat(highFirstByte.compareTo(lowFirstByte))
                .as("前提：Java 的有符号比较认为首字节 0x80 的 UUID 更小")
                .isNegative();
        assertOrderingAgreesWithDatabase(highFirstByte, lowFirstByte);

        // 随机批量：单次触发概率约 1/2，这个规模下漏检可以忽略
        for (int attempt = 0; attempt < 200; attempt++) {
            assertOrderingAgreesWithDatabase(UUID.randomUUID(), UUID.randomUUID());
        }
    }

    private void assertOrderingAgreesWithDatabase(UUID a, UUID b) {
        ConversationRepository.DirectKey key = ConversationRepository.DirectKey.of(a, b);
        Boolean ordered = jdbcClient
                .sql("SELECT CAST(:low AS uuid) < CAST(:high AS uuid)")
                .param("low", key.lowId())
                .param("high", key.highId())
                .query(Boolean.class)
                .single();
        assertThat(ordered)
                .as("DirectKey.of(%s, %s) 得出的顺序必须满足 chk_direct_key_order", a, b)
                .isTrue();
    }

    /** 用到 MemberRole 的名字常量，确保测试与实现引用同一套取值 */
    @Test
    void roleNamesMatchTheDatabaseCheckConstraint() {
        Group group = newGroup();
        for (MemberRole role : List.of(MemberRole.MEMBER, MemberRole.ADMIN)) {
            conversationService.updateMemberRole(group.id(), group.owner(),
                    group.member().toString(), role.name());
            assertThat(roleOf(group.id(), group.member())).isEqualTo(role.name());
        }
    }
}
