package com.openim.server.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.domain.service.ConversationService;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import com.openim.server.support.DemoUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话事件路由集成测试
 *
 * 迁移前 {@code emitToConversation} 依赖一张在连接建立时填充的
 * {@code conversationId → sessionId} 索引，此后成员关系变化不会更新它。
 * 本测试类验证路由改为实时查库后的两条不变量：
 * <ul>
 *   <li>连接建立<b>之后</b>加入会话的用户能收到消息</li>
 *   <li>已退出会话的用户不再收到消息，即使连接仍然存活</li>
 * </ul>
 *
 * 这两条在旧实现下都不成立，且当前没有加群/退群接口，
 * 因此只能直接操作成员表来构造场景——M2 引入群成员管理后，
 * 这里就是那条链路的回归测试。
 *
 * <b>关于缓存</b>：扇出路径的成员查询现在经过 {@code ConversationMemberCache}
 * （每条群消息在每个实例上都要查一次成员表，是全服务放大最严重的查询）。
 * 因此本测试在直接改写成员表后<b>必须显式调用</b>
 * {@code conversationService.invalidateMembers()}——这不是为了让测试通过而做的妥协，
 * 而是在锁定一条生产契约：<b>任何写 conversation_members 的事务都必须失效该会话的成员缓存</b>。
 * M2 实现加群/退群时若漏掉这一步，症状是新成员延迟收消息、被移出者继续收消息，
 * 且要等一个 TTL 才自行消失——极难与"投递本身出问题"区分开。
 *
 * 失效广播丢失（Redis 故障）时的兜底由 {@link ConversationMemberCacheExpiryTest} 覆盖。
 */
@SpringBootTest
class ConversationRoutingIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired
    private RealtimeWebSocketHandler handler;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void deliversToUsersWhoJoinedAfterTheirConnectionWasEstablished() throws Exception {
        // 建一个只有 Alice 的新会话
        UUID conversationId = createGroupConversation(DemoUsers.ALICE);

        WebSocketSession bobSession = session(DemoUsers.BOB, "routing-bob");
        handler.afterConnectionEstablished(bobSession);
        try {
            // Bob 的连接已建立，此刻他还不是成员
            handler.emitToConversation(conversationId.toString(), "message:created",
                    Map.of("text", "before join"));
            verify(bobSession, never()).sendMessage(any(TextMessage.class));

            // Bob 加入会话——连接没有重建
            addMember(conversationId, DemoUsers.BOB);

            handler.emitToConversation(conversationId.toString(), "message:created",
                    Map.of("text", "after join"));

            // 旧实现在这里会漏推：索引是连接时的快照，不含新加入的会话
            verify(bobSession).sendMessage(any(TextMessage.class));
        } finally {
            handler.afterConnectionClosed(bobSession, org.springframework.web.socket.CloseStatus.NORMAL);
        }
    }

    @Test
    void stopsDeliveringToUsersWhoLeftTheConversation() throws Exception {
        UUID conversationId = createGroupConversation(DemoUsers.ALICE);
        addMember(conversationId, DemoUsers.CAROL);

        WebSocketSession carolSession = session(DemoUsers.CAROL, "routing-carol");
        handler.afterConnectionEstablished(carolSession);
        try {
            handler.emitToConversation(conversationId.toString(), "message:created",
                    Map.of("text", "while member"));
            verify(carolSession).sendMessage(any(TextMessage.class));

            // Carol 退出会话，但连接保持
            markMemberLeft(conversationId, DemoUsers.CAROL);

            handler.emitToConversation(conversationId.toString(), "message:created",
                    Map.of("text", "after leaving"));

            // 仍然只有第一条：退群后不应再收到该会话的消息。
            // 旧实现会继续推送，直到用户重连才停止。
            verify(carolSession, org.mockito.Mockito.times(1))
                    .sendMessage(any(TextMessage.class));
        } finally {
            handler.afterConnectionClosed(carolSession, org.springframework.web.socket.CloseStatus.NORMAL);
        }
    }

    @Test
    void ignoresMalformedConversationIdWithoutThrowing() {
        WebSocketSession bobSession = session(DemoUsers.BOB, "routing-bob-2");
        handler.afterConnectionEstablished(bobSession);
        try {
            // 来自 Redis 的事件载荷不可信，格式非法时应静默跳过而不是让订阅线程抛异常
            handler.emitToConversation("not-a-uuid", "message:created", Map.of());
        } finally {
            handler.afterConnectionClosed(bobSession, org.springframework.web.socket.CloseStatus.NORMAL);
        }
    }

    @Test
    void memberListComesFromTheDatabaseNotTheConnectionSnapshot() {
        UUID conversationId = createGroupConversation(DemoUsers.ALICE);
        addMember(conversationId, DemoUsers.BOB);

        // 直接断言数据来源：路由依据必须是 listActiveMemberIds 的结果
        assertThatMembers(conversationId).containsExactlyInAnyOrder(DemoUsers.ALICE, DemoUsers.BOB);

        markMemberLeft(conversationId, DemoUsers.BOB);
        assertThatMembers(conversationId).containsExactly(DemoUsers.ALICE);
    }

    private org.assertj.core.api.ListAssert<UUID> assertThatMembers(UUID conversationId) {
        return org.assertj.core.api.Assertions.assertThat(
                conversationService.listActiveMemberIds(conversationId));
    }

    /** 建一个群聊，只含指定的创建者 */
    private UUID createGroupConversation(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO conversations (id, type, title, avatar, owner_id, next_seq)
                        VALUES (:id, 'group', :title, '测', :ownerId, 1)
                        """)
                .param("id", id)
                .param("title", "路由测试会话")
                .param("ownerId", ownerId)
                .update();
        addMember(id, ownerId);
        return id;
    }

    /**
     * 加入会话。
     *
     * 失效调用模拟的是 M2 加群接口<b>必须</b>做的事——服务层写完成员表后
     * 失效该会话的成员缓存。测试直接操作数据库绕过了服务层，所以要手动补上这一步。
     */
    private void addMember(UUID conversationId, UUID userId) {
        jdbcClient.sql("""
                        INSERT INTO conversation_members (conversation_id, user_id, role, read_seq)
                        VALUES (:conversationId, :userId, 'MEMBER', 0)
                        ON CONFLICT (conversation_id, user_id)
                        DO UPDATE SET left_at = NULL
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
        conversationService.invalidateMembers(conversationId);
    }

    /** 退出会话。失效理由同 {@link #addMember} */
    private void markMemberLeft(UUID conversationId, UUID userId) {
        jdbcClient.sql("""
                        UPDATE conversation_members SET left_at = now()
                        WHERE conversation_id = :conversationId AND user_id = :userId
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
        conversationService.invalidateMembers(conversationId);
    }

    private WebSocketSession session(UUID userId, String deviceId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of(
                WebSocketAuthenticationInterceptor.USER_ID, userId.toString(),
                WebSocketAuthenticationInterceptor.DEVICE_ID, deviceId
        ));
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(deviceId + "-session");
        return session;
    }
}
