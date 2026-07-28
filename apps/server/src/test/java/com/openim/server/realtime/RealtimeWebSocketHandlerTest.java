package com.openim.server.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.domain.InMemoryStore;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeWebSocketHandlerTest {
    @Test
    void isolatesTheSameDeviceIdAcrossDifferentUsers() throws Exception {
        InMemoryStore store = mock(InMemoryStore.class);
        when(store.listConversations(any())).thenReturn(List.of());
        RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(
                new ObjectMapper(),
                mock(Validator.class),
                store
        );
        WebSocketSession alice = session("alice", "shared-device");
        WebSocketSession bob = session("bob", "shared-device");

        handler.afterConnectionEstablished(alice);
        handler.afterConnectionEstablished(bob);
        handler.emitToDevice("bob", "shared-device", "rtc:offer", Map.of("roomId", "room-1"));

        verify(bob).sendMessage(any(TextMessage.class));
        verify(alice, never()).sendMessage(any(TextMessage.class));
    }

    private WebSocketSession session(String userId, String deviceId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of(
                WebSocketAuthenticationInterceptor.USER_ID, userId,
                WebSocketAuthenticationInterceptor.DEVICE_ID, deviceId
        ));
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(userId + "-session");
        return session;
    }
}
