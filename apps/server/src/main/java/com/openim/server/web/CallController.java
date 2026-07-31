package com.openim.server.web;

import com.openim.server.auth.AuthPrincipal;
import com.openim.server.domain.DomainModels.CallParticipant;
import com.openim.server.domain.DomainModels.CallRoom;
import com.openim.server.domain.Requests.CreateCall;
import com.openim.server.domain.service.CallService;
import com.openim.server.domain.service.DomainErrors;
import com.openim.server.realtime.RealtimeBroadcastService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 通话控制器
 * 处理通话的创建、接听、拒绝和离开操作，
 * 通过 WebSocket 实时推送参与者状态变更（含跨实例广播）。
 */
@Tag(name = "音视频通话", description = "通话房间与 WebRTC 信令的 HTTP 部分")
@RestController
@RequestMapping("/api/v1/calls")
public class CallController {
    private final CallService callService;
    private final RealtimeBroadcastService broadcast;

    public CallController(CallService callService, RealtimeBroadcastService broadcast) {
        this.callService = callService;
        this.broadcast = broadcast;
    }

    /** 创建通话房间。需 Bearer 令牌；被邀请的参与者会收到 call:invite 实时推送 */
    @PostMapping
    public ApiEnvelope.Success<CallRoom> create(
            @Valid @RequestBody CreateCall input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        CallRoom room = callService.createCall(auth.userUuid(), auth.deviceId(), input);
        room.participants().stream()
                .filter(p -> !p.userId().equals(auth.userId()))
                .forEach(p -> broadcast.broadcastToUser(p.userId(), "call:invite", Map.of("room", room)));
        return ApiEnvelope.success(room, request);
    }

    /** 查询单个通话房间。需 Bearer 令牌，仅房间参与者可见 */
    @GetMapping("/{id}")
    public ApiEnvelope.Success<CallRoom> get(@PathVariable String id, HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(
                callService.getCall(DomainErrors.parseId(id, "通话"), auth.userUuid()), request);
    }

    /** 接听通话。需 Bearer 令牌；房间内广播参与者加入，并通知自己其他设备已在别处接听 */
    @PostMapping("/{id}/accept")
    public ApiEnvelope.Success<CallRoom> accept(@PathVariable String id, HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        CallRoom room = callService.acceptCall(
                DomainErrors.parseId(id, "通话"), auth.userUuid(), auth.deviceId());
        CallParticipant joined = new CallParticipant(auth.userId(), "joined", auth.deviceId());
        emitToParticipants(room, "call:participant:joined",
                Map.of("roomId", room.id(), "participant", joined));
        broadcast.broadcastToUser(auth.userId(), "call:answered-elsewhere",
                Map.of("roomId", room.id(), "acceptedDeviceId", auth.deviceId()));
        return ApiEnvelope.success(room, request);
    }

    /** 拒绝通话。需 Bearer 令牌；若因此通话结束，向房间广播 call:ended */
    @PostMapping("/{id}/reject")
    public ApiEnvelope.Success<CallRoom> reject(@PathVariable String id, HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        CallRoom room = callService.rejectCall(DomainErrors.parseId(id, "通话"), auth.userUuid());
        if ("ended".equals(room.status())) {
            emitToParticipants(room, "call:ended", Map.of(
                    "roomId", room.id(),
                    "reason", room.endReason() == null ? "rejected" : room.endReason()));
        }
        return ApiEnvelope.success(room, request);
    }

    /** 离开通话。需 Bearer 令牌；房间内广播参与者离开，通话结束时广播 call:ended */
    @PostMapping("/{id}/leave")
    public ApiEnvelope.Success<CallRoom> leave(@PathVariable String id, HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        CallRoom room = callService.leaveCall(DomainErrors.parseId(id, "通话"), auth.userUuid());
        emitToParticipants(room, "call:participant:left",
                Map.of("roomId", room.id(), "userId", auth.userId(), "reason", "left"));
        if ("ended".equals(room.status())) {
            emitToParticipants(room, "call:ended", Map.of(
                    "roomId", room.id(),
                    "reason", room.endReason() == null ? "ended" : room.endReason()));
        }
        return ApiEnvelope.success(room, request);
    }

    /** 向房间内全体成员（按用户去重后）广播实时事件 */
    private void emitToParticipants(CallRoom room, String event, Object payload) {
        room.participants().stream()
                .map(CallParticipant::userId)
                .distinct()
                .forEach(uid -> broadcast.broadcastToUser(uid, event, payload));
    }
}
