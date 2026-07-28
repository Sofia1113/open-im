package com.openim.server.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.InMemoryStore;
import com.openim.server.domain.Requests.RtcSignal;
import com.openim.server.domain.Requests.SendMessage;
import com.openim.server.web.ApiException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 实时消息处理器
 *
 * 管理用户/设备/会话级别的连接注册，处理消息发送和 WebRTC 信令的转发。
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);
    private static final int MAX_SIGNAL_SIZE = 1_000_000;

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final InMemoryStore store;
    /** 用户 ID ↔ 会话集合映射 */
    private final Map<String, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();
    /** 用户 + 设备 ID ↔ 会话集合映射，避免不同账号复用 deviceId 时串流 */
    private final Map<DeviceSessionKey, Set<WebSocketSession>> deviceSessions = new ConcurrentHashMap<>();
    /** 会话 ID ↔ 会话集合映射 */
    private final Map<String, Set<WebSocketSession>> conversationSessions = new ConcurrentHashMap<>();

    public RealtimeWebSocketHandler(
            ObjectMapper objectMapper,
            Validator validator,
            InMemoryStore store
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.store = store;
    }

    /** 连接建立时：注册到用户/设备/会话三级映射，标记用户在线 */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = userId(session);
        String deviceId = deviceId(session);
        add(userSessions, userId, session);
        add(deviceSessions, new DeviceSessionKey(userId, deviceId), session);
        for (Conversation conversation : store.listConversations(userId)) {
            add(conversationSessions, conversation.id(), session);
        }
        store.setUserOnline(userId, true);
    }

    /** 处理收到的文本消息：解析命令信封并分发到对应处理器 */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String commandId = null;
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            if (!"command".equals(root.path("kind").asText())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR",
                        "实时消息格式不合法"
                );
            }
            commandId = requiredText(root, "commandId");
            String event = requiredText(root, "event");
            JsonNode payload = root.path("payload");
            switch (event) {
                case "message:send" -> handleSendMessage(session, commandId, payload);
                case "rtc:offer", "rtc:answer", "rtc:ice-candidate" ->
                        handleRtcSignal(session, commandId, event, payload);
                default -> throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "UNKNOWN_EVENT",
                        "不支持的实时事件"
                );
            }
        } catch (ApiException exception) {
            sendErrorAck(session, commandId, exception.code(), exception.getMessage());
        } catch (Exception exception) {
            log.error("Realtime command failed userId={} commandId={}",
                    userId(session), commandId, exception);
            sendErrorAck(session, commandId, "INTERNAL_ERROR", "服务暂时不可用");
        }
    }

    /** 处理发送消息命令 */
    private void handleSendMessage(
            WebSocketSession session,
            String commandId,
            JsonNode payload
    ) throws Exception {
        SendMessage input = objectMapper.treeToValue(payload, SendMessage.class);
        validate(input);
        ImMessage message = store.createMessage(userId(session), input);
        emitToConversation(message.conversationId(), "message:created", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "message", message
        ));
        sendSuccessAck(session, commandId, message);
    }

    /** 处理 WebRTC 信令转发（Offer/Answer/ICE Candidate） */
    private void handleRtcSignal(
            WebSocketSession session,
            String commandId,
            String event,
            JsonNode payload
    ) throws Exception {
        if (payload.toString().length() > MAX_SIGNAL_SIZE) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "信令载荷过大"
            );
        }
        RtcSignal input = objectMapper.treeToValue(payload, RtcSignal.class);
        validate(input);
        if (("rtc:offer".equals(event) || "rtc:answer".equals(event)) && input.sdp() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "SDP 不能为空"
            );
        }
        if ("rtc:ice-candidate".equals(event) && input.candidate() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "ICE candidate 不能为空"
            );
        }
        String fromUserId = userId(session);
        String targetDeviceId = store.validateRtcSignal(
                input.roomId(),
                fromUserId,
                input.targetUserId()
        );
        ObjectNode forwarded = objectMapper.valueToTree(input);
        forwarded.put("fromUserId", fromUserId);
        emitToDevice(input.targetUserId(), targetDeviceId, event, forwarded);
        sendSuccessAck(session, commandId, null);
    }

    /** 向指定用户的所有在线设备推送事件 */
    public void emitToUser(String userId, String event, Object payload) {
        emit(userSessions.get(userId), event, payload);
    }

    /** 向指定用户的指定设备推送事件 */
    public void emitToDevice(
            String userId,
            String deviceId,
            String event,
            Object payload
    ) {
        emit(deviceSessions.get(new DeviceSessionKey(userId, deviceId)), event, payload);
    }

    /** 向会话内的所有在线用户推送事件 */
    public void emitToConversation(String conversationId, String event, Object payload) {
        emit(conversationSessions.get(conversationId), event, payload);
    }

    private void emit(Set<WebSocketSession> sessions, String event, Object payload) {
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("kind", "event");
        envelope.put("event", event);
        envelope.set("payload", objectMapper.valueToTree(payload));
        sendToSessions(sessions, envelope);
    }

    private void sendSuccessAck(
            WebSocketSession session,
            String commandId,
            Object data
    ) {
        if (commandId == null) {
            return;
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("kind", "ack");
        envelope.put("commandId", commandId);
        envelope.put("ok", true);
        if (data == null) {
            envelope.putNull("data");
        } else {
            envelope.set("data", objectMapper.valueToTree(data));
        }
        send(session, envelope);
    }

    private void sendErrorAck(
            WebSocketSession session,
            String commandId,
            String code,
            String message
    ) {
        if (commandId == null) {
            return;
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("kind", "ack");
        envelope.put("commandId", commandId);
        envelope.put("ok", false);
        ObjectNode error = envelope.putObject("error");
        error.put("code", code);
        error.put("message", message);
        send(session, envelope);
    }

    private void sendToSessions(Set<WebSocketSession> sessions, JsonNode payload) {
        for (WebSocketSession session : sessions) {
            send(session, payload);
        }
    }

    private void send(WebSocketSession session, JsonNode payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        } catch (IOException exception) {
            log.warn("Unable to send realtime event sessionId={}", session.getId());
        }
    }

    private <T> void validate(T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "请求参数不合法"
            );
        }
    }

    private String requiredText(JsonNode root, String field) {
        String value = root.path(field).asText("");
        if (value.isBlank() || value.length() > 128) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    field + " 不合法"
            );
        }
        return value;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = userId(session);
        remove(userSessions, userId, session);
        remove(deviceSessions, new DeviceSessionKey(userId, deviceId(session)), session);
        for (Set<WebSocketSession> sessions : conversationSessions.values()) {
            sessions.remove(session);
        }
        Set<WebSocketSession> remaining = userSessions.get(userId);
        if (remaining == null || remaining.isEmpty()) {
            store.setUserOnline(userId, false);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Realtime transport error sessionId={}", session.getId());
    }

    private <K> void add(
            Map<K, Set<WebSocketSession>> registry,
            K key,
            WebSocketSession session
    ) {
        registry.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    private <K> void remove(
            Map<K, Set<WebSocketSession>> registry,
            K key,
            WebSocketSession session
    ) {
        Set<WebSocketSession> sessions = registry.get(key);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            registry.remove(key, sessions);
        }
    }

    private String userId(WebSocketSession session) {
        return (String) session.getAttributes().get(
                WebSocketAuthenticationInterceptor.USER_ID
        );
    }

    private String deviceId(WebSocketSession session) {
        return (String) session.getAttributes().get(
                WebSocketAuthenticationInterceptor.DEVICE_ID
        );
    }

    private record DeviceSessionKey(String userId, String deviceId) {
    }
}
