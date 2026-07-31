package com.openim.server.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.config.InstanceId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 跨实例缓存失效广播
 *
 * <b>为什么这里用 Pub/Sub 是对的，而消息投递用它是错的</b>：
 * 两者的失败代价完全不同。
 * <ul>
 *   <li>失效通知丢失 → 某个实例多用了至多一个 TTL 的陈旧数据，之后自动收敛。
 *       损失有界、可自愈。</li>
 *   <li>消息事件丢失 → 用户永久看不到那条消息。损失无界、不可自愈，
 *       因此实时事件改用 Redis Streams 的消费组 + XACK
 *       （见 {@link com.openim.server.realtime.RealtimeEventStream}）。</li>
 * </ul>
 * 同一个中间件，语义要求不同就该选不同的原语。
 *
 * <b>因此本类刻意不做可靠性保证</b>：不重试、不持久化、不确认。
 * 所有注册进来的缓存都必须自带 TTL 作为兜底——失效广播是把陈旧窗口
 * 从"一个 TTL"缩短到"一次网络往返"的优化，不是正确性的来源。
 */
@Component
public class CacheInvalidationBus implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationBus.class);
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    static final String CHANNEL = "openim:cache-invalidate";

    /** 失效整个缓存时使用的键占位符。真实键不会是空串，因此不会冲突 */
    private static final String ALL_KEYS = "*";

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final InstanceId instanceId;
    private final Map<String, InvalidatableCache> caches;
    private final Counter published;
    private final Counter received;
    private final Counter publishFailures;

    public CacheInvalidationBus(
            RedisTemplate<String, Object> redisTemplate,
            RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            InstanceId instanceId,
            List<InvalidatableCache> caches,
            MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.caches = caches.stream().collect(Collectors.toMap(
                InvalidatableCache::cacheName, Function.identity()));
        this.published = Counter.builder("openim.cache.invalidations_published")
                .description("本实例发出的缓存失效广播数")
                .register(meterRegistry);
        this.received = Counter.builder("openim.cache.invalidations_received")
                .description("从其他实例收到并应用的缓存失效通知数")
                .register(meterRegistry);
        this.publishFailures = Counter.builder("openim.cache.invalidation_publish_failures")
                .description("失效广播发布失败次数；失败时依赖各缓存自身的 TTL 兜底")
                .register(meterRegistry);
    }

    /** 订阅缓存失效广播频道,开始接收其他实例的失效通知 */
    @PostConstruct
    public void subscribe() {
        listenerContainer.addMessageListener(this, ChannelTopic.of(CHANNEL));
        log.info("Cache invalidation bus subscribed channel={} caches={}",
                CHANNEL, caches.keySet());
    }

    /**
     * 失效某个键，并通知其他实例。
     *
     * 本地立即失效不依赖 Redis：即使广播失败，发起变更的这个实例也必须
     * 马上看到新数据——否则"我改了但我自己看到的还是旧的"，这是最难排查的一类问题。
     */
    public void invalidate(String cacheName, String key) {
        InvalidatableCache cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidateLocal(key);
        }
        publish(cacheName, key);
    }

    /** 失效整个缓存，并通知其他实例 */
    public void invalidateAll(String cacheName) {
        InvalidatableCache cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidateAllLocal();
        }
        publish(cacheName, ALL_KEYS);
    }

    /**
     * 在当前事务提交后失效。<b>写路径必须用这个而不是 {@link #invalidate}。</b>
     *
     * 在事务内失效会引入一个必然发生的竞态：本实例广播失效 → 其他实例清掉本地副本
     * → 立即有请求回源查库 → 此刻本实例的事务<b>尚未提交</b>，查到的仍是旧值
     * → 旧值被重新缓存。结果是失效通知非但没有清掉陈旧数据，反而把它的寿命
     * 又续了一个完整的 TTL，而且这个窗口无法通过重试观察到——它看起来就像
     * "失效偶尔不生效"。
     *
     * 没有活动事务时（例如从定时任务或 REQUIRES_NEW 之外的路径调用）立即失效。
     */
    public void invalidateAfterCommit(String cacheName, String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidate(cacheName, key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidate(cacheName, key);
            }
        });
    }

    /** 在当前事务提交后失效整个缓存。语义同 {@link #invalidateAfterCommit} */
    public void invalidateAllAfterCommit(String cacheName) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidateAll(cacheName);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateAll(cacheName);
            }
        });
    }

    /** 向 Redis 频道广播失效消息;失败时降级为"其他实例等 TTL 过期",不阻断调用方 */
    private void publish(String cacheName, String key) {
        try {
            Map<String, String> message = new LinkedHashMap<>();
            message.put("originInstanceId", instanceId.value());
            message.put("cache", cacheName);
            message.put("key", key);
            redisTemplate.convertAndSend(CHANNEL, message);
            published.increment();
        } catch (RuntimeException exception) {
            publishFailures.increment();
            // 降级为"只有本实例立即生效，其他实例等 TTL 过期"。
            // 不抛出：调用方正处在一次业务写入的末尾，让缓存广播失败回滚业务是本末倒置。
            log.warn("Cache invalidation broadcast failed cache={} reason={}",
                    cacheName, exception.getClass().getSimpleName());
        }
    }

    /** 收到其他实例的失效广播后,按缓存名路由并清理本地副本;自己发出的广播直接跳过 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Map<String, String> payload = objectMapper.readValue(message.getBody(), MAP_TYPE);
            if (instanceId.value().equals(payload.get("originInstanceId"))) {
                return; // 本实例发起的，本地已经失效过了
            }
            InvalidatableCache cache = caches.get(payload.get("cache"));
            if (cache == null) {
                // 滚动更新期间，新版本可能引入了旧版本还不认识的缓存名
                log.debug("Ignoring invalidation for unknown cache={}", payload.get("cache"));
                return;
            }
            String key = payload.get("key");
            if (ALL_KEYS.equals(key)) {
                cache.invalidateAllLocal();
            } else if (key != null) {
                cache.invalidateLocal(key);
            }
            received.increment();
        } catch (RuntimeException | java.io.IOException exception) {
            log.warn("Failed to apply cache invalidation", exception);
        }
    }
}
