package com.openim.server.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.domain.service.CallService;
import com.openim.server.domain.service.ConversationService;
import com.openim.server.domain.service.MessageService;
import com.openim.server.domain.repository.OutboxRepository;
import com.openim.server.domain.service.UserService;
import com.openim.server.observability.DomainMetrics;
import com.openim.server.ratelimit.RateLimiter;
import com.openim.server.support.DemoUsers;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeWebSocketHandlerTest {
    private final MeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void isolatesTheSameDeviceIdAcrossDifferentUsers() throws Exception {
        ConversationService conversationService = mock(ConversationService.class);
        when(conversationService.listConversations(any())).thenReturn(List.of());
        RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(
                new ObjectMapper(),
                mock(Validator.class),
                conversationService,
                mock(MessageService.class),
                mock(CallService.class),
                mock(UserService.class),
                new RateLimiter(true, 1_000, 30),
                new DomainMetrics(registry, mock(OutboxRepository.class)),
                registry,
                512 * 1024,
                10_000,
                90_000
        );
        WebSocketSession alice = session(DemoUsers.ALICE, "shared-device");
        WebSocketSession bob = session(DemoUsers.BOB, "shared-device");

        handler.afterConnectionEstablished(alice);
        handler.afterConnectionEstablished(bob);
        handler.emitToDevice(DemoUsers.BOB.toString(), "shared-device", "rtc:offer",
                Map.of("roomId", "room-1"));

        verify(bob).sendMessage(any(TextMessage.class));
        verify(alice, never()).sendMessage(any(TextMessage.class));
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
