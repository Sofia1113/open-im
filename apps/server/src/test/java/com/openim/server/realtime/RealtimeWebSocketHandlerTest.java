package com.openim.server.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.domain.repository.OutboxRepository;
import com.openim.server.domain.service.CallService;
import com.openim.server.domain.service.ConversationService;
import com.openim.server.domain.service.MessageService;
import com.openim.server.domain.service.UserService;
import com.openim.server.observability.DomainMetrics;
import com.openim.server.ratelimit.RateLimiter;
import com.openim.server.support.DemoUsers;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeWebSocketHandlerTest {
    private final MeterRegistry registry = new SimpleMeterRegistry();

    /** 构造 handler。maxConnections 可调，用于验证容量闸门 */
    private RealtimeWebSocketHandler handlerWith(int maxConnections) {
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.listConversations(any())).thenReturn(List.of());
        return new RealtimeWebSocketHandler(
                new ObjectMapper(),
                mock(Validator.class),
                conversationService,
                mock(MessageService.class),
                mock(CallService.class),
                mock(UserService.class),
                mock(PresenceService.class),
                new RateLimiter(true, 1_000, 30, null, registry),
                new DomainMetrics(registry, mock(OutboxRepository.class)),
                registry,
                512 * 1024,
                10_000,
                90_000,
                maxConnections
        );
    }

    @Test
    void isolatesTheSameDeviceIdAcrossDifferentUsers() throws Exception {
        RealtimeWebSocketHandler handler = handlerWith(10_000);
        WebSocketSession alice = session(DemoUsers.ALICE, "shared-device");
        WebSocketSession bob = session(DemoUsers.BOB, "shared-device");

        handler.afterConnectionEstablished(alice);
        handler.afterConnectionEstablished(bob);
        handler.emitToDevice(DemoUsers.BOB.toString(), "shared-device", "rtc:offer",
                Map.of("roomId", "room-1"));

        verify(bob).sendMessage(any(TextMessage.class));
        verify(alice, never()).sendMessage(any(TextMessage.class));
    }

    /**
     * 达到上限后拒绝新连接。
     *
     * 此前单实例没有任何连接数上限。每条连接持有一个
     * ConcurrentWebSocketSessionDecorator，连接数无界意味着内存占用无界——
     * 结果是 OOMKill（整个实例上的所有连接一起断），而不是优雅拒绝一条新连接。
     */
    @Test
    void rejectsNewConnectionsWhenAtCapacity() throws Exception {
        RealtimeWebSocketHandler handler = handlerWith(1);
        WebSocketSession first = session(DemoUsers.ALICE, "device-a");
        WebSocketSession second = session(DemoUsers.BOB, "device-b");

        handler.afterConnectionEstablished(first);
        handler.afterConnectionEstablished(second);

        assertThat(handler.activeConnectionCount())
                .as("上限为 1 时只应接受一条连接")
                .isEqualTo(1);

        // 1013 SERVICE_OVERLOAD 的语义是"稍后重试"，客户端的退避重连是正确响应。
        // 用 POLICY_VIOLATION 之类会让客户端认为是自己的问题而放弃重连。
        verify(second).close(CloseStatus.SERVICE_OVERLOAD);
        verify(first, never()).close(any());
    }

    /** 被拒绝的连接不进注册表，因此不会收到任何投递 */
    @Test
    void rejectedConnectionsDoNotReceiveEvents() throws Exception {
        RealtimeWebSocketHandler handler = handlerWith(1);
        WebSocketSession accepted = session(DemoUsers.ALICE, "device-a");
        WebSocketSession rejected = session(DemoUsers.BOB, "device-b");

        handler.afterConnectionEstablished(accepted);
        handler.afterConnectionEstablished(rejected);

        handler.emitToUser(DemoUsers.BOB.toString(), "message:created", Map.of("id", "m1"));

        verify(rejected, never()).sendMessage(any(TextMessage.class));
    }

    /** 断开连接后腾出的容量可以被新连接使用 */
    @Test
    void capacityIsReleasedWhenConnectionsClose() throws Exception {
        RealtimeWebSocketHandler handler = handlerWith(1);
        WebSocketSession first = session(DemoUsers.ALICE, "device-a");
        WebSocketSession second = session(DemoUsers.BOB, "device-b");

        handler.afterConnectionEstablished(first);
        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        handler.afterConnectionEstablished(second);

        assertThat(handler.activeConnectionCount()).isEqualTo(1);
        verify(second, never()).close(any());
    }

    private WebSocketSession session(UUID userId, String deviceId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of(
                WebSocketAuthenticationInterceptor.USER_ID, userId.toString(),
                WebSocketAuthenticationInterceptor.DEVICE_ID, deviceId
        ));
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(userId + "-session");
        return session;
    }
}
