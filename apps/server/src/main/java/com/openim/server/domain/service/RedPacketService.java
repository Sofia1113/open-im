package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.DomainModels.RedPacket;
import com.openim.server.domain.DomainModels.RedPacketClaim;
import com.openim.server.domain.DomainModels.Wallet;
import com.openim.server.domain.DomainResults.ClaimedRedPacket;
import com.openim.server.domain.DomainResults.CreatedRedPacket;
import com.openim.server.domain.RedPacketAllocator;
import com.openim.server.domain.Requests.CreateRedPacket;
import com.openim.server.domain.repository.ConversationRepository;
import com.openim.server.domain.repository.ConversationRepository.ConversationMeta;
import com.openim.server.domain.repository.MessageRepository;
import com.openim.server.domain.repository.OutboxRepository;
import com.openim.server.domain.repository.RedPacketRepository;
import com.openim.server.domain.repository.RedPacketRepository.RedPacketRow;
import com.openim.server.domain.repository.RedPacketRepository.ShareRow;
import com.openim.server.domain.repository.WalletRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 红包与虚拟账务
 *
 * 资金流转全部走复式记账的托管账户：发红包时 用户 → 托管，领取时 托管 → 领取人，
 * 过期时 托管 → 发送者。因此"已发出未领取"的钱始终有明确归属，可以独立对账。
 */
@Service
public class RedPacketService {
    private static final long EXPIRY_HOURS = 24;
    private static final Set<String> VALID_MODES = Set.of("fixed", "equal", "random");

    private final RedPacketRepository redPacketRepository;
    private final WalletRepository walletRepository;
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final RedPacketAllocator allocator;
    private final OutboxRepository outboxRepository;

    public RedPacketService(
            RedPacketRepository redPacketRepository,
            WalletRepository walletRepository,
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            ConversationService conversationService,
            RedPacketAllocator allocator,
            OutboxRepository outboxRepository
    ) {
        this.redPacketRepository = redPacketRepository;
        this.walletRepository = walletRepository;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.allocator = allocator;
        this.outboxRepository = outboxRepository;
    }

    @Transactional(readOnly = true)
    public Wallet getWallet(UUID userId) {
        return walletRepository.findWalletByUserId(userId)
                .orElseThrow(() -> DomainErrors.notFound("钱包不存在"));
    }

    /**
     * 创建红包。
     *
     * 扣款、消息和红包在同一事务内完成：任一步失败都不会留下"钱扣了但红包没发出"的中间态。
     */
    @Transactional
    public CreatedRedPacket createRedPacket(UUID senderId, CreateRedPacket input) {
        UUID conversationId = DomainErrors.parseId(input.conversationId(), "会话");
        ConversationMeta meta = conversationService.requireMeta(conversationId, senderId);
        validateInput(input, meta);

        Optional<UUID> existingId = redPacketRepository
                .findIdBySenderIdempotency(senderId, input.idempotencyKey());
        if (existingId.isPresent()) {
            return buildCreatedResult(existingId.get(), senderId);
        }

        if (messageRepository.findByClientMessageId(senderId, input.clientMessageId()).isPresent()) {
            throw DomainErrors.conflict("DUPLICATE_MESSAGE_ID", "消息幂等键已被使用");
        }

        UUID packetId = UUID.randomUUID();
        Instant now = Instant.now();

        UUID senderAccount = walletRepository.findAccountIdByUserId(senderId)
                .orElseThrow(() -> DomainErrors.notFound("钱包不存在"));
        boolean debited = walletRepository.transfer(
                UUID.randomUUID(),
                "RED_PACKET_SEND",
                packetId,
                "red-packet-send:" + packetId,
                senderAccount,
                WalletRepository.RED_PACKET_ESCROW_ACCOUNT,
                input.totalAmount()
        );
        if (!debited) {
            throw DomainErrors.conflict("INSUFFICIENT_BALANCE", "虚拟余额不足");
        }

        ImMessage message = writeRedPacketMessage(conversationId, senderId, input, packetId, now);

        try {
            redPacketRepository.insert(
                    packetId,
                    conversationId,
                    senderId,
                    UUID.fromString(message.id()),
                    input.mode(),
                    input.totalAmount(),
                    input.totalCount(),
                    input.greeting().trim(),
                    now.plus(EXPIRY_HOURS, ChronoUnit.HOURS),
                    input.idempotencyKey()
            );
        } catch (DuplicateKeyException duplicate) {
            // 并发重复提交：另一个事务已用同一幂等键创建，回滚本次并返回既有结果
            throw DomainErrors.conflict("DUPLICATE_REQUEST", "红包正在处理中，请稍后重试");
        }

        List<Long> shares = "random".equals(input.mode())
                ? allocator.random(input.totalAmount(), input.totalCount())
                : allocator.equal(input.totalAmount(), input.totalCount());
        redPacketRepository.insertShares(packetId, shares);

        outboxRepository.append("red_packet", packetId, "red_packet:created",
                Map.of("redPacketId", packetId.toString(), "conversationId", conversationId.toString()));

        return new CreatedRedPacket(
                redPacketRepository.findById(packetId).orElseThrow(),
                message,
                getWallet(senderId)
        );
    }

    @Transactional
    public RedPacket getRedPacket(UUID packetId, UUID userId) {
        RedPacketRow row = redPacketRepository.findByIdForUpdate(packetId)
                .orElseThrow(() -> DomainErrors.notFound("红包不存在"));
        conversationService.requireMembership(row.conversationId(), userId);
        refundIfExpired(row);
        return redPacketRepository.findById(packetId).orElseThrow();
    }

    /**
     * 领取红包。
     *
     * 事务内先锁定红包行做状态判定，再用 SKIP LOCKED 抢占份额。
     * 重复领取由 (red_packet_id, user_id) 唯一约束兜底，即使前面的判断被并发绕过也不会多发钱。
     */
    @Transactional
    public ClaimedRedPacket claimRedPacket(UUID packetId, UUID userId, String idempotencyKey) {
        RedPacketRow row = redPacketRepository.findByIdForUpdate(packetId)
                .orElseThrow(() -> DomainErrors.notFound("红包不存在"));
        conversationService.requireMembership(row.conversationId(), userId);

        boolean refunded = refundIfExpired(row);

        Optional<RedPacketClaim> existing = redPacketRepository.findClaim(packetId, userId);
        if (existing.isPresent()) {
            return new ClaimedRedPacket(
                    redPacketRepository.findById(packetId).orElseThrow(),
                    existing.get().amount(),
                    getWallet(userId),
                    true
            );
        }

        if (refunded || "expired".equals(row.status()) || "refunded".equals(row.status())) {
            throw DomainErrors.conflict("RED_PACKET_EXPIRED", "红包已过期");
        }
        if ("exhausted".equals(row.status())) {
            throw DomainErrors.conflict("RED_PACKET_EXHAUSTED", "红包已领完");
        }

        ConversationMeta meta = conversationRepository.findMeta(row.conversationId())
                .orElseThrow(() -> DomainErrors.notFound("会话不存在"));
        if ("direct".equals(meta.type()) && row.senderId().equals(userId)) {
            throw DomainErrors.forbidden("FORBIDDEN", "不能领取自己发送的单聊红包");
        }

        ShareRow share = redPacketRepository.claimNextShare(packetId, userId, Instant.now())
                .orElseThrow(() -> DomainErrors.conflict("RED_PACKET_EXHAUSTED", "红包已领完"));

        UUID claimerAccount = walletRepository.findAccountIdByUserId(userId)
                .orElseThrow(() -> DomainErrors.notFound("钱包不存在"));
        boolean credited = walletRepository.transfer(
                UUID.randomUUID(),
                "RED_PACKET_CLAIM",
                packetId,
                "red-packet-claim:" + packetId + ":" + userId,
                WalletRepository.RED_PACKET_ESCROW_ACCOUNT,
                claimerAccount,
                share.amount()
        );
        if (!credited) {
            // 托管账户余额不足说明账务已经不一致，必须让事务失败而不是继续
            throw new IllegalStateException(
                    "Red packet escrow account is short of funds for packet " + packetId);
        }

        try {
            redPacketRepository.insertClaim(
                    UUID.randomUUID(), packetId, share.id(), userId,
                    share.amount(), idempotencyKey, Instant.now());
        } catch (DuplicateKeyException duplicate) {
            throw DomainErrors.conflict("DUPLICATE_REQUEST", "重复的领取请求");
        }

        redPacketRepository.applyClaim(packetId, share.amount());
        outboxRepository.append("red_packet", packetId, "red_packet:claimed",
                Map.of("redPacketId", packetId.toString(), "userId", userId.toString()));

        return new ClaimedRedPacket(
                redPacketRepository.findById(packetId).orElseThrow(),
                share.amount(),
                getWallet(userId),
                false
        );
    }

    /**
     * 过期退款，由 Worker 批量驱动。
     *
     * 每个红包独立事务：单个失败不影响本轮其他红包，失败的会在下一轮重新被扫描到。
     *
     * @return 实际退款的红包 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> refundExpiredPacket(UUID packetId) {
        Optional<RedPacketRow> row = redPacketRepository.findByIdForUpdate(packetId);
        if (row.isEmpty() || !refundIfExpired(row.get())) {
            return Optional.empty();
        }
        return Optional.of(packetId);
    }

    @Transactional(readOnly = true)
    public List<UUID> findExpiredPacketIds(int limit) {
        return redPacketRepository.findExpiredIds(limit);
    }

    /**
     * 若红包已过期则退还未领取金额。
     *
     * 调用前必须已经持有该红包的行锁，否则并发退款会重复入账。
     *
     * @return 是否执行了状态变更
     */
    private boolean refundIfExpired(RedPacketRow row) {
        if (Set.of("exhausted", "refunded", "expired").contains(row.status())) {
            return false;
        }
        if (row.expiresAt().isAfter(Instant.now())) {
            return false;
        }

        long remaining = row.remainingAmount();
        if (remaining > 0) {
            UUID senderAccount = walletRepository.findAccountIdByUserId(row.senderId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Red packet sender has no wallet account: " + row.senderId()));
            boolean returned = walletRepository.transfer(
                    UUID.randomUUID(),
                    "RED_PACKET_REFUND",
                    row.id(),
                    "red-packet-refund:" + row.id(),
                    WalletRepository.RED_PACKET_ESCROW_ACCOUNT,
                    senderAccount,
                    remaining
            );
            if (!returned) {
                throw new IllegalStateException(
                        "Red packet escrow account is short of funds for refund " + row.id());
            }
        }
        redPacketRepository.updateStatus(row.id(), "refunded");
        outboxRepository.append("red_packet", row.id(), "red_packet:refunded",
                Map.of("redPacketId", row.id().toString(), "amount", remaining));
        return true;
    }

    private ImMessage writeRedPacketMessage(
            UUID conversationId,
            UUID senderId,
            CreateRedPacket input,
            UUID packetId,
            Instant now
    ) {
        long seq = conversationRepository.allocateNextSeq(conversationId);
        ImMessage message = messageRepository.insert(
                UUID.randomUUID(),
                conversationId,
                seq,
                senderId,
                input.clientMessageId(),
                "red_packet",
                Map.of("redPacketId", packetId.toString(), "greeting", input.greeting().trim()),
                now
        );
        conversationRepository.updateSummary(conversationId, "[红包]", now);
        return message;
    }

    private CreatedRedPacket buildCreatedResult(UUID packetId, UUID senderId) {
        RedPacket packet = redPacketRepository.findById(packetId)
                .orElseThrow(() -> DomainErrors.notFound("红包不存在"));
        UUID messageId = redPacketRepository.findMessageId(packetId)
                .orElseThrow(() -> new IllegalStateException("Red packet message is missing: " + packetId));
        ImMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalStateException("Red packet message is missing: " + messageId));
        return new CreatedRedPacket(packet, message, getWallet(senderId));
    }

    private void validateInput(CreateRedPacket input, ConversationMeta meta) {
        if (!VALID_MODES.contains(input.mode())) {
            throw DomainErrors.validation("不支持的红包模式");
        }
        if (input.totalAmount() < input.totalCount()) {
            throw DomainErrors.validation("每个红包至少需要 1 分");
        }
        if ("direct".equals(meta.type()) && input.totalCount() != 1) {
            throw DomainErrors.validation("单聊红包只能有 1 份");
        }
        if (input.totalCount() > meta.memberCount()) {
            throw DomainErrors.validation("红包份数不能超过会话人数");
        }
    }
}
