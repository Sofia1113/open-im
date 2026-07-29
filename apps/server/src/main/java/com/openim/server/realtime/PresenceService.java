package com.openim.server.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户在线状态（Presence）
 *
 * 存储在 Redis 而非数据库的原因：
 * <ul>
 *   <li>TTL 自动到期：实例崩溃后不需要外部清理任务来修正 online 状态</li>
 *   <li>在线状态的变化频率很高（每个心跳周期都要刷新），数据库写入代价太大</li>
 *   <li>Redis 的原子性操作保证多实例并发更新时不会出现竞争</li>
 * </ul>
 *
 * Key 格式：{@code presence:{userId}}，值为固定字符串 {@code "1"}，TTL 为 {@code presence-ttl-seconds}。
 * 只要连接存活，WebSocket 处理器的心跳循环就会不断刷新 TTL；
 * 连接断开或实例崩溃后 key 自动过期，无需手动清理。
 */
@Service
public class PresenceService {
    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);
    private static final String KEY_PREFIX = "presence:";
    private static final String ONLINE_VALUE = "1";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public PresenceService(
            StringRedisTemplate redisTemplate,
            @Value("${openim.realtime.presence-ttl-seconds:180}") long ttlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * 标记用户上线并重置 TTL。
     * 连接建立和心跳 pong 都会调用，保持 key 存活。
     */
    public void markOnline(UUID userId) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + userId, ONLINE_VALUE, ttl);
        } catch (RuntimeException exception) {
            log.warn("Failed to set presence online for userId={}", userId);
        }
    }

    /**
     * 标记用户离线：立即删除 key（不等 TTL 自然过期）。
     *
     * 只有在该用户的所有 WebSocket 连接都断开后才调用，
     * 否则会让仍然在线的其他设备显示为离线。
     */
    public void markOffline(UUID userId) {
        try {
            redisTemplate.delete(KEY_PREFIX + userId);
        } catch (RuntimeException exception) {
            log.warn("Failed to clear presence for userId={}", userId);
        }
    }

    /** 判断单个用户是否在线 */
    public boolean isOnline(UUID userId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + userId));
        } catch (RuntimeException exception) {
            log.warn("Failed to check presence for userId={}", userId);
            return false;
        }
    }

    /**
     * 批量获取在线用户 ID 集合。
     *
     * 用 Redis pipeline 批量查询，避免 N 次网络往返：
     * 查询 1000 个用户的在线状态一次 pipeline 比 N 次单独查询快得多。
     */
    public Map<UUID, Boolean> batchIsOnline(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> idList = List.copyOf(userIds);
        try {
            // executePipelined 的返回类型是 List<Object>，需要手动转换
            List<Object> results = redisTemplate.executePipelined(
                    (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                        for (UUID id : idList) {
                            // StringRedisSerializer 就是 UTF-8，直接编码避免通配符类型推断问题
                            byte[] keyBytes = (KEY_PREFIX + id)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            connection.keyCommands().exists(keyBytes);
                        }
                        return null;
                    }
            );
            java.util.Map<UUID, Boolean> map = new java.util.LinkedHashMap<>(idList.size());
            for (int i = 0; i < idList.size(); i++) {
                Object r = (results != null && i < results.size()) ? results.get(i) : null;
                map.put(idList.get(i), Boolean.TRUE.equals(r));
            }
            return map;
        } catch (RuntimeException exception) {
            log.warn("Failed to batch-check presence, defaulting to offline");
            java.util.Map<UUID, Boolean> map = new java.util.LinkedHashMap<>();
            idList.forEach(id -> map.put(id, false));
            return map;
        }
    }
}
