package com.openim.server.domain;

import com.openim.server.domain.DomainModels.CallParticipant;
import com.openim.server.domain.DomainModels.CallRoom;
import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.DomainModels.RedPacket;
import com.openim.server.domain.DomainModels.RedPacketClaim;
import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.DomainModels.Wallet;
import com.openim.server.domain.DomainModels.WalletEntry;
import com.openim.server.domain.DomainResults.ClaimedRedPacket;
import com.openim.server.domain.DomainResults.CreatedRedPacket;
import com.openim.server.domain.Requests.CreateCall;
import com.openim.server.domain.Requests.CreateRedPacket;
import com.openim.server.domain.Requests.SendMessage;
import com.openim.server.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 内存数据仓储（MVP 使用）
 *
 * 所有数据存储在单进程内存中，重启不保留。包含用户、会话、消息、
 * 钱包、红包和通话的完整 CRUD 操作，以及幂等去重、权限校验和业务规则检查。
 *
 * 注意：生产环境应替换为 PostgreSQL + Redis + Outbox 模式。
 */
@Repository
public class InMemoryStore {
    private final RedPacketAllocator allocator;

    // 用户存储：userId → UserState
    private final Map<String, UserState> users = new LinkedHashMap<>();
    // 会话存储：conversationId → ConversationState
    private final Map<String, ConversationState> conversations = new LinkedHashMap<>();
    // 消息存储：conversationId → [ImMessage]
    private final Map<String, List<ImMessage>> messages = new HashMap<>();
    // 消息幂等去重：senderId:clientMessageId → ImMessage
    private final Map<String, ImMessage> messageDedupe = new HashMap<>();
    // 钱包存储：userId → WalletState
    private final Map<String, WalletState> wallets = new HashMap<>();
    // 红包存储：redPacketId → RedPacketState
    private final Map<String, RedPacketState> redPackets = new HashMap<>();
    // 红包幂等去重：senderId:idempotencyKey → redPacketId
    private final Map<String, String> redPacketDedupe = new HashMap<>();
    // 通话存储：callRoomId → CallRoomState
    private final Map<String, CallRoomState> calls = new HashMap<>();
    // 通话幂等去重：creatorId:idempotencyKey → callRoomId
    private final Map<String, String> callDedupe = new HashMap<>();

    public InMemoryStore(RedPacketAllocator allocator) {
        this.allocator = allocator;
        seed();
    }

    /** 返回所有用户列表 */
    public synchronized List<User> listUsers() {
        return users.values().stream().map(UserState::view).toList();
    }

    /** 根据 userId 获取用户 */
    public synchronized User getUser(String userId) {
        return requireUser(userId).view();
    }

    /** 设置用户在线/离线状态 */
    public synchronized void setUserOnline(String userId, boolean online) {
        UserState user = users.get(userId);
        if (user == null) {
            return;
        }
        user.status = online ? "online" : "offline";
        user.lastSeenAt = online ? null : Instant.now();
    }

    /** 返回指定用户可见的会话列表（按最后活跃时间降序） */
    public synchronized List<Conversation> listConversations(String userId) {
        requireUser(userId);
        return conversations.values().stream()
                .filter(conversation -> conversation.memberIds.contains(userId))
                .sorted(Comparator.comparing(
                        (ConversationState conversation) -> conversation.lastActiveAt
                ).reversed())
                .map(conversation -> conversation.viewFor(userId, users))
                .toList();
    }

    /**
     * 返回指定会话中 afterSeq 之后的消息（最多 100 条）
     * 用于历史消息加载和增量拉取。
     */
    public synchronized List<ImMessage> listMessages(
            String conversationId,
            String userId,
            long afterSeq
    ) {
        requireConversation(conversationId, userId);
        List<ImMessage> filtered = messages.getOrDefault(conversationId, List.of()).stream()
                .filter(message -> message.seq() > afterSeq)
                .toList();
        int toIndex = Math.min(filtered.size(), 100);
        return List.copyOf(filtered.subList(0, toIndex));
    }

    /**
     * 创建文本消息
     * - 校验消息内容和长度
     * - 根据 senderId + clientMessageId 幂等去重
     * - 自动分配会话序号 (seq) 并更新会话摘要
     */
    public synchronized ImMessage createMessage(String userId, SendMessage input) {
        ConversationState conversation = requireConversation(input.conversationId(), userId);
        if (!"text".equals(input.type())) {
            throw badRequest("VALIDATION_ERROR", "当前接口只接受文本消息");
        }
        String text = input.content() == null
                ? null
                : Objects.toString(input.content().get("text"), null);
        if (text == null || text.isBlank() || text.length() > 4_000) {
            throw badRequest("VALIDATION_ERROR", "消息正文长度必须为 1–4000 字符");
        }

        String dedupeKey = userId + ":" + input.clientMessageId();
        ImMessage existing = messageDedupe.get(dedupeKey);
        if (existing != null) {
            return existing;
        }

        ImMessage message = new ImMessage(
                UUID.randomUUID().toString(),
                conversation.id,
                conversation.nextSeq++,
                userId,
                input.clientMessageId(),
                "text",
                Map.of("text", text.trim()),
                Instant.now()
        );
        messages.get(conversation.id).add(message);
        messageDedupe.put(dedupeKey, message);
        conversation.lastMessage = text.trim();
        conversation.lastActiveAt = message.sentAt();
        return message;
    }

    /** 获取用户钱包余额和流水 */
    public synchronized Wallet getWallet(String userId) {
        WalletState wallet = wallets.get(userId);
        if (wallet == null) {
            throw notFound("钱包不存在");
        }
        return wallet.view();
    }

    /**
     * 创建红包
     * - 校验输入（模式、金额、份数、权限）
     * - 幂等去重（idempotencyKey）
     * - 扣除发送者虚拟余额
     * - 生成红包份额分配并创建系统消息
     */
    public synchronized CreatedRedPacket createRedPacket(
            String userId,
            CreateRedPacket input
    ) {
        ConversationState conversation = requireConversation(input.conversationId(), userId);
        validateRedPacketInput(input, conversation);
        String dedupeKey = userId + ":" + input.idempotencyKey();
        String existingId = redPacketDedupe.get(dedupeKey);
        if (existingId != null) {
            RedPacketState existing = requireRedPacket(existingId);
            return new CreatedRedPacket(
                    existing.view(),
                    findMessage(existing.messageId),
                    getWallet(userId)
            );
        }
        if (messageDedupe.containsKey(userId + ":" + input.clientMessageId())) {
            throw conflict("DUPLICATE_MESSAGE_ID", "消息幂等键已被使用");
        }

        WalletState wallet = wallets.get(userId);
        if (wallet == null || wallet.balance < input.totalAmount()) {
            throw conflict("INSUFFICIENT_BALANCE", "虚拟余额不足");
        }

        List<Long> shareAmounts = "random".equals(input.mode())
                ? allocator.random(input.totalAmount(), input.totalCount())
                : allocator.equal(input.totalAmount(), input.totalCount());
        String packetId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        wallet.balance -= input.totalAmount();
        wallet.entries.addFirst(new WalletEntry(
                UUID.randomUUID().toString(),
                "red_packet_send",
                -input.totalAmount(),
                wallet.balance,
                packetId,
                now
        ));

        ImMessage message = new ImMessage(
                UUID.randomUUID().toString(),
                conversation.id,
                conversation.nextSeq++,
                userId,
                input.clientMessageId(),
                "red_packet",
                Map.of(
                        "redPacketId", packetId,
                        "greeting", input.greeting().trim()
                ),
                now
        );
        messages.get(conversation.id).add(message);
        messageDedupe.put(userId + ":" + input.clientMessageId(), message);
        conversation.lastMessage = "[红包]";
        conversation.lastActiveAt = now;

        RedPacketState packet = new RedPacketState(
                packetId,
                conversation.id,
                userId,
                input.mode(),
                input.totalAmount(),
                input.totalCount(),
                input.greeting().trim(),
                now.plus(24, ChronoUnit.HOURS),
                input.idempotencyKey(),
                message.id(),
                shareAmounts.stream().map(ShareState::new).toList()
        );
        redPackets.put(packet.id, packet);
        redPacketDedupe.put(dedupeKey, packet.id);
        return new CreatedRedPacket(packet.view(), message, wallet.view());
    }

    /** 查询红包详情（访问时触发过期退款检查） */
    public synchronized RedPacket getRedPacket(String packetId, String userId) {
        RedPacketState packet = requireRedPacket(packetId);
        requireConversation(packet.conversationId, userId);
        refundIfExpired(packet);
        return packet.view();
    }

    /**
     * 领取红包
     * - 幂等重入（已领取则返回上次结果）
     * - 检查过期/领完状态
     * - 单聊红包不可自己领取
     * - 从份额池分配一个未领取的金额
     */
    public synchronized ClaimedRedPacket claimRedPacket(
            String packetId,
            String userId,
            String idempotencyKey
    ) {
        RedPacketState packet = requireRedPacket(packetId);
        ConversationState conversation = requireConversation(packet.conversationId, userId);
        refundIfExpired(packet);

        RedPacketClaim existing = packet.claims.stream()
                .filter(claim -> claim.userId().equals(userId))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return new ClaimedRedPacket(
                    packet.view(),
                    existing.amount(),
                    getWallet(userId),
                    true
            );
        }
        if ("expired".equals(packet.status) || "refunded".equals(packet.status)) {
            throw conflict("RED_PACKET_EXPIRED", "红包已过期");
        }
        if ("exhausted".equals(packet.status)) {
            throw conflict("RED_PACKET_EXHAUSTED", "红包已领完");
        }
        if ("direct".equals(conversation.type) && packet.senderId.equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "不能领取自己发送的单聊红包");
        }

        ShareState share = packet.shares.stream()
                .filter(candidate -> candidate.claimedBy == null)
                .findFirst()
                .orElseThrow(() -> conflict("RED_PACKET_EXHAUSTED", "红包已领完"));
        Instant now = Instant.now();
        share.claimedBy = userId;
        packet.claimedAmount += share.amount;
        packet.claimedCount += 1;
        packet.claims.add(new RedPacketClaim(
                UUID.randomUUID().toString(),
                userId,
                share.amount,
                now
        ));
        packet.status = packet.claimedCount == packet.totalCount
                ? "exhausted"
                : "partially_claimed";

        WalletState wallet = wallets.get(userId);
        if (wallet == null) {
            throw notFound("钱包不存在");
        }
        wallet.balance += share.amount;
        wallet.entries.addFirst(new WalletEntry(
                UUID.randomUUID().toString(),
                "red_packet_claim",
                share.amount,
                wallet.balance,
                packet.id,
                now
        ));
        return new ClaimedRedPacket(packet.view(), share.amount, wallet.view(), false);
    }

    /**
     * 创建通话房间
     * - 校验被邀请人是否为会话成员
     * - 检查创建者是否已在其他通话中（忙线检测）
     * - 自动设置 45 秒超时
     */
    public synchronized CallRoom createCall(
            String creatorId,
            String deviceId,
            CreateCall input
    ) {
        ConversationState conversation = requireConversation(input.conversationId(), creatorId);
        if (!Set.of("audio", "video").contains(input.mediaType())) {
            throw badRequest("VALIDATION_ERROR", "通话类型必须是 audio 或 video");
        }
        String dedupeKey = creatorId + ":" + input.idempotencyKey();
        String existingId = callDedupe.get(dedupeKey);
        if (existingId != null) {
            return requireCall(existingId).view();
        }

        List<String> invitees = new ArrayList<>(new LinkedHashSet<>(input.inviteeIds()));
        invitees.remove(creatorId);
        if (invitees.isEmpty() || invitees.stream().anyMatch(id -> !conversation.memberIds.contains(id))) {
            throw badRequest("VALIDATION_ERROR", "被邀请人必须是会话成员");
        }
        boolean busy = calls.values().stream().anyMatch(room ->
                !"ended".equals(room.status) && room.participants.stream().anyMatch(participant ->
                        participant.userId.equals(creatorId) && "joined".equals(participant.state)
                )
        );
        if (busy) {
            throw conflict("CALL_BUSY", "你已在其他通话中");
        }

        Instant now = Instant.now();
        List<CallParticipantState> participants = new ArrayList<>();
        participants.add(new CallParticipantState(creatorId, "joined", deviceId));
        invitees.forEach(id -> participants.add(new CallParticipantState(id, "invited", null)));
        CallRoomState room = new CallRoomState(
                UUID.randomUUID().toString(),
                conversation.id,
                creatorId,
                input.mediaType(),
                "ringing",
                participants,
                now,
                now.plusSeconds(45)
        );
        calls.put(room.id, room);
        callDedupe.put(dedupeKey, room.id);
        return room.view();
    }

    /** 查询通话房间 */
    public synchronized CallRoom getCall(String roomId, String userId) {
        CallRoomState room = requireCall(roomId);
        requireCallParticipant(room, userId);
        return room.view();
    }

    /** 接听通话：标记参与者为 joined，状态切换为 active */
    public synchronized CallRoom acceptCall(
            String roomId,
            String userId,
            String deviceId
    ) {
        CallRoomState room = requireCall(roomId);
        if ("ended".equals(room.status) || !room.expiresAt.isAfter(Instant.now())) {
            throw conflict("CALL_EXPIRED", "通话邀请已过期");
        }
        CallParticipantState participant = requireCallParticipant(room, userId);
        if ("joined".equals(participant.state)) {
            return room.view();
        }
        participant.state = "joined";
        participant.joinedDeviceId = deviceId;
        room.status = "active";
        return room.view();
    }

    /** 拒绝通话：若全部拒绝则结束通话 */
    public synchronized CallRoom rejectCall(String roomId, String userId) {
        CallRoomState room = requireCall(roomId);
        CallParticipantState participant = requireCallParticipant(room, userId);
        participant.state = "rejected";
        if (room.participants.stream().noneMatch(item -> "invited".equals(item.state))) {
            endCall(room, "rejected");
        }
        return room.view();
    }

    /** 离开通话：加入者少于 2 人时自动结束 */
    public synchronized CallRoom leaveCall(String roomId, String userId) {
        CallRoomState room = requireCall(roomId);
        CallParticipantState participant = requireCallParticipant(room, userId);
        participant.state = "left";
        participant.joinedDeviceId = null;
        long joined = room.participants.stream()
                .filter(item -> "joined".equals(item.state))
                .count();
        if (joined < 2) {
            endCall(room, "participants_left");
        }
        return room.view();
    }

    /**
     * 校验 WebRTC 信令双方在通话中的状态
     * 返回目标用户的 deviceId 供信令转发。
     */
    public synchronized String validateRtcSignal(
            String roomId,
            String fromUserId,
            String targetUserId
    ) {
        CallRoomState room = requireCall(roomId);
        if ("ended".equals(room.status)) {
            throw conflict("CALL_EXPIRED", "通话已经结束");
        }
        CallParticipantState from = requireCallParticipant(room, fromUserId);
        CallParticipantState target = requireCallParticipant(room, targetUserId);
        if (!"joined".equals(from.state) || !"joined".equals(target.state)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "FORBIDDEN",
                    "信令双方必须已加入房间"
            );
        }
        if (target.joinedDeviceId == null) {
            throw conflict("CALL_NOT_READY", "目标设备尚未就绪");
        }
        return target.joinedDeviceId;
    }

    /** 根据 userId 查找用户，不存在则抛出 NOT_FOUND */
    private UserState requireUser(String userId) {
        UserState user = users.get(userId);
        if (user == null) {
            throw notFound("用户不存在");
        }
        return user;
    }

    /** 根据 id 查找会话并校验成员权限 */
    private ConversationState requireConversation(String id, String userId) {
        ConversationState conversation = conversations.get(id);
        if (conversation == null) {
            throw notFound("会话不存在");
        }
        if (!conversation.memberIds.contains(userId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "NOT_A_MEMBER",
                    "你不是该会话成员"
            );
        }
        return conversation;
    }

    /** 根据 id 查找红包 */
    private RedPacketState requireRedPacket(String id) {
        RedPacketState packet = redPackets.get(id);
        if (packet == null) {
            throw notFound("红包不存在");
        }
        return packet;
    }

    /** 根据 id 查找通话房间 */
    private CallRoomState requireCall(String id) {
        CallRoomState room = calls.get(id);
        if (room == null) {
            throw notFound("通话不存在");
        }
        return room;
    }

    /** 在通话房间中查找参与者，不存在则抛出 FORBIDDEN */
    private CallParticipantState requireCallParticipant(CallRoomState room, String userId) {
        return room.participants.stream()
                .filter(item -> item.userId.equals(userId))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.FORBIDDEN,
                        "FORBIDDEN",
                        "你不在该通话中"
                ));
    }

    /** 通过消息 ID 在所有会话的消息中查找 */
    private ImMessage findMessage(String id) {
        return messages.values().stream()
                .flatMap(List::stream)
                .filter(message -> message.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Red packet message is missing"));
    }

    /** 校验红包创建参数 */
    private void validateRedPacketInput(
            CreateRedPacket input,
            ConversationState conversation
    ) {
        if (!Set.of("fixed", "equal", "random").contains(input.mode())) {
            throw badRequest("VALIDATION_ERROR", "不支持的红包模式");
        }
        if (input.totalAmount() < input.totalCount()) {
            throw badRequest("VALIDATION_ERROR", "每个红包至少需要 1 分");
        }
        if ("direct".equals(conversation.type) && input.totalCount() != 1) {
            throw badRequest("VALIDATION_ERROR", "单聊红包只能有 1 份");
        }
        if (input.totalCount() > conversation.memberIds.size()) {
            throw badRequest("VALIDATION_ERROR", "红包份数不能超过会话人数");
        }
    }

    /** 红包过期退款检查：在访问红包时自动将未领金额退还给发送者 */
    private void refundIfExpired(RedPacketState packet) {
        if (Set.of("exhausted", "refunded").contains(packet.status) ||
                packet.expiresAt.isAfter(Instant.now())) {
            return;
        }
        long remaining = packet.totalAmount - packet.claimedAmount;
        packet.status = "expired";
        if (remaining > 0) {
            WalletState senderWallet = wallets.get(packet.senderId);
            if (senderWallet != null) {
                senderWallet.balance += remaining;
                senderWallet.entries.addFirst(new WalletEntry(
                        UUID.randomUUID().toString(),
                        "red_packet_refund",
                        remaining,
                        senderWallet.balance,
                        packet.id,
                        Instant.now()
                ));
            }
        }
        packet.status = "refunded";
    }

    /** 终止通话房间 */
    private void endCall(CallRoomState room, String reason) {
        room.status = "ended";
        room.endedAt = Instant.now();
        room.endReason = reason;
    }

    /** 构造 400 请求参数错误异常 */
    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** 构造 409 业务冲突异常（重复、余额不足等） */
    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 构造 404 资源不存在异常 */
    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 初始化种子数据：3 个演示用户、2 个演示会话和若干条历史消息 */
    private void seed() {
        Instant now = Instant.now();
        putUser(new UserState("alice", "alice", "Alice 林夏", "林", "offline", now));
        putUser(new UserState("bob", "bob", "Bob 陈默", "陈", "offline", now));
        putUser(new UserState("carol", "carol", "Carol 周晴", "周", "offline", now));

        putConversation(new ConversationState(
                "conv-alice-bob",
                "direct",
                "好友会话",
                "友",
                List.of("alice", "bob"),
                "周末一起去看展吗？",
                now,
                0,
                3
        ));
        putConversation(new ConversationState(
                "conv-group",
                "group",
                "周末出游计划",
                "游",
                List.of("alice", "bob", "carol"),
                "大家晚上八点碰一下",
                now.minusSeconds(60),
                2,
                3
        ));

        addSeedMessage("conv-alice-bob", 1, "alice", "嗨，最近怎么样？");
        addSeedMessage("conv-alice-bob", 2, "bob", "周末一起去看展吗？");
        addSeedMessage("conv-group", 1, "carol", "路线我整理好了，发群里啦");
        addSeedMessage("conv-group", 2, "bob", "大家晚上八点碰一下");
    }

    /** 注册用户及其初始钱包（每人 500 元 = 50000 分） */
    private void putUser(UserState user) {
        users.put(user.id, user);
        wallets.put(user.id, new WalletState(user.id, 50_000));
    }

    private void putConversation(ConversationState conversation) {
        conversations.put(conversation.id, conversation);
        messages.put(conversation.id, new ArrayList<>());
    }

    private void addSeedMessage(
            String conversationId,
            long seq,
            String senderId,
            String text
    ) {
        ImMessage message = new ImMessage(
                UUID.randomUUID().toString(),
                conversationId,
                seq,
                senderId,
                "seed-" + conversationId + "-" + seq,
                "text",
                Map.of("text", text),
                Instant.now().minusSeconds((10 - seq) * 60)
        );
        messages.get(conversationId).add(message);
        messageDedupe.put(senderId + ":" + message.clientMessageId(), message);
    }

    private static final class UserState {
        private final String id;
        private final String username;
        private final String displayName;
        private final String avatar;
        private String status;
        private Instant lastSeenAt;

        private UserState(
                String id,
                String username,
                String displayName,
                String avatar,
                String status,
                Instant lastSeenAt
        ) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
            this.avatar = avatar;
            this.status = status;
            this.lastSeenAt = lastSeenAt;
        }

        private User view() {
            return new User(id, username, displayName, avatar, status, lastSeenAt);
        }
    }

    private static final class ConversationState {
        private final String id;
        private final String type;
        private final String title;
        private final String avatar;
        private final List<String> memberIds;
        private String lastMessage;
        private Instant lastActiveAt;
        private final int unreadCount;
        private long nextSeq;

        private ConversationState(
                String id,
                String type,
                String title,
                String avatar,
                List<String> memberIds,
                String lastMessage,
                Instant lastActiveAt,
                int unreadCount,
                long nextSeq
        ) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.avatar = avatar;
            this.memberIds = List.copyOf(memberIds);
            this.lastMessage = lastMessage;
            this.lastActiveAt = lastActiveAt;
            this.unreadCount = unreadCount;
            this.nextSeq = nextSeq;
        }

        private Conversation viewFor(String userId, Map<String, UserState> users) {
            if ("direct".equals(type)) {
                String otherId = memberIds.stream()
                        .filter(id -> !id.equals(userId))
                        .findFirst()
                        .orElse(userId);
                UserState other = users.get(otherId);
                return new Conversation(
                        id,
                        type,
                        other == null ? title : other.displayName,
                        other == null ? avatar : other.avatar,
                        memberIds,
                        lastMessage,
                        lastActiveAt,
                        unreadCount
                );
            }
            return new Conversation(
                    id,
                    type,
                    title,
                    avatar,
                    memberIds,
                    lastMessage,
                    lastActiveAt,
                    unreadCount
            );
        }
    }

    private static final class WalletState {
        private final String userId;
        private long balance;
        private final java.util.ArrayDeque<WalletEntry> entries = new java.util.ArrayDeque<>();

        private WalletState(String userId, long balance) {
            this.userId = userId;
            this.balance = balance;
        }

        private Wallet view() {
            return new Wallet(userId, balance, List.copyOf(entries));
        }
    }

    private static final class ShareState {
        private final long amount;
        private String claimedBy;

        private ShareState(long amount) {
            this.amount = amount;
        }
    }

    private static final class RedPacketState {
        private final String id;
        private final String conversationId;
        private final String senderId;
        private final String mode;
        private final long totalAmount;
        private final int totalCount;
        private long claimedAmount;
        private int claimedCount;
        private final String greeting;
        private String status = "published";
        private final Instant expiresAt;
        @SuppressWarnings("unused")
        private final String idempotencyKey;
        private final String messageId;
        private final List<ShareState> shares;
        private final List<RedPacketClaim> claims = new ArrayList<>();

        private RedPacketState(
                String id,
                String conversationId,
                String senderId,
                String mode,
                long totalAmount,
                int totalCount,
                String greeting,
                Instant expiresAt,
                String idempotencyKey,
                String messageId,
                List<ShareState> shares
        ) {
            this.id = id;
            this.conversationId = conversationId;
            this.senderId = senderId;
            this.mode = mode;
            this.totalAmount = totalAmount;
            this.totalCount = totalCount;
            this.greeting = greeting;
            this.expiresAt = expiresAt;
            this.idempotencyKey = idempotencyKey;
            this.messageId = messageId;
            this.shares = new ArrayList<>(shares);
        }

        private RedPacket view() {
            return new RedPacket(
                    id,
                    conversationId,
                    senderId,
                    mode,
                    totalAmount,
                    totalCount,
                    claimedAmount,
                    claimedCount,
                    greeting,
                    status,
                    expiresAt,
                    List.copyOf(claims)
            );
        }
    }

    private static final class CallParticipantState {
        private final String userId;
        private String state;
        private String joinedDeviceId;

        private CallParticipantState(String userId, String state, String joinedDeviceId) {
            this.userId = userId;
            this.state = state;
            this.joinedDeviceId = joinedDeviceId;
        }

        private CallParticipant view() {
            return new CallParticipant(userId, state, joinedDeviceId);
        }
    }

    private static final class CallRoomState {
        private final String id;
        private final String conversationId;
        private final String creatorId;
        private final String mediaType;
        private String status;
        private final List<CallParticipantState> participants;
        private final Instant createdAt;
        private final Instant expiresAt;
        private Instant endedAt;
        private String endReason;

        private CallRoomState(
                String id,
                String conversationId,
                String creatorId,
                String mediaType,
                String status,
                List<CallParticipantState> participants,
                Instant createdAt,
                Instant expiresAt
        ) {
            this.id = id;
            this.conversationId = conversationId;
            this.creatorId = creatorId;
            this.mediaType = mediaType;
            this.status = status;
            this.participants = participants;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        private CallRoom view() {
            return new CallRoom(
                    id,
                    conversationId,
                    creatorId,
                    mediaType,
                    status,
                    participants.stream().map(CallParticipantState::view).toList(),
                    createdAt,
                    expiresAt,
                    endedAt,
                    endReason
            );
        }
    }
}
