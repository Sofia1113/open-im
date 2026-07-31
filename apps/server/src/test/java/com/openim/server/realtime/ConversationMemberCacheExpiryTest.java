package com.openim.server.realtime;

import com.openim.server.domain.service.ConversationService;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import com.openim.server.support.DemoUsers;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话成员缓存的 TTL 兜底
 *
 * <b>这个测试锁定的是缓存与快照的本质区别。</b>
 *
 * 旧实现用连接建立时的成员快照做路由，它的错误<b>不会自愈</b>——
 * 一旦成员变了，错误会持续到用户重连为止，可能是几小时。
 *
 * 缓存即使在失效广播完全丢失的情况下（Redis 故障、网络分区、
 * 或者 M2 的开发者忘了调用 invalidateMembers），陈旧窗口也止于一个 TTL。
 * 这里把 TTL 压到 1 秒来验证这条自愈路径，生产默认 60 秒。
 *
 * 注意本测试<b>刻意不调用</b> {@code invalidateMembers}——调用了就测不到兜底了。
 */
@SpringBootTest(properties = {
        "openim.realtime.member-cache-ttl-seconds=1"
})
class ConversationMemberCacheExpiryTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void staleMembersDisappearOnceTheTtlElapsesEvenWithoutInvalidation() {
        UUID conversationId = createGroupConversation(DemoUsers.ALICE);
        addMemberDirectly(conversationId, DemoUsers.BOB);

        // 载入缓存
        assertThat(conversationService.listActiveMemberIds(conversationId))
                .containsExactlyInAnyOrder(DemoUsers.ALICE, DemoUsers.BOB);

        // Bob 退出，但不发失效通知——模拟 Redis 故障或调用方遗漏
        jdbcClient.sql("""
                        UPDATE conversation_members SET left_at = now()
                        WHERE conversation_id = :conversationId AND user_id = :userId
                        """)
                .param("conversationId", conversationId)
                .param("userId", DemoUsers.BOB)
                .update();

        // 缓存仍是旧的——这正是陈旧窗口
        assertThat(conversationService.listActiveMemberIds(conversationId))
                .contains(DemoUsers.BOB);

        // TTL 到期后自动收敛到数据库的真实状态。
        // 快照方案在这里会永远返回 Bob，直到连接断开。
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(
                        conversationService.listActiveMemberIds(conversationId))
                        .containsExactly(DemoUsers.ALICE));
    }

    @Test
    void newMembersAppearOnceTheTtlElapsesEvenWithoutInvalidation() {
        UUID conversationId = createGroupConversation(DemoUsers.ALICE);

        assertThat(conversationService.listActiveMemberIds(conversationId))
                .containsExactly(DemoUsers.ALICE);

        addMemberDirectly(conversationId, DemoUsers.CAROL);

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(
                        conversationService.listActiveMemberIds(conversationId))
                        .containsExactlyInAnyOrder(DemoUsers.ALICE, DemoUsers.CAROL));
    }

    /**
     * 鉴权路径不受缓存影响：成员变更立即生效。
     *
     * 这条断言是扇出与鉴权分开处理的依据——扇出可以容忍一个 TTL 的陈旧，
     * 权限判断不行。
     */
    @Test
    void membershipCheckIsNotCachedSoRevocationIsImmediate() {
        UUID conversationId = createGroupConversation(DemoUsers.ALICE);
        addMemberDirectly(conversationId, DemoUsers.BOB);

        // 此刻是成员，不抛异常
        conversationService.requireMembership(conversationId, DemoUsers.BOB);

        jdbcClient.sql("""
                        UPDATE conversation_members SET left_at = now()
                        WHERE conversation_id = :conversationId AND user_id = :userId
                        """)
                .param("conversationId", conversationId)
                .param("userId", DemoUsers.BOB)
                .update();

        // 没有失效通知、没有等待 TTL——鉴权直接查库，因此立即拒绝
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        conversationService.requireMembership(conversationId, DemoUsers.BOB))
                .hasMessageContaining("不是该会话成员");
    }

    /** 会话不存在与不是成员必须可区分，前端据此显示不同提示 */
    @Test
    void missingConversationIsDistinguishableFromMissingMembership() {
        UUID conversationId = createGroupConversation(DemoUsers.ALICE);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        conversationService.requireMembership(conversationId, DemoUsers.BOB))
                .hasMessageContaining("不是该会话成员");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        conversationService.requireMembership(UUID.randomUUID(), DemoUsers.ALICE))
                .hasMessageContaining("会话不存在");
    }

    private UUID createGroupConversation(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO conversations (id, type, title, avatar, owner_id, next_seq)
                        VALUES (:id, 'group', :title, '测', :ownerId, 1)
                        """)
                .param("id", id)
                .param("title", "缓存过期测试会话")
                .param("ownerId", ownerId)
                .update();
        addMemberDirectly(id, ownerId);
        return id;
    }

    private void addMemberDirectly(UUID conversationId, UUID userId) {
        jdbcClient.sql("""
                        INSERT INTO conversation_members (conversation_id, user_id, role, read_seq)
                        VALUES (:conversationId, :userId, 'MEMBER', 0)
                        ON CONFLICT (conversation_id, user_id)
                        DO UPDATE SET left_at = NULL
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
    }
}
