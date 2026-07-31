package com.openim.server.realtime;

import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.DomainModels.RedPacket;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个可去重的实时事件
 *
 * <b>为什么需要这个类型</b>：同一个逻辑事件有两条投递路径——请求处理期间的同步
 * 推送（低延迟）和 {@link com.openim.server.worker.OutboxPublisherWorker} 的
 * 补偿推送（保证不丢）。此前两条路径各自拼装载荷，结果是：
 * <ul>
 *   <li>主路径发 {@code {eventId, message}}，Worker 发 {@code {eventId, outboxId, payload}}，
 *       客户端按契约取 {@code payload.message} 时在 Worker 版本上取不到</li>
 *   <li>Worker 用 outbox 内部事件名（{@code red_packet:claimed}）作为线上事件名，
 *       而客户端监听的是 {@code red-packet:updated}，两者永不匹配</li>
 *   <li>两条路径的 eventId 各自随机生成，同一事件的两次投递无法被识别为重复</li>
 * </ul>
 *
 * 把"线上事件名 + 载荷结构 + 去重标识"收敛到这里的静态工厂后，两条路径
 * 只能通过同一个构造入口产生事件，结构上无法再漂移。
 *
 * @param dedupKey 事件的<b>稳定业务标识</b>。同一逻辑事件无论被投递几次都得到
 *                 相同的 key，因此可用于去重；不同事件必须不同。刻意不用随机 UUID：
 *                 那样两条路径产生的 key 不同，去重就失效了。
 * @param name     线上事件名，必须与 packages/contracts 的 ServerToClientEvents 一致
 * @param payload  线上载荷，结构同样以契约为准
 */
public record RealtimeEvent(String dedupKey, String name, Object payload) {

    /**
     * 新消息。
     *
     * 去重标识用消息 ID：它由数据库唯一约束保证唯一，且同一条消息重复投递时不变。
     * 客户端也按 {@code message.id} 去重，两端使用同一个标识便于排查。
     */
    public static RealtimeEvent messageCreated(ImMessage message) {
        return new RealtimeEvent(
                "message:" + message.id(),
                "message:created",
                envelope("message", message.id(), message)
        );
    }

    /**
     * 红包状态变化（发放后被领取、过期退款）。
     *
     * 去重标识包含已领份数与状态：每一次领取都产生不同的 key，
     * 而同一次领取的重复投递得到相同的 key。只用红包 ID 会把
     * 连续多次领取误判为重复而丢弃后续更新。
     */
    public static RealtimeEvent redPacketUpdated(RedPacket redPacket) {
        String key = "red-packet:" + redPacket.id()
                + ":" + redPacket.claimedCount()
                + ":" + redPacket.status();
        return new RealtimeEvent(key, "red-packet:updated",
                envelope("redPacket", key, redPacket));
    }

    /**
     * 契约要求载荷带 {@code eventId}，客户端用它做幂等。
     * 直接复用 dedupKey，使服务端日志与客户端行为可以对齐。
     */
    private static Map<String, Object> envelope(String field, String eventId, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put(field, value);
        return payload;
    }
}
