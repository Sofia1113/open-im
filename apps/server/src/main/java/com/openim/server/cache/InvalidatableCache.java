package com.openim.server.cache;

/**
 * 可被跨实例失效的本地缓存
 *
 * 实现者向 {@link CacheInvalidationBus} 注册，收到其他实例的失效通知后
 * 清理自己的本地副本。
 *
 * <b>约定</b>：{@code invalidateLocal} 只清本地，不得再次广播——否则一次失效
 * 会在实例之间无限反弹。广播由调用方通过 {@link CacheInvalidationBus#invalidate}
 * 显式发起。
 */
public interface InvalidatableCache {

    /** 缓存名，用于失效消息路由。必须全局唯一且稳定（会出现在网络消息里） */
    String cacheName();

    /** 清除单个键的本地副本 */
    void invalidateLocal(String key);

    /** 清除全部本地副本。用于无法精确定位受影响键的批量变更 */
    void invalidateAllLocal();
}
