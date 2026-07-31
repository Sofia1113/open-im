package com.openim.server.domain.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.openim.server.cache.CacheInvalidationBus;
import com.openim.server.cache.InvalidatableCache;
import com.openim.server.domain.repository.ConversationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 会话成员关系缓存
 *
 * <b>解决的问题</b>：{@code RealtimeWebSocketHandler.emitToConversation} 每投递一条
 * 消息就查一次成员列表。多实例下这是
 * <i>每条群消息 × 每个实例 = 一次成员查询</i>——一个 200 人的群里发一条消息，
 * 4 个实例就是 4 次查询，而这条消息本身只写了一行。消息越活跃，
 * 放大越严重，且全部落在同一张表上。
 *
 * <b>为什么不能退回连接时快照</b>：那是这段代码原先的做法，已经被证明是错的——
 * 快照只在连接建立那一刻正确，此后加群的用户收不到消息、退群的用户仍在接收。
 * 成员关系的真实来源必须是数据库。
 *
 * 缓存是这两者之间的正确答案：真实来源仍是数据库，但用<b>短 TTL + 变更时主动失效</b>
 * 把重复查询折叠掉。与快照的关键区别在于它会自愈——即使某次失效广播丢了，
 * 陈旧窗口也止于一个 TTL，而快照的错误会持续到连接断开为止。
 *
 * TTL 取 60 秒：成员变更是低频操作（建群、加人、退群），而消息投递是高频操作。
 * 失效广播覆盖了正常路径，TTL 只是 Redis 故障时的兜底上限。
 */
@Component
public class ConversationMemberCache implements InvalidatableCache {

    public static final String CACHE_NAME = "conversation-members";

    private final ConversationRepository conversationRepository;
    private final Cache<String, List<UUID>> cache;
    private final ObjectProvider<CacheInvalidationBus> busProvider;

    public ConversationMemberCache(
            ConversationRepository conversationRepository,
            ObjectProvider<CacheInvalidationBus> busProvider,
            MeterRegistry meterRegistry,
            @Value("${openim.realtime.member-cache-ttl-seconds:60}") long ttlSeconds,
            @Value("${openim.realtime.member-cache-max-size:20000}") long maxSize
    ) {
        this.conversationRepository = conversationRepository;
        this.busProvider = busProvider;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, cache, CACHE_NAME);
    }

    /**
     * 会话的有效成员 ID 列表。
     *
     * 返回的列表来自缓存，调用方<b>不得修改</b>——它被多个线程共享。
     * {@code listActiveMemberIds} 返回的是不可变列表，这里原样透传。
     */
    public List<UUID> listActiveMemberIds(UUID conversationId) {
        return cache.get(conversationId.toString(),
                key -> List.copyOf(conversationRepository.listActiveMemberIds(conversationId)));
    }

    /**
     * 会话成员变更后失效。建群、加人、退群、移出都必须调用。
     *
     * 在事务提交后执行，避免其他实例回源读到未提交的旧成员表。
     */
    public void invalidate(UUID conversationId) {
        busProvider.getObject().invalidateAfterCommit(CACHE_NAME, conversationId.toString());
    }

    // ── InvalidatableCache ──────────────────────────────────────────────

    /** 缓存名，供失效总线按名定向失效本实例 */
    @Override
    public String cacheName() {
        return CACHE_NAME;
    }

    /** 失效本实例的单个条目——失效广播在本地的落点 */
    @Override
    public void invalidateLocal(String key) {
        cache.invalidate(key);
    }

    /** 清空本实例全部缓存条目，用于缓存整体失效场景 */
    @Override
    public void invalidateAllLocal() {
        cache.invalidateAll();
    }
}
