package com.openim.server.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.config.InstanceId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Pub/Sub 事件路由单元测试
 *
 * 直接调用 {@link RealtimeEventSubscriber#onMessage(Message, byte[])}，
 * 验证路由逻辑而不依赖真实 Redis 连接。
 * 跨实例 end-to-end 集成（真实 Redis Pub/Sub）由 {@code SecurityHardeningIntegrationTest}
 * 中的限流场景覆盖——那里使用了真实的 Redis 容器。
 */
class RealtimeEventSubscriberTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RealtimeWebSocketHandler localHandler;
    private RealtimeEventSubscriber subscriber;
    private InstanceId myInstanceId;

    @BeforeEach
    void setup() {
        localHandler = mock(RealtimeWebSocketHandler.class);
        myInstanceId = new InstanceId("instance-A");
        subscriber = new RealtimeEventSubscriber(
                localHandler,
                mock(RedisMessageListenerContainer.class),
                MAPPER,
                myInstanceId
        );
    }

    @Test
    void routesUserEventToLocalHandler() throws Exception {
        Message msg = message(Map.of(
                "originInstanceId", "instance-B",  // 来自其他实例
                "targetType", "USER",
                "userId", "alice",
                "event", "wallet:updated",
                "payload", Map.of("balance", 100)
        ));

        subscriber.onMessage(msg, null);

        verify(localHandler).emitToUser("alice", "wallet:updated", Map.of("balance", 100));
    }

    @Test
    void routesDeviceEventToLocalHandler() throws Exception {
        Message msg = message(Map.of(
                "originInstanceId", "instance-B",
                "targetType", "DEVICE",
                "userId", "alice",
                "deviceId", "device-123",
                "event", "rtc:offer",
                "payload", Map.of("sdp", "...")
        ));

        subscriber.onMessage(msg, null);

        verify(localHandler).emitToDevice("alice", "device-123", "rtc:offer", Map.of("sdp", "..."));
    }

    @Test
    void routesConversationEventToLocalHandler() throws Exception {
        Message msg = message(Map.of(
                "originInstanceId", "instance-B",
                "targetType", "CONVERSATION",
                "conversationId", "conv-abc",
                "event", "message:created",
                "payload", Map.of("text", "hello")
        ));

        subscriber.onMessage(msg, null);

        verify(localHandler).emitToConversation("conv-abc", "message:created", Map.of("text", "hello"));
    }

    @Test
    void skipsMessagesFromSameInstance() throws Exception {
        // 本实例自己发出的消息已经在 broadcastToXxx() 里本地投递过了，不应再次处理
        Message msg = message(Map.of(
                "originInstanceId", "instance-A",  // 本实例
                "targetType", "USER",
                "userId", "alice",
                "event", "message:created",
                "payload", Map.of()
        ));

        subscriber.onMessage(msg, null);

        verifyNoInteractions(localHandler);
    }

    @Test
    void handlesUnknownTargetTypeGracefully() throws Exception {
        // 未知类型不应抛出异常，只记录警告
        Message msg = message(Map.of(
                "originInstanceId", "instance-B",
                "targetType", "UNKNOWN_TYPE",
                "event", "some:event",
                "payload", Map.of()
        ));

        subscriber.onMessage(msg, null);

        verifyNoInteractions(localHandler);
    }

    @Test
    void handlesInvalidJsonGracefully() {
        // 解析失败不应让应用崩溃
        Message msg = brokenMessage("not-valid-json{{{");

        subscriber.onMessage(msg, null);

        verifyNoInteractions(localHandler);
    }

    @Test
    void handlesNullUserIdGracefully() throws Exception {
        // userId 缺失不应调用 localHandler
        Message msg = message(Map.of(
                "originInstanceId", "instance-B",
                "targetType", "USER",
                // userId 缺失
                "event", "test",
                "payload", Map.of()
        ));

        subscriber.onMessage(msg, null);

        verify(localHandler, never()).emitToUser(null, "test", Map.of());
    }

    private Message message(Object payload) throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(payload);
        return new Message() {
            @Override public byte[] getBody() { return body; }
            @Override public byte[] getChannel() { return RealtimeBroadcastService.CHANNEL.getBytes(StandardCharsets.UTF_8); }
        };
    }

    private Message brokenMessage(String raw) {
        byte[] body = raw.getBytes(StandardCharsets.UTF_8);
        return new Message() {
            @Override public byte[] getBody() { return body; }
            @Override public byte[] getChannel() { return RealtimeBroadcastService.CHANNEL.getBytes(StandardCharsets.UTF_8); }
        };
    }
}
