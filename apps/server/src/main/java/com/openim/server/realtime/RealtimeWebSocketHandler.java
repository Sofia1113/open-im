package com.openim.server.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import io.micrometer.core.instrument.Counter;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 实时消息处理器
 *
 * 管理用户与设备级别的连接注册，处理消息发送和 WebRTC 信令的转发。
 *
 * <ul>
 *   <li>在线状态由 {@link PresenceService} 维护（Redis TTL），不再写 DB 的 presence 列</li>
 *   <li>消息广播和信令转发通过 {@link RealtimeBroadcastService}，可跨实例投递</li>
 *   <li>按会话投递时实时查询数据库成员关系，不缓存会话 → 连接的映射
 *       （见 {@link #emitToConversation}）</li>
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

    /**
     * 单实例连接数上限。
     *
     * 默认 10000。每条连接的常驻成本主要是发送缓冲区（上限
     * {@code send-buffer-size}，默认 512KB，但只在积压时才涨到那么大）
     * 加上注册表里的几个引用。10000 条在 1Gi 内存上限下有足够余量，
     * 同时远高于任何单实例的实际预期负载——它是防止失控的闸门，不是容量规划。
     *
     * 真正的容量数字需要压测得出，见 load-test/。
     */
    private final int maxConnections;

    /** 因达到上限而被拒绝的连接数。非零说明需要扩容或调高上限 */
    private final Counter rejectedConnections;

    /**
     * 连接注册表。
     *
     * 刻意<b>不</b>维护 conversationId → sessionId 的索引：那份索引只能在连接建立
     * 的那一刻按当时的成员关系填充，此后成员变动（加群、退群、被移出）都不会
     * 反映进去，导致新成员收不到消息、已退出成员仍在接收。
     * 会话成员关系的真实来源是数据库，因此按会话投递时实时查库解析成员，
     * 再落到 {@code userSessions} 上路由。
     */
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userSessions = new ConcurrentHashMap<>();
    private final Map<DeviceSessionKey, Set<String>> deviceSessions = new ConcurrentHashMap<>();

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
            @Value("${openim.realtime.pong-timeout-millis:90000}") long pongTimeoutMillis,
            @Value("${openim.realtime.max-connections:10000}") int maxConnections
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
        this.maxConnections = maxConnections;
        Gauge.builder("openim.realtime.connections", this,
                        RealtimeWebSocketHandler::activeConnectionCount)
                .description("当前活跃的 WebSocket 连接数")
                .register(meterRegistry);
        Gauge.builder("openim.realtime.max_connections", () -> maxConnections)
                .description("单实例连接数上限，用于计算容量水位")
                .register(meterRegistry);
        this.rejectedConnections = Counter.builder("openim.realtime.connections_rejected")
                .description("因达到单实例上限而被拒绝的连接数；非零即需扩容")
                .register(meterRegistry);
    }

    /**
     * 连接建立：注册会话并标记用户在线。
     *
     * 注册顺序刻意固定为"先注册表、后 presence"：消息路由只查
     * {@code sessions}/{@code userSessions}/{@code deviceSessions}，presence
     * 只服务于在线状态展示。若反序，会存在 presence 已在线而连接尚不可达的
     * 窗口——这一瞬间到达的广播将投不出去。注册全部完成前容器不会分发任何帧，
     * 因此不存在"已收消息但查不到会话"的路径。
     *
     * 容量闸门在此处而非注册后：实例满员时直接以 1013 拒绝（见
     * {@link #closeWith}），让负载均衡器把新连接导向其他实例；
     * 不设闸门的话连接数无界增长，最终是 OOMKill 而非优雅拒绝。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = userId(session);
        String deviceId = deviceId(session);

        // 容量闸门。此前单实例没有任何连接数上限——每条连接都持有一个
        // ConcurrentWebSocketSessionDecorator（含最大 512KB 的发送缓冲区），
        // 连接数无界意味着内存占用无界，最终是 OOMKill 而不是优雅拒绝。
        // 主动拒绝的好处是负载均衡器能把新连接导向其他实例。
        if (sessions.size() >= maxConnections) {
            rejectedConnections.increment();
            log.warn("Rejecting realtime connection: instance at capacity {}/{} userId={}",
                    sessions.size(), maxConnections, userId);
            closeWith(session, CloseStatus.SERVICE_OVERLOAD);
            return;
        }

        WebSocketSession concurrent = new ConcurrentWebSocketSessionDecorator(
                session, sendTimeLimitMillis, sendBufferSizeLimit);

        String sessionId = session.getId();
        sessions.put(sessionId, new SessionEntry(concurrent, userId, deviceId));
        add(userSessions, userId, sessionId);
        add(deviceSessions, new DeviceSessionKey(userId, deviceId), sessionId);
        // presence 写 Redis（带 TTL），不再写 DB 的 presence 列
        presenceService.markOnline(userUuid(session));
    }

    /**
     * 以指定状态码关闭连接。
     *
     * {@code SERVICE_OVERLOAD}（1013）是有意选择：它对客户端的语义是
     * "稍后重试"，而 {@code POLICY_VIOLATION} 之类会让客户端认为是自己的问题
     * 而放弃重连。客户端的指数退避重连逻辑对 1013 是正确的响应。
     */
    private void closeWith(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // 连接可能已经断了，无需处理
        }
    }

    /**
     * 处理客户端实时命令帧。
     *
     * 帧格式：{@code {kind: "command", commandId, event, payload}}。按 event
     * 分发到消息发送或 RTC 信令处理；任何失败都回带 commandId 的错误 ack，
     * 让客户端能把失败与具体请求对上（命令帧乱序到达时尤其重要）。
     */
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

    /** 限流检查：按用户维度消费配额，超限记录指标并抛出 RATE_LIMITED */
    private void requireQuota(WebSocketSession session, RateLimitPolicy policy) {
        RateLimiter.Decision decision = rateLimiter.tryConsume(policy, "user:" + userId(session));
        if (!decision.allowed()) {
            metrics.recordRateLimitRejection();
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "操作过于频繁，请稍后再试");
        }
    }

    /** 发送消息：落库后经 broadcastService 广播（本实例本地投递 + Redis 流发往其他实例），再回成功 ack */
    private void handleSendMessage(WebSocketSession session, String commandId, JsonNode payload) throws Exception {
        SendMessage input = objectMapper.treeToValue(payload, SendMessage.class);
        validate(input);
        ImMessage message = messageService.createMessage(userUuid(session), input);
        // 通过 broadcastService 广播：本实例本地投递 + Redis Pub/Sub 发往其他实例。
        // 事件经 RealtimeEvent 构造，与 Outbox 补偿路径共用同一份载荷结构和去重标识
        broadcastService.broadcastToConversation(
                message.conversationId(), RealtimeEvent.messageCreated(message));
        sendSuccessAck(session, commandId, message);
    }

    /**
     * 处理 WebRTC 信令：校验载荷大小与必填字段后转发到目标设备。
     * 目标设备可能在其他实例上，必须走 broadcastService 的跨实例投递路径。
     */
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

    // ── 本地投递（仅当前实例，供 RealtimeBroadcastService 和 RealtimeEventStreamConsumer 调用）──

    /** 向用户的所有本地连接投递事件（仅当前实例；跨实例投递由广播服务负责） */
    public void emitToUser(String userId, String event, Object payload) {
        emit(userSessions.get(userId), event, payload);
    }

    /** 向用户指定设备的所有本地连接投递事件（仅当前实例；RTC 信令专用） */
    public void emitToDevice(String userId, String deviceId, String event, Object payload) {
        emit(deviceSessions.get(new DeviceSessionKey(userId, deviceId)), event, payload);
    }

    /**
     * 向会话成员投递。
     *
     * 成员关系实时查库而非依赖连接时的快照：加群的用户立刻能收到消息，
     * 退群的用户立刻停止接收，无需等到重连。
     *
     * 本实例没有该会话任何成员的连接时（多实例部署下的常态），
     * 提前返回以避免无谓的查询——这是最常见的情况。
     */
    public void emitToConversation(String conversationId, String event, Object payload) {
        if (userSessions.isEmpty()) {
            return;
        }
        UUID id = parseUuid(conversationId);
        if (id == null) {
            log.warn("Skipping conversation broadcast for malformed id");
            return;
        }
        Set<String> targets = new java.util.HashSet<>();
        for (UUID memberId : conversationService.listActiveMemberIds(id)) {
            Set<String> memberSessions = userSessions.get(memberId.toString());
            if (memberSessions != null) {
                targets.addAll(memberSessions);
            }
        }
        emit(targets, event, payload);
    }

    /** 解析 UUID，非法格式返回 null（调用方据此跳过广播并告警） */
    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 当前实例的活跃连接数（供监控 Gauge 与容量水位判断使用） */
    public int activeConnectionCount() {
        return sessions.size();
    }

    /** 向一组会话 ID 投递事件；集合先拷贝再遍历，避免并发断连修改底层集合 */
    private void emit(java.util.Collection<String> sessionIds, String event, Object payload) {
        if (sessionIds == null || sessionIds.isEmpty()) return;
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("kind", "event");
        envelope.put("event", event);
        envelope.set("payload", objectMapper.valueToTree(payload));
        // 拷贝一份再遍历：并发断连会修改底层集合
        for (String sessionId : List.copyOf(sessionIds)) {
            SessionEntry entry = sessions.get(sessionId);
            if (entry != null) send(entry.session(), envelope);
        }
    }

    /** 回成功 ack：携带可选 data，客户端按 commandId 与请求配对 */
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

    /** 回错误 ack：携带错误码与信息，客户端据此区分是否值得重试 */
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

    /** 回 ack 给发起方：优先取注册表中包装过的并发安全会话，未注册时退回原始会话 */
    private void sendToOriginating(WebSocketSession session, JsonNode payload) {
        SessionEntry entry = sessions.get(session.getId());
        send(entry == null ? session : entry.session(), payload);
    }

    /** 发送一帧 JSON 文本：失败仅记录指标与日志，断连清理交给心跳与连接关闭回调 */
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
                log.info("Closing stale realtime connection sessionId={} userId={}",
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

    /** 静默关闭连接：忽略"连接已断开"等可预期异常 */
    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (IOException ignored) {
        }
    }

    /** 刷新会话的最后活跃时间，供心跳判定连接是否已死 */
    private void touch(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry != null) entry.touch();
    }

    /** Bean Validation 校验，存在违规时抛 VALIDATION_ERROR */
    private <T> void validate(T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法");
        }
    }

    /** 取必填文本字段：缺失、空白或超长（>128 字符）即抛 VALIDATION_ERROR */
    private String requiredText(JsonNode root, String field) {
        String value = root.path(field).asText("");
        if (value.isBlank() || value.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " 不合法");
        }
        return value;
    }

    /**
     * 连接关闭：从三张注册表移除会话并处理离线。
     *
     * 移除后该连接立即从所有后续广播的候选集中消失；同用户的其他连接不受影响。
     * 只有在该用户<b>没有任何剩余连接</b>时才标记离线——多设备在线时贸然下线，
     * 其他设备会被错误显示为离线。
     *
     * 传输异常（{@link #handleTransportError}）和心跳清理（{@link #sendHeartbeats}
     * 关闭超时连接）最终都会触发本回调，因此所有清理只需在此处做一遍。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        String userId = userId(session);
        sessions.remove(sessionId);
        remove(userSessions, userId, sessionId);
        remove(deviceSessions, new DeviceSessionKey(userId, deviceId(session)), sessionId);
        Set<String> remaining = userSessions.get(userId);
        if (remaining == null || remaining.isEmpty()) {
            // 该用户所有连接都断开了：删除 Redis presence key 并更新 DB last_seen_at
            userService.setUserOnline(userUuid(session), false);
        }
    }

    /** 传输层异常：仅记录日志，连接关闭与清理由容器随后回调 {@link #afterConnectionClosed} 完成 */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Realtime transport error sessionId={}", session.getId());
    }

    /** 注册表写入：key 不存在时先建并发集合再加会话 ID（computeIfAbsent 原子完成） */
    private <K> void add(Map<K, Set<String>> registry, K key, String sessionId) {
        registry.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    /** 注册表移除：集合清空后连同 key 一起删除，避免空集合残留 */
    private <K> void remove(Map<K, Set<String>> registry, K key, String sessionId) {
        Set<String> ids = registry.get(key);
        if (ids == null) return;
        ids.remove(sessionId);
        if (ids.isEmpty()) registry.remove(key, ids);
    }

    /** 从会话属性取用户 ID（握手鉴权时注入） */
    private String userId(WebSocketSession session) {
        return (String) session.getAttributes().get(WebSocketAuthenticationInterceptor.USER_ID);
    }

    /** 从会话属性取设备 ID（握手鉴权时注入） */
    private String deviceId(WebSocketSession session) {
        return (String) session.getAttributes().get(WebSocketAuthenticationInterceptor.DEVICE_ID);
    }

    /** 用户 ID 解析为 UUID（属性中存放的是握手时校验过的合法字符串） */
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

        /** 注册的连接（并发安全包装后的会话） */
        private WebSocketSession session() { return session; }
        /** 归属用户 ID */
        private String userId() { return userId; }
        /** 归属设备 ID */
        @SuppressWarnings("unused") private String deviceId() { return deviceId; }
        /** 最后活跃时间（心跳与 pong 时刷新） */
        private Instant lastSeenAt() { return lastSeenAt; }
        /** 刷新最后活跃时间，供心跳判定连接是否已死 */
        private void touch() { lastSeenAt = Instant.now(); }
    }

    private record DeviceSessionKey(String userId, String deviceId) {}
}
