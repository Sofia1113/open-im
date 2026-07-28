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
}
