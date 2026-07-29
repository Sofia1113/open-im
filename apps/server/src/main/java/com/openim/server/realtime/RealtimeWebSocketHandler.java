package com.openim.server.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.Requests.RtcSignal;
import com.openim.server.domain.Requests.SendMessage;
import com.openim.server.domain.service.CallService;
import com.openim.server.domain.service.ConversationService;
import com.openim.server.domain.service.MessageService;
import com.openim.server.domain.service.UserService;
import com.openim.server.observability.DomainMetrics;
import com.openim.server.ratelimit.RateLimitPolicy;
import com.openim.server.ratelimit.RateLimiter;
import com.openim.server.web.ApiException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 实时消息处理器
 *
 * 管理用户/设备/会话级别的连接注册，处理消息发送和 WebRTC 信令的转发。
 *
 * Redis 迁移后的变化：
 * <ul>
 *   <li>在线状态由 {@link PresenceService} 维护（Redis TTL），不再写 DB 的 presence 列</li>
 *   <li>消息广播和信令转发通过 {@link RealtimeBroadcastService}，可跨实例投递</li>
 *   <li>{@code broadcastService} 用 {@code @Lazy} 注入打破循环依赖</li>
 * </ul>
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    private static final int MAX_SIGNAL_SIZE = 32 * 1024;

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final CallService callService;
    private final UserService userService;
    private final PresenceService presenceService;
    private final RateLimiter rateLimiter;
    private final DomainMetrics metrics;

    /**
     * @Lazy 打破循环依赖：
     * RealtimeWebSocketHandler → RealtimeBroadcastService → RealtimeWebSocketHandler
     * Spring 为 broadcastService 创建延迟代理，首次调用时才真正初始化。
     */
    @Lazy
    @Autowired
    private RealtimeBroadcastService broadcastService;

    private final int sendBufferSizeLimit;
    private final int sendTimeLimitMillis;
    private final long pongTimeoutMillis;

    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userSessions = new ConcurrentHashMap<>();
    private final Map<DeviceSessionKey, Set<String>> deviceSessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> conversationSessions = new ConcurrentHashMap<>();

    public RealtimeWebSocketHandler(
            ObjectMapper objectMapper,
            Validator validator,
            ConversationService conversationService,
            MessageService messageService,
            CallService callService,
            UserService userService,
            PresenceService presenceService,
            RateLimiter rateLimiter,
            DomainMetrics metrics,
            MeterRegistry meterRegistry,
            @Value("${openim.realtime.send-buffer-size:524288}") int sendBufferSizeLimit,
            @Value("${openim.realtime.send-time-limit-millis:10000}") int sendTimeLimitMillis,
            @Value("${openim.realtime.pong-timeout-millis:90000}") long pongTimeoutMillis
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.callService = callService;
        this.userService = userService;
        this.presenceService = presenceService;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.sendBufferSizeLimit = sendBufferSizeLimit;
        this.sendTimeLimitMillis = sendTimeLimitMillis;
        this.pongTimeoutMillis = pongTimeoutMillis;
        Gauge.builder("openim.realtime.connections", this,
                        RealtimeWebSocketHandler::activeConnectionCount)
                .description("当前活跃的 WebSocket 连接数")
                .register(meterRegistry);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = userId(session);
        String deviceId = deviceId(session);
        WebSocketSession concurrent = new ConcurrentWebSocketSessionDecorator(
                session, sendTimeLimitMillis, sendBufferSizeLimit);

        String sessionId = session.getId();
        sessions.put(sessionId, new SessionEntry(concurrent, userId, deviceId));
        add(userSessions, userId, sessionId);
        add(deviceSessions, new DeviceSessionKey(userId, deviceId), sessionId);
        for (Conversation conversation : conversationService.listConversations(userUuid(session))) {
            add(conversationSessions, conversation.id(), sessionId);
        }
        // presence 写 Redis（带 TTL），不再写 DB 的 presence 列
        presenceService.markOnline(userUuid(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        touch(session.getId());
        String commandId = null;
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            if (!"command".equals(root.path("kind").asText())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "实时消息格式不合法");
            }
            commandId = requiredText(root, "commandId");
            String event = requiredText(root, "event");
            JsonNode payload = root.path("payload");
            switch (event) {
                case "message:send" -> {
                    requireQuota(session, RateLimitPolicy.SEND_MESSAGE);
                    handleSendMessage(session, commandId, payload);
                }
                case "rtc:offer", "rtc:answer", "rtc:ice-candidate" -> {
                    requireQuota(session, RateLimitPolicy.RTC_SIGNAL);
                    handleRtcSignal(session, commandId, event, payload);
                }
                default -> throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_EVENT", "不支持的实时事件");
            }
        } catch (ApiException exception) {
            sendErrorAck(session, commandId, exception.code(), exception.getMessage());
        } catch (Exception exception) {
            log.error("Realtime command failed userId={} commandId={}", userId(session), commandId, exception);
            sendErrorAck(session, commandId, "INTERNAL_ERROR", "服务暂时不可用");
        }
    }

    /** pong 回应心跳：刷新连接存活时间 + 续期 Redis presence TTL */
    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        touch(session.getId());
        presenceService.markOnline(userUuid(session));
    }

    private void requireQuota(WebSocketSession session, RateLimitPolicy policy) {
        RateLimiter.Decision decision = rateLimiter.tryConsume(policy, "user:" + userId(session));
        if (!decision.allowed()) {
            metrics.recordRateLimitRejection();
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "操作过于频繁，请稍后再试");
        }
    }

    private void handleSendMessage(WebSocketSession session, String commandId, JsonNode payload) throws Exception {
        SendMessage input = objectMapper.treeToValue(payload, SendMessage.class);
        validate(input);
        ImMessage message = messageService.createMessage(userUuid(session), input);
        // 通过 broadcastService 广播：本实例本地投递 + Redis Pub/Sub 发往其他实例
        broadcastService.broadcastToConversation(message.conversationId(), "message:created", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "message", message
        ));
        sendSuccessAck(session, commandId, message);
    }

    private void handleRtcSignal(
            WebSocketSession session,
            String commandId,
            String event,
            JsonNode payload
    ) throws Exception {
        if (payload.toString().length() > MAX_SIGNAL_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "信令载荷过大");
        }
        RtcSignal input = objectMapper.treeToValue(payload, RtcSignal.class);
        validate(input);
        if (("rtc:offer".equals(event) || "rtc:answer".equals(event)) && input.sdp() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "SDP 不能为空");
        }
        if ("rtc:ice-candidate".equals(event) && input.candidate() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ICE candidate 不能为空");
        }
        String fromUserId = userId(session);
        String targetDeviceId = callService.validateRtcSignal(
                input.roomId(), userUuid(session), input.targetUserId());
        ObjectNode forwarded = objectMapper.valueToTree(input);
        forwarded.put("fromUserId", fromUserId);
        // 信令目标设备可能在另一个实例上，必须经过 broadcastService
        broadcastService.broadcastToDevice(input.targetUserId(), targetDeviceId, event, forwarded);
        sendSuccessAck(session, commandId, null);
    }

    // ── 本地投递（仅当前实例，供 RealtimeBroadcastService 和 RealtimeEventSubscriber 调用）─────

    public void emitToUser(String userId, String event, Object payload) {
        emit(userSessions.get(userId), event, payload);
    }

    public void emitToDevice(String userId, String deviceId, String event, Object payload) {
        emit(deviceSessions.get(new DeviceSessionKey(userId, deviceId)), event, payload);
    }

    public void emitToConversation(String conversationId, String event, Object payload) {
        emit(conversationSessions.get(conversationId), event, payload);
    }

    public int activeConnectionCount() {
        return sessions.size();
    }

    private void emit(Set<String> sessionIds, String event, Object payload) {
        if (sessionIds == null || sessionIds.isEmpty()) return;
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("kind", "event");
        envelope.put("event", event);
        envelope.set("payload", objectMapper.valueToTree(payload));
        for (String sessionId : Set.copyOf(sessionIds)) {
            SessionEntry entry = sessions.get(sessionId);
            if (entry != null) send(entry.session(), envelope);
        }
    }

    private void sendSuccessAck(WebSocketSession session, String commandId, Object data) {
        if (commandId == null) return;
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("kind", "ack");
        envelope.put("commandId", commandId);
        envelope.put("ok", true);
        if (data == null) envelope.putNull("data");
        else envelope.set("data", objectMapper.valueToTree(data));
        sendToOriginating(session, envelope);
    }

    private void sendErrorAck(WebSocketSession session, String commandId, String code, String message) {
        if (commandId == null) return;
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("kind", "ack");
        envelope.put("commandId", commandId);
        envelope.put("ok", false);
        ObjectNode error = envelope.putObject("error");
        error.put("code", code);
        error.put("message", message);
        sendToOriginating(session, envelope);
    }

    private void sendToOriginating(WebSocketSession session, JsonNode payload) {
        SessionEntry entry = sessions.get(session.getId());
        send(entry == null ? session : entry.session(), payload);
    }

    private void send(WebSocketSession session, JsonNode payload) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException | IllegalStateException exception) {
            metrics.recordRealtimeSendFailure();
            log.warn("Unable to send realtime event sessionId={} reason={}",
                    session.getId(), exception.getClass().getSimpleName());
        }
    }

    /**
     * 心跳：向所有连接发 ping，清理死连接，同时续期活跃用户的 Redis presence TTL。
     */
    @Scheduled(fixedDelayString = "${openim.realtime.heartbeat-interval-millis:30000}")
    public void sendHeartbeats() {
        Instant deadline = Instant.now().minusMillis(pongTimeoutMillis);
        // 收集活跃用户，用于批量续期 presence
        Set<UUID> activeUsers = ConcurrentHashMap.newKeySet();
        for (SessionEntry entry : sessions.values()) {
            if (entry.lastSeenAt().isBefore(deadline)) {
                log.info("Closing stale realtime connection sessionId={} userId=",
                        entry.session().getId(), entry.userId());
                closeQuietly(entry.session());
                continue;
            }
            try {
                if (entry.session().isOpen()) {
                    entry.session().sendMessage(new PingMessage());
                    activeUsers.add(UUID.fromString(entry.userId()));
                }
            } catch (IOException | IllegalStateException exception) {
                closeQuietly(entry.session());
            }
        }
        // 所有心跳都发完后，刷新 Redis TTL；批量刷新比一次一次 EXPIRE 少往返
        activeUsers.forEach(presenceService::markOnline);
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (IOException ignored) {
        }
    }

    private void touch(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry != null) entry.touch();
    }

    private <T> void validate(T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法");
        }
    }

    private String requiredText(JsonNode root, String field) {
        String value = root.path(field).asText("");
        if (value.isBlank() || value.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " 不合法");
        }
        return value;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        String userId = userId(session);
        sessions.remove(sessionId);
        remove(userSessions, userId, sessionId);
        remove(deviceSessions, new DeviceSessionKey(userId, deviceId(session)), sessionId);
        for (Set<String> ids : conversationSessions.values()) {
            ids.remove(sessionId);
        }
        Set<String> remaining = userSessions.get(userId);
        if (remaining == null || remaining.isEmpty()) {
            // 该用户所有连接都断开了：删除 Redis presence key 并更新 DB last_seen_at
            userService.setUserOnline(userUuid(session), false);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Realtime transport error sessionId={}", session.getId());
    }

    private <K> void add(Map<K, Set<String>> registry, K key, String sessionId) {
        registry.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    private <K> void remove(Map<K, Set<String>> registry, K key, String sessionId) {
        Set<String> ids = registry.get(key);
        if (ids == null) return;
        ids.remove(sessionId);
        if (ids.isEmpty()) registry.remove(key, ids);
    }

    private String userId(WebSocketSession session) {
        return (String) session.getAttributes().get(WebSocketAuthenticationInterceptor.USER_ID);
    }

    private String deviceId(WebSocketSession session) {
        return (String) session.getAttributes().get(WebSocketAuthenticationInterceptor.DEVICE_ID);
    }

    private UUID userUuid(WebSocketSession session) {
        return UUID.fromString(userId(session));
    }

    private static final class SessionEntry {
        private final WebSocketSession session;
        private final String userId;
        private final String deviceId;
        private volatile Instant lastSeenAt = Instant.now();

        private SessionEntry(WebSocketSession session, String userId, String deviceId) {
            this.session = session;
            this.userId = userId;
            this.deviceId = deviceId;
        }

        private WebSocketSession session() { return session; }
        private String userId() { return userId; }
        @SuppressWarnings("unused") private String deviceId() { return deviceId; }
        private Instant lastSeenAt() { return lastSeenAt; }
        private void touch() { lastSeenAt = Instant.now(); }
    }

    private record DeviceSessionKey(String userId, String deviceId) {}
}
