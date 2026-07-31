package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.DomainModels.RedPacket;
import com.openim.server.domain.DomainModels.RedPacketClaim;
import com.openim.server.domain.DomainModels.Wallet;
import com.openim.server.domain.DomainResults.ClaimedRedPacket;
import com.openim.server.domain.DomainResults.CreatedRedPacket;
import com.openim.server.domain.RedPacketAllocator;
import com.openim.server.domain.Requests.CreateRedPacket;
import com.openim.server.domain.repository.AuditRepository;
import com.openim.server.domain.repository.ConversationRepository;
import com.openim.server.domain.repository.ConversationRepository.ConversationMeta;
import com.openim.server.domain.repository.MessageRepository;
import com.openim.server.domain.repository.OutboxRepository;
import com.openim.server.domain.repository.RedPacketRepository;
import com.openim.server.domain.repository.RedPacketRepository.RedPacketRow;
import com.openim.server.domain.repository.RedPacketRepository.ShareRow;
import com.openim.server.domain.repository.WalletRepository;
import com.openim.server.observability.DomainMetrics;
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
 *
 * <b>审计</b>：每一次资金变动都写 {@code audit_logs}。此前这里只写 outbox
 * 和指标——outbox 是投递队列（有保留期，会被清理），指标是聚合值（不含单笔明细），
 * 两者都不能回答"这笔钱是谁在什么时候动的"。资金操作没有审计是合规硬伤，
 * 而且恰恰在需要它的时候（对账不平、用户投诉少钱）才会发现它不存在。
 *
 * 审计动作以 {@code red_packet.} 开头，因此不参与
 * {@link AuditRepository#deleteOlderThan} 的保留期清理——账务记录的追溯期
 * 远长于一般操作日志。
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
    private final AuditRepository auditRepository;
    private final DomainMetrics metrics;

    public RedPacketService(
            RedPacketRepository redPacketRepository,
            WalletRepository walletRepository,
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            ConversationService conversationService,
            RedPacketAllocator allocator,
            OutboxRepository outboxRepository,
            AuditRepository auditRepository,
            DomainMetrics metrics
    ) {
        this.redPacketRepository = redPacketRepository;
        this.walletRepository = walletRepository;
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.allocator = allocator;
        this.outboxRepository = outboxRepository;
        this.auditRepository = auditRepository;
        this.metrics = metrics;
    }

    /** 查询用户钱包（余额与流水）。钱包不存在时抛 404 */
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

        // 审计与 outbox 是两件事：outbox 有保留期会被清理，且它只是投递队列。
        // 资金明细必须独立留痕，否则"这笔钱是谁在什么时候动的"无法回答。
        auditRepository.record("USER", senderId, "red_packet.issued",
                "RED_PACKET", packetId, null,
                Map.of(
                        "conversationId", conversationId.toString(),
                        "totalAmount", input.totalAmount(),
                        "totalCount", input.totalCount(),
                        "mode", input.mode()
                ));
        metrics.recordRedPacketCreated();

        return new CreatedRedPacket(
                redPacketRepository.findById(packetId).orElseThrow(),
                message,
                getWallet(senderId)
        );
    }

    /**
     * 查看红包详情。
     *
     * 行锁读取并在同一事务内顺带处理过期退款——查看和领取都可能撞上过期，
     * 统一在行锁保护下执行，避免并发退款重复入账。
     */
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
                Map.of(
                        "redPacketId", packetId.toString(),
                        "conversationId", row.conversationId().toString(),
                        "userId", userId.toString()
                ));
        auditRepository.record("USER", userId, "red_packet.claimed",
                "RED_PACKET", packetId, null,
                Map.of(
                        "conversationId", row.conversationId().toString(),
                        "amount", share.amount(),
                        "shareId", share.id().toString()
                ));
        metrics.recordRedPacketClaimed();

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

    /** 扫描已过期且尚未退款的红包，供 Worker 批量驱动退款。limit 限制单轮处理量 */
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
                Map.of(
                        "redPacketId", row.id().toString(),
                        "conversationId", row.conversationId().toString(),
                        "amount", remaining
                ));
        // 退款由 Worker 触发而非用户操作，因此 actor 是 SYSTEM。
        // 归属到发送者会让审计看起来像"用户主动退了款"，那是错的。
        auditRepository.record("SYSTEM", null, "red_packet.refunded",
                "RED_PACKET", row.id(), null,
                Map.of(
                        "conversationId", row.conversationId().toString(),
                        "senderId", row.senderId().toString(),
                        "refundedAmount", remaining
                ));
        metrics.recordRedPacketRefunded();
        return true;
    }

    /** 写入红包消息（type=red_packet，载荷含红包 ID 与祝福语）并更新会话摘要 */
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

    /** 组装创建红包的返回结果：红包、关联消息与发送者最新钱包。幂等命中与首次创建共用 */
    private CreatedRedPacket buildCreatedResult(UUID packetId, UUID senderId) {
        RedPacket packet = redPacketRepository.findById(packetId)
                .orElseThrow(() -> DomainErrors.notFound("红包不存在"));
        UUID messageId = redPacketRepository.findMessageId(packetId)
                .orElseThrow(() -> new IllegalStateException("Red packet message is missing: " + packetId));
        ImMessage message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalStateException("Red packet message is missing: " + messageId));
        return new CreatedRedPacket(packet, message, getWallet(senderId));
    }

    /** 校验红包创建入参：模式合法、总额够分（每份至少 1 分）、单聊限 1 份、份数不超会话人数 */
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
