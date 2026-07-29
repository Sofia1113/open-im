package com.openim.server.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.config.InstanceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实时事件广播服务
 *
 * 统一处理两条投递路径：
 * <ol>
 *   <li>本地 WebSocket 会话（同实例用户）</li>
 *   <li>Redis Pub/Sub（其他实例上的用户，由 {@link RealtimeEventSubscriber} 接收后本地分发）</li>
 * </ol>
 *
 * 调用方只需调用 {@code broadcastToXxx()}，无需关心用户当前连接在哪个实例上。
 * 若 Redis 暂时不可用，降级为仅本地投递并记录警告，不抛出异常。
 *
 * <b>循环依赖说明</b>：此 Service 持有 {@link RealtimeWebSocketHandler}（本地投递），
 * Handler 反过来通过 {@code @Lazy} 字段注入此 Service（信令转发用）。
 * Spring 用延迟代理解决循环依赖，两者都可正常工作。
 */
@Service
public class RealtimeBroadcastService {
    private static final Logger log = LoggerFactory.getLogger(RealtimeBroadcastService.class);

    static final String CHANNEL = "openim:events";

    private final RealtimeWebSocketHandler localHandler;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final InstanceId instanceId;

    public RealtimeBroadcastService(
            RealtimeWebSocketHandler localHandler,
            RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper,
            InstanceId instanceId
    ) {
        this.localHandler = localHandler;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
    }

    /** 向某用户的所有在线设备广播（含其他实例） */
    public void broadcastToUser(String userId, String event, Object payload) {
        localHandler.emitToUser(userId, event, payload);
        publish("USER", Map.of("userId", userId), event, payload);
    }

    /** 向某用户的指定设备广播（含其他实例） */
    public void broadcastToDevice(String userId, String deviceId, String event, Object payload) {
        localHandler.emitToDevice(userId, deviceId, event, payload);
        publish("DEVICE", Map.of("userId", userId, "deviceId", deviceId), event, payload);
    }

    /** 向某会话内所有在线成员广播（含其他实例） */
    public void broadcastToConversation(String conversationId, String event, Object payload) {
        localHandler.emitToConversation(conversationId, event, payload);
        publish("CONVERSATION", Map.of("conversationId", conversationId), event, payload);
    }

    private void publish(String targetType, Map<String, String> target, String event, Object payload) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("originInstanceId", instanceId.value());
            message.put("targetType", targetType);
            message.putAll(target);
            message.put("event", event);
            message.put("payload", payload);
            redisTemplate.convertAndSend(CHANNEL, message);
        } catch (RuntimeException exception) {
            // Redis 不可用时降级为仅本地投递，不影响当前实例上的用户
            log.warn("Failed to publish realtime event to Redis channel={} event={}", CHANNEL, event);
        }
    }
}
