package com.openim.server.realtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 实时事件去重
 *
 * <b>去重必须是每实例本地的，不能跨实例共享</b>——这是本类此前最严重的设计错误。
 *
 * 旧实现用 Redis {@code SET NX} 做<i>全局</i>去重，且在<b>发布前</b>判定。
 * 后果是：主路径推送时抢到了那个全局键，于是 Outbox 补偿路径永远被判定为
 * "重复"而丢弃。所谓的补偿从来没有补偿过任何东西——它唯一能覆盖的场景，
 * 只剩"事务提交后、主路径推送前进程崩溃"这个极窄的时间窗。
 *
 * 根因是把两件事混为一谈：
 * <ul>
 *   <li><b>"这个事件已经发出去了"</b>——全局状态，但它<i>不能</i>用来决定是否投递，
 *       因为"发出去了"不代表"每个实例都收到了"</li>
 *   <li><b>"我这个实例已经把它投给本地连接了"</b>——本地状态，这才是去重该问的问题</li>
 * </ul>
 *
 * 每个实例都必须独立投递每一个事件（它们各自连着不同的用户），因此每个实例也
 * 必须独立判断"我投过了没有"。改用进程内 Caffeine 后：
 * <ul>
 *   <li>语义正确：实例 A 投过了不会阻止实例 B 投</li>
 *   <li>不依赖 Redis：Redis 故障时去重照常工作，不再需要"故障放行"的妥协</li>
 *   <li>更快：一次哈希查找，没有网络往返</li>
 * </ul>
 *
 * <b>去重防的是什么</b>：
 * <ol>
 *   <li>Outbox 补偿重发主路径已投递过的事件（两条不同的流记录，相同业务标识）</li>
 *   <li>{@code XAUTOCLAIM} 重投处理成功但 {@code XACK} 失败的记录（同一条流记录）</li>
 * </ol>
 *
 * TTL 需覆盖"主路径推送"到"Outbox 补偿扫描"的最大间隔。Worker 每 5 秒扫一轮，
 * 失败后指数退避上限 5 分钟，10 分钟足以覆盖一个完整重试周期。
 * 超出该窗口后同一事件不可能再被投递，继续保留只是白占内存。
 */
@Component
public class EventDeduplicator {

    private static final String CACHE_NAME = "realtime-event-dedup";

    private final Cache<String, Boolean> delivered;
    private final Counter duplicatesDropped;

    public EventDeduplicator(
            MeterRegistry meterRegistry,
            @Value("${openim.realtime.event-dedup-ttl-seconds:600}") long ttlSeconds,
            @Value("${openim.realtime.event-dedup-max-size:200000}") long maxSize
    ) {
        this.delivered = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, delivered, CACHE_NAME);
        this.duplicatesDropped = Counter.builder("openim.realtime.duplicate_events_dropped")
                .description("被去重丢弃的重复事件数。持续为零说明补偿路径没在工作")
                .register(meterRegistry);
    }

    /**
     * 标记事件已投递，返回是否应当继续投递。
     *
     * @param dedupKey 事件的稳定业务标识，见 {@link RealtimeEvent#dedupKey()}。
     *                 为空时放行——没有标识就无法去重，而丢弃会造成消息丢失。
     * @return true 表示本实例首次投递该事件，应当推送
     */
    public boolean shouldDeliver(String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return true;
        }
        // asMap().putIfAbsent 是原子的：并发的两条路径只有一个拿到 null
        boolean first = delivered.asMap().putIfAbsent(dedupKey, Boolean.TRUE) == null;
        if (!first) {
            duplicatesDropped.increment();
        }
        return first;
    }
}
