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
import com.openim.server.ratelimit.RateLimitPolicy;
import com.openim.server.ratelimit.RateLimiter;
import com.openim.server.web.ApiException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
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
 * 三点与可靠性直接相关的设计：
 * <ul>
 *   <li>每条连接用 {@link ConcurrentWebSocketSessionDecorator} 包装，写入变为带缓冲的非阻塞操作。
 *       此前直接在 {@code synchronized(session)} 里同步 {@code sendMessage}，
 *       一个慢客户端就能占住 Tomcat 工作线程，群发场景下会级联拖垮整个线程池。</li>
 *   <li>注册表按 sessionId 索引而非持有 session 对象，因为装饰器与原始 session 不相等，
 *       连接关闭回调拿到的是后者。</li>
 *   <li>应用层心跳先于 TCP 发现半开连接：对端断电或网络中断时 FIN 可能永远不到，
 *       仅靠容器空闲超时会让连接和其占用的注册表项长期滞留。</li>
 * </ul>
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    /**
     * 单条信令载荷上限。
     *
     * 远小于容器的文本帧上限（64KB），使超限由业务层用明确错误码拒绝，
     * 而不是让容器直接断连。典型 SDP 为数 KB，32KB 已有充足余量。
     */
    private static final int MAX_SIGNAL_SIZE = 32 * 1024;

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final CallService callService;
    private final UserService userService;
    private final RateLimiter rateLimiter;

    /** 发送缓冲上限：超过说明客户端消费不过来，终止连接而不是继续堆积 */
    private final int sendBufferSizeLimit;
    /** 单次发送的时间上限 */
    private final int sendTimeLimitMillis;
    /** 超过该时长没有收到 pong 即判定连接已死 */
    private final long pongTimeoutMillis;

    /** sessionId ↔ 连接状态 */
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    /** 用户 ID ↔ sessionId 集合 */
    private final Map<String, Set<String>> userSessions = new ConcurrentHashMap<>();
    /** 用户 + 设备 ID ↔ sessionId 集合，避免不同账号复用 deviceId 时串流 */
    private final Map<DeviceSessionKey, Set<String>> deviceSessions = new ConcurrentHashMap<>();
    /** 会话 ID ↔ sessionId 集合 */
    private final Map<String, Set<String>> conversationSessions = new ConcurrentHashMap<>();

    public RealtimeWebSocketHandler(
            ObjectMapper objectMapper,
            Validator validator,
            ConversationService conversationService,
            MessageService messageService,
            CallService callService,
            UserService userService,
            RateLimiter rateLimiter,
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
        this.rateLimiter = rateLimiter;
        this.sendBufferSizeLimit = sendBufferSizeLimit;
        this.sendTimeLimitMillis = sendTimeLimitMillis;
        this.pongTimeoutMillis = pongTimeoutMillis;
    }

    /** 连接建立时：注册到用户/设备/会话三级映射，标记用户在线 */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = userId(session);
        String deviceId = deviceId(session);
        // 包装后写入走缓冲队列；超出缓冲或超时由装饰器终止连接
        WebSocketSession concurrent = new ConcurrentWebSocketSessionDecorator(
                session, sendTimeLimitMillis, sendBufferSizeLimit);

        String sessionId = session.getId();
        sessions.put(sessionId, new SessionEntry(concurrent, userId, deviceId));
        add(userSessions, userId, sessionId);
        add(deviceSessions, new DeviceSessionKey(userId, deviceId), sessionId);
        for (Conversation conversation : conversationService.listConversations(userUuid(session))) {
            add(conversationSessions, conversation.id(), sessionId);
        }
        userService.setUserOnline(userUuid(session), true);
    }

    /** 处理收到的文本消息：解析命令信封并分发到对应处理器 */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        touch(session.getId());
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
                case "message:send" -> {
                    requireQuota(session, RateLimitPolicy.SEND_MESSAGE);
                    handleSendMessage(session, commandId, payload);
                }
                case "rtc:offer", "rtc:answer", "rtc:ice-candidate" -> {
                    requireQuota(session, RateLimitPolicy.RTC_SIGNAL);
                    handleRtcSignal(session, commandId, event, payload);
                }
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

    /** 客户端回应心跳：刷新存活时间 */
    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        touch(session.getId());
    }

    /**
     * 校验实时命令配额。
     *
     * WebSocket 命令不经过 HTTP 拦截器，必须在这里单独限流——
     * 否则一条已建立的连接就能绕过全部 HTTP 侧的速率约束。
     */
    private void requireQuota(WebSocketSession session, RateLimitPolicy policy) {
        RateLimiter.Decision decision = rateLimiter.tryConsume(policy, "user:" + userId(session));
        if (!decision.allowed()) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED",
                    "操作过于频繁，请稍后再试"
            );
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
        ImMessage message = messageService.createMessage(userUuid(session), input);
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
        String targetDeviceId = callService.validateRtcSignal(
                input.roomId(),
                userUuid(session),
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

    /** 当前活跃连接数，供可观测性指标使用 */
    public int activeConnectionCount() {
        return sessions.size();
    }

    private void emit(Set<String> sessionIds, String event, Object payload) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("kind", "event");
        envelope.put("event", event);
        envelope.set("payload", objectMapper.valueToTree(payload));
        // 复制一份再遍历：发送失败会移除注册项，直接遍历原集合可能触发并发修改
        for (String sessionId : Set.copyOf(sessionIds)) {
            SessionEntry entry = sessions.get(sessionId);
            if (entry != null) {
                send(entry.session(), envelope);
            }
        }
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
        sendToOriginating(session, envelope);
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
        sendToOriginating(session, envelope);
    }

    /**
     * 回复命令来源连接。
     *
     * 处理器回调收到的是原始 session，需要换回注册时的装饰器，
     * 否则这次写入会绕过缓冲队列重新变成阻塞写。
     */
    private void sendToOriginating(WebSocketSession session, JsonNode payload) {
        SessionEntry entry = sessions.get(session.getId());
        send(entry == null ? session : entry.session(), payload);
    }

    private void send(WebSocketSession session, JsonNode payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (IOException | IllegalStateException exception) {
            // IllegalStateException 来自装饰器：缓冲区超限或发送超时，连接已被它关闭
            log.warn("Unable to send realtime event sessionId={} reason={}",
                    session.getId(), exception.getClass().getSimpleName());
        }
    }

    /**
     * 心跳：向所有连接发 ping，并清理长时间没有回应的连接。
     *
     * 半开连接不会触发 onClose，只有主动探测才能发现；否则它们会一直占据注册表，
     * 使该用户显示为在线，发给他的消息也会静默丢失。
     */
    @Scheduled(fixedDelayString = "${openim.realtime.heartbeat-interval-millis:30000}")
    public void sendHeartbeats() {
        Instant deadline = Instant.now().minusMillis(pongTimeoutMillis);
        for (SessionEntry entry : sessions.values()) {
            if (entry.lastSeenAt().isBefore(deadline)) {
                log.info("Closing stale realtime connection sessionId={} userId={}",
                        entry.session().getId(), entry.userId());
                closeQuietly(entry.session());
                continue;
            }
            try {
                if (entry.session().isOpen()) {
                    entry.session().sendMessage(new org.springframework.web.socket.PingMessage());
                }
            } catch (IOException | IllegalStateException exception) {
                closeQuietly(entry.session());
            }
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (IOException ignored) {
            // 连接已经不可用，关闭失败无需处理；注册表项由 afterConnectionClosed 清理
        }
    }

    private void touch(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry != null) {
            entry.touch();
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
        if (ids == null) {
            return;
        }
        ids.remove(sessionId);
        if (ids.isEmpty()) {
            registry.remove(key, ids);
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

    /** 握手阶段已校验过令牌中的用户标识，此处解析必然成功 */
    private UUID userUuid(WebSocketSession session) {
        return UUID.fromString(userId(session));
    }

    /** 单条连接的状态。lastSeenAt 由命令和 pong 共同刷新 */
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

        private WebSocketSession session() {
            return session;
        }

        private String userId() {
            return userId;
        }

        @SuppressWarnings("unused")
        private String deviceId() {
            return deviceId;
        }

        private Instant lastSeenAt() {
            return lastSeenAt;
        }

        private void touch() {
            lastSeenAt = Instant.now();
        }
    }

    private record DeviceSessionKey(String userId, String deviceId) {
    }
}
