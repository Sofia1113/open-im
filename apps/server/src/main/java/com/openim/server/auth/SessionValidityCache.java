package com.openim.server.auth;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import com.openim.server.cache.CacheInvalidationBus;
import com.openim.server.cache.InvalidatableCache;
import com.openim.server.domain.repository.DeviceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 设备会话有效性缓存
 *
 * <b>解决的问题</b>：{@link AuthenticationInterceptor} 此前对<b>每一个</b> HTTP 请求
 * 执行一次 {@code deviceRepository.isSessionActive()}。设计意图是对的——令牌有效
 * 不等于会话有效，账号被封或设备被撤销必须立刻拒绝——但代价是每请求一次数据库
 * 往返。Hikari 池只有 20 个连接，这是整个服务第一个会撞上的容量墙：
 * 请求量还没到消息写入的瓶颈，鉴权就先把连接池占满了。
 *
 * <b>权衡</b>：缓存把"撤销立即生效"放宽为"撤销在 {@code ttl} 内生效"。
 * 这个代价是可控的，因为：
 * <ul>
 *   <li>撤销的意义本就是"比访问令牌自然过期更快地失效"。访问令牌 TTL 是 900 秒，
 *       缓存 TTL 是 30 秒——撤销延迟仍然比不撤销快 30 倍。</li>
 *   <li>更重要的是有主动失效：撤销发生时通过 {@link CacheInvalidationBus} 广播，
 *       正常情况下其他实例在一次网络往返内就失效，"30 秒"只是 Redis
 *       同时故障时的最坏上限。</li>
 * </ul>
 *
 * <b>为什么正负结果都缓存</b>：只缓存有效结果的话，一个被撤销的令牌每次重试
 * 都会穿透到数据库——而被撤销的令牌恰恰最可能被反复重试（客户端还没感知到失效，
 * 或者攻击者在用一个已撤销的令牌）。负缓存把这条路径也挡在数据库之前。
 *
 * <b>负缓存为什么不能设得更久</b>：{@link DeviceRepository#upsert} 在重新登录时
 * 会把 {@code revoked_at} 清空——设备是可以"复活"的。因此登录成功后必须主动失效
 * （见 {@link SessionService}），且负缓存 TTL 不能长于正缓存。
 *
 * <b>维护约束</b>：缓存的是
 * {@link DeviceRepository#isSessionActive} 的结果，而那条查询同时校验
 * {@code devices.revoked_at} 与 {@code users.status = 'ACTIVE'}。
 * 因此<b>任何改变用户状态的新功能</b>——封禁、注销、删除——都必须在提交后调用
 * {@link #invalidateAll()}，否则被封禁的账号会继续持有访问权直到缓存过期。
 * 当前已接入的失效点：登录、登出、全设备撤销、令牌重放撤销、账号注销。
 */
@Component
public class SessionValidityCache implements InvalidatableCache {

    public static final String CACHE_NAME = "session-validity";

    private final DeviceRepository deviceRepository;
    private final Cache<String, Boolean> cache;

    /**
     * 用 ObjectProvider 打破循环依赖：
     * CacheInvalidationBus 构造时需要收集全部 InvalidatableCache（含本类），
     * 本类又需要 bus 来广播失效。延迟到实际调用时再取。
     */
    private final ObjectProvider<CacheInvalidationBus> busProvider;

    public SessionValidityCache(
            DeviceRepository deviceRepository,
            ObjectProvider<CacheInvalidationBus> busProvider,
            MeterRegistry meterRegistry,
            @Value("${openim.auth.session-cache-ttl-seconds:30}") long ttlSeconds,
            @Value("${openim.auth.session-cache-max-size:50000}") long maxSize
    ) {
        this.deviceRepository = deviceRepository;
        this.busProvider = busProvider;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .recordStats()
                .build();
        // 命中率进指标：命中率持续偏低说明 TTL 太短或 key 基数过大，缓存没起作用
        CaffeineCacheMetrics.monitor(meterRegistry, cache, CACHE_NAME);
    }

    /**
     * 会话是否仍然有效。缓存未命中时回源数据库。
     */
    public boolean isSessionActive(String deviceId, UUID userId) {
        if (deviceId == null || userId == null) {
            return false;
        }
        Boolean cached = cache.get(key(deviceId, userId),
                ignored -> deviceRepository.isSessionActive(deviceId, userId));
        return Boolean.TRUE.equals(cached);
    }

    /**
     * 主动失效某个设备会话，并广播到其他实例。
     *
     * 撤销、登录、改密之后都必须调用——否则被撤销的令牌会在缓存 TTL 内继续可用。
     *
     * 失效在<b>事务提交后</b>执行。在事务内失效会让其他实例回源读到未提交的旧值
     * 并重新缓存，反而延长陈旧窗口，详见
     * {@link CacheInvalidationBus#invalidateAfterCommit}。
     */
    public void invalidate(String deviceId, UUID userId) {
        if (deviceId == null || userId == null) {
            return;
        }
        busProvider.getObject().invalidateAfterCommit(CACHE_NAME, key(deviceId, userId));
    }

    /**
     * 失效全部设备会话缓存。
     *
     * 用户级撤销（改密、全设备下线）影响的 deviceId 集合在调用点未必已知，
     * 逐个失效需要先查库列出设备，反而多一次往返。整体清空的代价只是
     * 其他用户的缓存条目也被清掉，它们会在下次请求时重新载入——
     * 一次多余的回源远好过一个漏掉的撤销。
     */
    public void invalidateAll() {
        busProvider.getObject().invalidateAllAfterCommit(CACHE_NAME);
    }

    /** 缓存键：设备与用户二元组，分隔符不会出现在任一组成部分中 */
    private static String key(String deviceId, UUID userId) {
        return deviceId + "|" + userId;
    }

    // ── InvalidatableCache ──────────────────────────────────────────────

    /** 缓存名，供失效广播按名路由到本缓存 */
    @Override
    public String cacheName() {
        return CACHE_NAME;
    }

    /** 只清本地实例的单个条目（由失效广播回调，勿直接调用） */
    @Override
    public void invalidateLocal(String key) {
        cache.invalidate(key);
    }

    /** 清空本地实例的全部条目（由失效广播回调，勿直接调用） */
    @Override
    public void invalidateAllLocal() {
        cache.invalidateAll();
    }
}
