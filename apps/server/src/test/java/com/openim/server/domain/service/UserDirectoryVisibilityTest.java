package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.Requests.CreateGroupConversation;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户目录的可见性边界
 *
 * <b>此前 {@code GET /api/v1/users} 返回全站所有 ACTIVE 用户</b>——无 LIMIT 的
 * 全表扫描，且客户端加载时就会请求它。两个问题叠加：
 *
 * <ul>
 *   <li><b>隐私</b>：任何登录用户可以枚举全部注册者的用户名、昵称与在线状态</li>
 *   <li><b>容量</b>：响应体随注册量线性增长，十万用户时这一个接口就能打满连接池</li>
 * </ul>
 *
 * 收窄后的可见集是<b>可推导的</b>：与我共处过至少一个会话的人——他们的昵称在会话
 * 成员列表里本来就对我可见，所以这个接口不再暴露任何"我本来看不到"的信息。
 *
 * 这个测试类刻意<b>不使用演示种子</b>：种子里 Alice/Bob/Carol 三人已经互相在同一个
 * 群里，任何"能看到对方"的断言都会因为种子关系而通过，而不是因为代码正确。
 */
@SpringBootTest
class UserDirectoryVisibilityTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private JdbcClient jdbcClient;

    /**
     * 陌生人不出现在联系人列表里。
     *
     * 这是本轮改动的核心断言。改造之前它必然失败——那时任何人都能看到所有人。
     */
    @Test
    void strangersAreNotVisibleInTheContactList() {
        UUID alice = createUser("vis-alice");
        UUID stranger = createUser("vis-stranger");

        assertThat(ids(userService.listContacts(alice)))
                .as("没有任何会话往来的人不该出现在联系人里")
                .doesNotContain(stranger.toString());
    }

    /** 建立单聊后对方成为联系人，双向可见 */
    @Test
    void openingADirectConversationMakesBothPartiesContacts() {
        UUID alice = createUser("vis-d-alice");
        UUID bob = createUser("vis-d-bob");

        conversationService.createDirectConversation(alice, bob.toString());

        assertThat(ids(userService.listContacts(alice))).contains(bob.toString());
        assertThat(ids(userService.listContacts(bob)))
                .as("可见性是对称的——对方发起的会话同样让我看到他")
                .contains(alice.toString());
    }

    /** 同群成员互为联系人，且不会看到群外的人 */
    @Test
    void groupMembersSeeEachOtherButNotOutsiders() {
        UUID owner = createUser("vis-g-own");
        UUID member = createUser("vis-g-mem");
        UUID outsider = createUser("vis-g-out");

        conversationService.createGroupConversation(owner,
                new CreateGroupConversation("可见性群", "见", List.of(member.toString())));

        assertThat(ids(userService.listContacts(member)))
                .contains(owner.toString())
                .doesNotContain(outsider.toString());
    }

    /** 自己不出现在自己的联系人里 */
    @Test
    void theRequesterIsNotTheirOwnContact() {
        UUID alice = createUser("vis-self-a");
        UUID bob = createUser("vis-self-b");
        conversationService.createDirectConversation(alice, bob.toString());

        assertThat(ids(userService.listContacts(alice)))
                .doesNotContain(alice.toString());
    }

    /**
     * 同一个人在多个会话里只出现一次。
     *
     * JOIN 出来的行数是"共同会话数"，不去重的话联系人列表里会有重复项，
     * 而 React 的 key 会撞。
     */
    @Test
    void aPersonSharedAcrossSeveralConversationsAppearsOnce() {
        UUID alice = createUser("vis-dup-a");
        UUID bob = createUser("vis-dup-b");

        conversationService.createDirectConversation(alice, bob.toString());
        conversationService.createGroupConversation(alice,
                new CreateGroupConversation("群一", "一", List.of(bob.toString())));
        conversationService.createGroupConversation(alice,
                new CreateGroupConversation("群二", "二", List.of(bob.toString())));

        assertThat(ids(userService.listContacts(alice)).stream()
                .filter(bob.toString()::equals)
                .count())
                .isEqualTo(1);
    }

    /**
     * 退群后对方从联系人里消失。
     *
     * 条件里带 {@code left_at IS NULL}，否则"曾经同群"会永久保留可见性——
     * 那使退群这个动作在隐私层面没有任何效果。
     */
    @Test
    void leavingAConversationRemovesTheContactRelationship() {
        UUID owner = createUser("vis-leave-own");
        UUID member = createUser("vis-leave-mem");
        Conversation group = conversationService.createGroupConversation(owner,
                new CreateGroupConversation("退群可见性", "退", List.of(member.toString())));

        assertThat(ids(userService.listContacts(member))).contains(owner.toString());

        conversationService.leave(UUID.fromString(group.id()), member);

        assertThat(ids(userService.listContacts(member)))
                .as("退群后不该继续看到原群成员")
                .doesNotContain(owner.toString());
        assertThat(ids(userService.listContacts(owner)))
                .as("反向同样不可见")
                .doesNotContain(member.toString());
    }

    // ── 按用户名查找 ────────────────────────────────────────────────

    /**
     * 完整用户名可以找到陌生人——这是建立第一次对话的唯一入口。
     *
     * 联系人列表收窄之后，没有这条路径的话两个从未有过往来的人永远无法开始对话，
     * 那会把隐私改进变成功能缺失。
     */
    @Test
    void aStrangerCanBeFoundByTheirExactUsername() {
        UUID alice = createUser("search-alice");
        UUID stranger = createUser("search-target");
        String username = usernameOf(stranger);

        assertThat(ids(userService.searchUsers(username, alice)))
                .containsExactly(stranger.toString());
    }

    /** 大小写不敏感：注册时限定小写，但用户可能输入大写 */
    @Test
    void usernameSearchIsCaseInsensitive() {
        UUID alice = createUser("search-ci-a");
        UUID target = createUser("search-ci-t");

        assertThat(ids(userService.searchUsers(usernameOf(target).toUpperCase(), alice)))
                .containsExactly(target.toString());
    }

    /**
     * <b>不做前缀匹配。</b>
     *
     * 这条断言是可见性边界的一部分，不是行为偏好：`LIKE 'ab%'` 允许攻击者用几百次
     * 请求枚举出大部分用户名，等于把刚移除的全站列表又还回去了，只是慢一点。
     */
    @Test
    void partialUsernamesFindNothing() {
        UUID alice = createUser("prefix-alice");
        UUID target = createUser("prefix-target");
        String username = usernameOf(target);

        assertThat(userService.searchUsers(username.substring(0, username.length() - 2), alice))
                .as("前缀不应命中——否则用户名可被枚举")
                .isEmpty();
        assertThat(userService.searchUsers(username.substring(2), alice))
                .as("后缀同样不应命中")
                .isEmpty();
    }

    @Test
    void searchingForYourselfReturnsNothing() {
        UUID alice = createUser("search-self");

        assertThat(userService.searchUsers(usernameOf(alice), alice))
                .as("找自己没有意义——单聊接口也会拒绝与自己建会话")
                .isEmpty();
    }

    @Test
    void searchingForAnUnknownUsernameReturnsAnEmptyList() {
        UUID alice = createUser("search-miss");

        assertThat(userService.searchUsers("no_such_user_" + UUID.randomUUID(), alice)).isEmpty();
        assertThat(userService.searchUsers("   ", alice)).isEmpty();
        assertThat(userService.searchUsers("", alice)).isEmpty();
    }

    /** 已注销的账号既不出现在联系人里也搜不到 */
    @Test
    void deletedAccountsAreInvisibleEverywhere() {
        UUID alice = createUser("del-alice");
        UUID bob = createUser("del-bob");
        conversationService.createDirectConversation(alice, bob.toString());
        String bobUsername = usernameOf(bob);

        assertThat(ids(userService.listContacts(alice))).contains(bob.toString());

        jdbcClient.sql("UPDATE users SET status = 'DELETED' WHERE id = :id")
                .param("id", bob)
                .update();

        assertThat(ids(userService.listContacts(alice)))
                .as("注销的账号不该留在联系人里")
                .doesNotContain(bob.toString());
        assertThat(userService.searchUsers(bobUsername, alice)).isEmpty();
    }

    // ── 按 ID 批量补齐 ──────────────────────────────────────────────

    /**
     * 批量补齐<b>不</b>做可见性校验，这是有意的。
     *
     * 这些 ID 是服务端通过消息、红包、通话等鉴权过的接口交给客户端的——
     * 再校验一次等于要求客户端证明它是怎么拿到这些 ID 的。
     *
     * 真正的意义在于：收窄联系人列表之后，历史消息里已退群成员的昵称会取不到，
     * 没有这条路径它们会退化成一串 UUID。
     */
    @Test
    void lookupResolvesProfilesRegardlessOfContactRelationship() {
        UUID alice = createUser("lookup-a");
        UUID stranger = createUser("lookup-stranger");

        // 陌生人不在联系人里
        assertThat(ids(userService.listContacts(alice))).doesNotContain(stranger.toString());
        // 但按 ID 能取到资料——渲染历史消息需要它
        assertThat(ids(userService.lookupUsers(List.of(stranger))))
                .containsExactly(stranger.toString());
    }

    @Test
    void lookupIgnoresUnknownIdsInsteadOfFailing() {
        UUID known = createUser("lookup-known");

        assertThat(ids(userService.lookupUsers(List.of(known, UUID.randomUUID()))))
                .as("不存在的 ID 被跳过而不是让整批失败——"
                        + "否则一个坏 ID 会让整屏昵称都显示不出来")
                .containsExactly(known.toString());
        assertThat(userService.lookupUsers(List.of())).isEmpty();
    }

    /** 超出上限的请求被截断，不能当成变相的全量导出接口 */
    @Test
    void lookupIsBoundedInSize() {
        List<UUID> many = new java.util.ArrayList<>();
        for (int index = 0; index < 250; index++) {
            many.add(UUID.randomUUID());
        }
        UUID real = createUser("lookup-bounded");
        // 真实用户放在超出上限的位置，因此结果必须为空——证明截断确实发生了
        many.add(real);

        assertThat(userService.lookupUsers(many))
                .as("第 251 个 ID 应当被截断掉")
                .isEmpty();
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private List<String> ids(List<User> users) {
        return users.stream().map(User::id).toList();
    }

    private String usernameOf(UUID userId) {
        return jdbcClient.sql("SELECT username FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
    }

    /**
     * 创建测试用户。
     *
     * 用户名带随机后缀并全小写下划线，与注册接口的 {@code ^[a-z0-9_]+$} 约束一致——
     * 按用户名查找的测试依赖这一点。
     */
    private UUID createUser(String prefix) {
        UUID id = UUID.randomUUID();
        String username = prefix.replace("-", "_") + "_"
                + id.toString().replace("-", "").substring(0, 8);
        jdbcClient.sql("""
                        INSERT INTO users (id, username, display_name, avatar, status)
                        VALUES (:id, :username, :displayName, '可', 'ACTIVE')
                        """)
                .param("id", id)
                .param("username", username)
                .param("displayName", prefix)
                .update();
        return id;
    }
}
