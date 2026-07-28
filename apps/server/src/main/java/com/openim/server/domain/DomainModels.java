package com.openim.server.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 领域模型定义
 * 包含用户、会话、消息、钱包、红包和通话等核心数据结构的不可变记录。
 */
public final class DomainModels {
    private DomainModels() {
    }

    /** 用户 */
    public record User(
            String id,
            String username,
            String displayName,
            String avatar,
            String status,
            Instant lastSeenAt
    ) {
    }

    /** 会话（单聊/群聊） */
    public record Conversation(
            String id,
            String type,
            String title,
            String avatar,
            List<String> memberIds,
            String lastMessage,
            Instant lastActiveAt,
            int unreadCount
    ) {
    }

    /** 即时消息 */
    public record ImMessage(
            String id,
            String conversationId,
            long seq,
            String senderId,
            String clientMessageId,
            String type,
            Map<String, Object> content,
            Instant sentAt
    ) {
    }

    /** 钱包流水条目 */
    public record WalletEntry(
            String id,
            String type,
            long amount,
            long balanceAfter,
            String businessId,
            Instant createdAt
    ) {
    }

    /** 用户钱包 */
    public record Wallet(
            String userId,
            long balance,
            List<WalletEntry> entries
    ) {
    }

    /** 红包领取记录 */
    public record RedPacketClaim(
            String id,
            String userId,
            long amount,
            Instant claimedAt
    ) {
    }

    /** 红包 */
    public record RedPacket(
            String id,
            String conversationId,
            String senderId,
            String mode,
            long totalAmount,
            int totalCount,
            long claimedAmount,
            int claimedCount,
            String greeting,
            String status,
            Instant expiresAt,
            List<RedPacketClaim> claims
    ) {
    }

    /** 通话参与者 */
    public record CallParticipant(
            String userId,
            String state,
            String joinedDeviceId
    ) {
    }

    /** 通话房间 */
    public record CallRoom(
            String id,
            String conversationId,
            String creatorId,
            String mediaType,
            String status,
            List<CallParticipant> participants,
            Instant createdAt,
            Instant expiresAt,
            Instant endedAt,
            String endReason
    ) {
    }
}
