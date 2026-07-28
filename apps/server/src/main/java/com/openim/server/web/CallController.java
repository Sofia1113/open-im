package com.openim.server.web;

import com.openim.server.auth.AuthPrincipal;
import com.openim.server.domain.DomainModels.CallParticipant;
import com.openim.server.domain.DomainModels.CallRoom;
import com.openim.server.domain.InMemoryStore;
import com.openim.server.domain.Requests.CreateCall;
import com.openim.server.realtime.RealtimeWebSocketHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 通话控制器
 * 处理通话的创建、接听、拒绝和离开操作，
 * 通过 WebSocket 实时推送参与者状态变更。
 */
@RestController
@RequestMapping("/api/v1/calls")
public class CallController {
    private final InMemoryStore store;
    private final RealtimeWebSocketHandler realtime;

    public CallController(InMemoryStore store, RealtimeWebSocketHandler realtime) {
        this.store = store;
        this.realtime = realtime;
    }

    @PostMapping
    public ApiEnvelope.Success<CallRoom> create(
            @Valid @RequestBody CreateCall input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        CallRoom room = store.createCall(auth.userId(), auth.deviceId(), input);
        room.participants().stream()
                .filter(participant -> !participant.userId().equals(auth.userId()))
                .forEach(participant -> realtime.emitToUser(
                        participant.userId(),
                        "call:invite",
                        Map.of("room", room)
                ));
        return ApiEnvelope.success(room, request);
    }

    @GetMapping("/{id}")
    public ApiEnvelope.Success<CallRoom> get(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(store.getCall(id, auth.userId()), request);
    }

    @PostMapping("/{id}/accept")
    public ApiEnvelope.Success<CallRoom> accept(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        CallRoom room = store.acceptCall(id, auth.userId(), auth.deviceId());
        CallParticipant participant = new CallParticipant(
                auth.userId(),
                "joined",
                auth.deviceId()
        );
        emitToParticipants(room, "call:participant:joined", Map.of(
                "roomId", room.id(),
                "participant", participant
        ));
        realtime.emitToUser(auth.userId(), "call:answered-elsewhere", Map.of(
                "roomId", room.id(),
                "acceptedDeviceId", auth.deviceId()
        ));
        return ApiEnvelope.success(room, request);
    }

    @PostMapping("/{id}/reject")
    public ApiEnvelope.Success<CallRoom> reject(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        CallRoom room = store.rejectCall(id, auth.userId());
        if ("ended".equals(room.status())) {
            emitToParticipants(room, "call:ended", Map.of(
                    "roomId", room.id(),
                    "reason", room.endReason() == null ? "rejected" : room.endReason()
            ));
        }
        return ApiEnvelope.success(room, request);
    }

    @PostMapping("/{id}/leave")
    public ApiEnvelope.Success<CallRoom> leave(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        CallRoom room = store.leaveCall(id, auth.userId());
        emitToParticipants(room, "call:participant:left", Map.of(
                "roomId", room.id(),
                "userId", auth.userId(),
                "reason", "left"
        ));
        if ("ended".equals(room.status())) {
            emitToParticipants(room, "call:ended", Map.of(
                    "roomId", room.id(),
                    "reason", room.endReason() == null ? "ended" : room.endReason()
            ));
        }
        return ApiEnvelope.success(room, request);
    }

    private void emitToParticipants(CallRoom room, String event, Object payload) {
        room.participants().stream()
                .map(CallParticipant::userId)
                .distinct()
                .forEach(userId -> realtime.emitToUser(userId, event, payload));
    }
}
