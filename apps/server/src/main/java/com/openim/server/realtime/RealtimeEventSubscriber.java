package com.openim.server.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.config.InstanceId;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Redis 实时事件订阅端
 *
 * 接收其他实例通过 {@link RealtimeBroadcastService} 发布的事件，
 * 判断目标类型后转交给本实例的 {@link RealtimeWebSocketHandler} 分发。
 *
 * 跳过自身发出的消息（通过 {@code originInstanceId} 对比），
 * 避免因"先本地投递、再经 Redis 二次分发"而重复推送。
 */
@Component
public class RealtimeEventSubscriber implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(RealtimeEventSubscriber.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RealtimeWebSocketHandler localHandler;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final InstanceId instanceId;

    public RealtimeEventSubscriber(
            RealtimeWebSocketHandler localHandler,
            RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            InstanceId instanceId
    ) {
        this.localHandler = localHandler;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
    }

    @PostConstruct
    public void subscribe() {
        listenerContainer.addMessageListener(this,
                ChannelTopic.of(RealtimeBroadcastService.CHANNEL));
        log.info("Subscribed to Redis channel={} instanceId={}",
                RealtimeBroadcastService.CHANNEL, instanceId.value());
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Map<String, Object> msg = objectMapper.readValue(message.getBody(), MAP_TYPE);

            // 跳过本实例自己发出的消息——本地投递已经在 broadcastToXxx() 里同步完成
            String origin = (String) msg.get("originInstanceId");
            if (instanceId.value().equals(origin)) {
                return;
            }

            String targetType = (String) msg.get("targetType");
            String event = (String) msg.get("event");
            Object payload = msg.get("payload");

            switch (targetType) {
                case "USER" -> {
                    String userId = (String) msg.get("userId");
                    if (userId != null) {
                        localHandler.emitToUser(userId, event, payload);
                    }
                }
                case "DEVICE" -> {
                    String userId = (String) msg.get("userId");
                    String deviceId = (String) msg.get("deviceId");
                    if (userId != null && deviceId != null) {
                        localHandler.emitToDevice(userId, deviceId, event, payload);
                    }
                }
                case "CONVERSATION" -> {
                    String conversationId = (String) msg.get("conversationId");
                    if (conversationId != null) {
                        localHandler.emitToConversation(conversationId, event, payload);
                    }
                }
                default -> log.warn("Unknown realtime event targetType={}", targetType);
            }
        } catch (RuntimeException | java.io.IOException exception) {
            log.warn("Failed to process realtime event from Redis", exception);
        }
    }
}
