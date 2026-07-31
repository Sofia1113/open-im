package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.CallParticipant;
import com.openim.server.domain.DomainModels.CallRoom;
import com.openim.server.domain.Requests.CreateCall;
import com.openim.server.domain.repository.CallRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 通话房间状态机与信令授权
 *
 * 服务端只管理房间权限和信令转发授权，不接触媒体流。
 */
@Service
public class CallService {
    /** 邀请超时，与客户端响铃时长约定一致 */
    private static final long INVITE_TIMEOUT_SECONDS = 45;

    private final CallRepository callRepository;
    private final ConversationService conversationService;

    public CallService(CallRepository callRepository, ConversationService conversationService) {
        this.callRepository = callRepository;
        this.conversationService = conversationService;
    }

    /**
     * 创建通话房间并邀请成员。
     *
     * 幂等：同一 (creatorId, idempotencyKey) 重复提交返回已创建的房间，不新建。
     * 创建者以 joined 状态入房，被邀请者以 invited 状态等待接听；创建者
     * 已在他通时直接拒绝，不进入抢锁路径。
     */
    @Transactional
    public CallRoom createCall(UUID creatorId, String deviceId, CreateCall input) {
        UUID conversationId = DomainErrors.parseId(input.conversationId(), "会话");
        conversationService.requireMembership(conversationId, creatorId);

        if (!Set.of("audio", "video").contains(input.mediaType())) {
            throw DomainErrors.validation("通话类型必须是 audio 或 video");
        }

        Optional<UUID> existing = callRepository
                .findIdByCreatorIdempotency(creatorId, input.idempotencyKey());
        if (existing.isPresent()) {
            return callRepository.findById(existing.get())
                    .orElseThrow(() -> DomainErrors.notFound("通话不存在"));
        }

        List<UUID> invitees = resolveInvitees(input, creatorId, conversationId);

        if (callRepository.isUserBusy(creatorId)) {
            throw DomainErrors.conflict("CALL_BUSY", "你已在其他通话中");
        }

        UUID roomId = UUID.randomUUID();
        Instant now = Instant.now();
        callRepository.insertRoom(
                roomId, conversationId, creatorId, input.mediaType(),
                now, now.plusSeconds(INVITE_TIMEOUT_SECONDS), input.idempotencyKey());
        callRepository.insertParticipant(roomId, creatorId, "joined", deviceId, now);
        for (UUID invitee : invitees) {
            callRepository.insertParticipant(roomId, invitee, "invited", null, null);
        }
        return callRepository.findById(roomId).orElseThrow();
    }

    /** 查看通话详情。调用者必须是房间参与者，否则 403 */
    @Transactional(readOnly = true)
    public CallRoom getCall(UUID roomId, UUID userId) {
        CallRoom room = callRepository.findById(roomId)
                .orElseThrow(() -> DomainErrors.notFound("通话不存在"));
        requireParticipant(room, userId);
        return room;
    }

    /**
     * 接听通话。
     *
     * 行锁内先检查过期状态再更新，防止"接听"与"刚过期"并发时把已结束的
     * 通话重新激活。重复接听（已是 joined）幂等返回当前房间。
     */
    @Transactional
    public CallRoom acceptCall(UUID roomId, UUID userId, String deviceId) {
        CallRoom room = lockRoom(roomId);
        if ("ended".equals(room.status()) || !room.expiresAt().isAfter(Instant.now())) {
            throw DomainErrors.conflict("CALL_EXPIRED", "通话邀请已过期");
        }
        CallParticipant participant = requireParticipant(room, userId);
        if ("joined".equals(participant.state())) {
            return room;
        }
        callRepository.updateParticipantState(roomId, userId, "joined", deviceId);
        callRepository.markActive(roomId);
        return callRepository.findById(roomId).orElseThrow();
    }

    /** 拒接通话。全部被邀请者都已拒绝时，通话直接结束 */
    @Transactional
    public CallRoom rejectCall(UUID roomId, UUID userId) {
        CallRoom room = lockRoom(roomId);
        requireParticipant(room, userId);
        callRepository.updateParticipantState(roomId, userId, "rejected", null);
        if (callRepository.countParticipantsInState(roomId, "invited") == 0) {
            callRepository.endRoom(roomId, "rejected");
        }
        return callRepository.findById(roomId).orElseThrow();
    }

    /** 挂断通话。剩余已加入人数不足两人时结束通话 */
    @Transactional
    public CallRoom leaveCall(UUID roomId, UUID userId) {
        CallRoom room = lockRoom(roomId);
        requireParticipant(room, userId);
        callRepository.updateParticipantState(roomId, userId, "left", null);
        if (callRepository.countParticipantsInState(roomId, "joined") < 2) {
            callRepository.endRoom(roomId, "participants_left");
        }
        return callRepository.findById(roomId).orElseThrow();
    }

    /**
     * 校验 WebRTC 信令双方状态并返回目标设备。
     *
     * 信令只在双方都已加入房间时才转发，且必须定向到目标用户实际接听的那台设备，
     * 避免同账号其他设备收到不属于它的 SDP。
     */
    @Transactional(readOnly = true)
    public String validateRtcSignal(String roomIdValue, UUID fromUserId, String targetUserIdValue) {
        UUID roomId = DomainErrors.parseId(roomIdValue, "通话");
        UUID targetUserId = DomainErrors.parseId(targetUserIdValue, "用户");
        CallRoom room = callRepository.findById(roomId)
                .orElseThrow(() -> DomainErrors.notFound("通话不存在"));
        if ("ended".equals(room.status())) {
            throw DomainErrors.conflict("CALL_EXPIRED", "通话已经结束");
        }
        CallParticipant from = requireParticipant(room, fromUserId);
        CallParticipant target = requireParticipant(room, targetUserId);
        if (!"joined".equals(from.state()) || !"joined".equals(target.state())) {
            throw DomainErrors.forbidden("FORBIDDEN", "信令双方必须已加入房间");
        }
        if (target.joinedDeviceId() == null) {
            throw DomainErrors.conflict("CALL_NOT_READY", "目标设备尚未就绪");
        }
        return target.joinedDeviceId();
    }

    /** 收敛超时未接听的通话，由 Worker 调用 */
    @Transactional
    public List<UUID> expireTimedOutCalls(int limit) {
        List<UUID> roomIds = callRepository.lockExpiredRoomIds(limit);
        for (UUID roomId : roomIds) {
            callRepository.endRoom(roomId, "timeout");
        }
        return roomIds;
    }

    /** 解析被邀请人：必须是会话有效成员，剔除创建者自己，且至少留下一位 */
    private List<UUID> resolveInvitees(CreateCall input, UUID creatorId, UUID conversationId) {
        Set<UUID> members = new LinkedHashSet<>(conversationService.listActiveMemberIds(conversationId));
        LinkedHashSet<UUID> invitees = new LinkedHashSet<>();
        for (String rawId : input.inviteeIds()) {
            UUID inviteeId = DomainErrors.parseId(rawId, "用户");
            if (inviteeId.equals(creatorId)) {
                continue;
            }
            if (!members.contains(inviteeId)) {
                throw DomainErrors.validation("被邀请人必须是会话成员");
            }
            invitees.add(inviteeId);
        }
        if (invitees.isEmpty()) {
            throw DomainErrors.validation("被邀请人必须是会话成员");
        }
        return List.copyOf(invitees);
    }

    /** 行锁读取房间，串行化同一房间的状态变更（接听/拒绝/挂断/过期），避免状态机竞态 */
    private CallRoom lockRoom(UUID roomId) {
        return callRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> DomainErrors.notFound("通话不存在"));
    }

    /** 校验用户是房间参与者，否则抛 403。信令与状态变更前都必须先过这一关 */
    private CallParticipant requireParticipant(CallRoom room, UUID userId) {
        String target = userId.toString();
        return room.participants().stream()
                .filter(participant -> participant.userId().equals(target))
                .findFirst()
                .orElseThrow(() -> DomainErrors.forbidden("FORBIDDEN", "你不在该通话中"));
    }
}
