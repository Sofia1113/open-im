package com.openim.server.domain;

import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.DomainModels.RedPacket;
import com.openim.server.domain.DomainModels.Wallet;

/**
 * 领域操作结果封装
 * 定义红包创建和领取的返回类型，包含消息、红包和钱包状态的变更。
 */
public final class DomainResults {
    private DomainResults() {
    }

    public record CreatedRedPacket(
            RedPacket redPacket,
            ImMessage message,
            Wallet wallet
    ) {
    }

    public record ClaimedRedPacket(
            RedPacket redPacket,
            long amount,
            Wallet wallet,
            boolean alreadyClaimed
    ) {
    }

    /**
     * 一页消息历史。
     *
     * <b>为什么要带 {@code hasMore} 而不是让客户端按"返回条数 < 页大小"推断</b>：
     * 那个推断在最后一页恰好装满时是错的——客户端会认为还有更多，再请求一次拿到
     * 空数组，然后才停下。对"向上翻阅"这个交互来说，多一次请求意味着用户看到
     * 加载指示器闪一下却没有新内容出现。
     *
     * 服务端知道确切答案（多取一条判断），把它告诉客户端比让每个客户端各自猜更省事。
     *
     * @param messages 本页消息，按 {@code seq} <b>升序</b>，可直接渲染
     * @param hasMore  在本页之外、同一方向上是否还有更多消息
     */
    public record MessagePage(
            java.util.List<ImMessage> messages,
            boolean hasMore
    ) {
        /** 构造一页消息。入参列表做防御性拷贝，避免调用方后续修改污染已返回的页面 */
        public static MessagePage of(java.util.List<ImMessage> messages, boolean hasMore) {
            return new MessagePage(java.util.List.copyOf(messages), hasMore);
        }
    }

    /**
     * 会话成员变更结果。
     *
     * <b>为什么要把 removed 单独带出来</b>：成员变更事件靠
     * {@code broadcastToConversation} 扇出，而它的收件人是<i>变更之后</i>的成员列表——
     * 被移出的人已经不在里面，收不到任何通知，客户端会一直显示一个他已经无权访问的
     * 会话，直到下次手动刷新才发现请求全部 403。这些人必须由调用方按用户单独推送。
     *
     * 新加入的成员不需要这样处理：他们在新的成员列表里，扇出天然覆盖。
     *
     * @param conversationId 变更的会话
     * @param joined         本次真正加入的用户（已在群内的不计入）
     * @param removed        本次真正移出的用户（本来就不在的不计入）
     */
    public record MembershipChange(
            String conversationId,
            java.util.List<String> joined,
            java.util.List<String> removed
    ) {
        /** 构造"仅有成员加入"的变更结果，removed 为空列表 */
        public static MembershipChange joined(String conversationId, java.util.List<String> joined) {
            return new MembershipChange(conversationId, java.util.List.copyOf(joined), java.util.List.of());
        }

        /** 构造"仅有单名成员被移出"的变更结果，joined 为空列表 */
        public static MembershipChange removed(String conversationId, String userId) {
            return new MembershipChange(conversationId, java.util.List.of(), java.util.List.of(userId));
        }
    }
}
