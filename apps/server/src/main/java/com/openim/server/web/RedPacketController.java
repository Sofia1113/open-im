package com.openim.server.web;

import com.openim.server.auth.AuthPrincipal;
import com.openim.server.domain.DomainModels.RedPacket;
import com.openim.server.domain.DomainResults.ClaimedRedPacket;
import com.openim.server.domain.DomainResults.CreatedRedPacket;
import com.openim.server.domain.Requests.ClaimRedPacket;
import com.openim.server.domain.Requests.CreateRedPacket;
import com.openim.server.domain.service.DomainErrors;
import com.openim.server.domain.service.RedPacketService;
import com.openim.server.realtime.RealtimeBroadcastService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 红包控制器
 * 处理红包的创建、查询和领取操作，
 * 操作完成后通过 WebSocket 推送实时更新（含跨实例广播）。
 */
@RestController
@RequestMapping("/api/v1/red-packets")
public class RedPacketController {
    private final RedPacketService redPacketService;
    private final RealtimeBroadcastService broadcast;

    public RedPacketController(RedPacketService redPacketService, RealtimeBroadcastService broadcast) {
        this.redPacketService = redPacketService;
        this.broadcast = broadcast;
    }

    @PostMapping
    public ApiEnvelope.Success<CreatedRedPacket> create(
            @Valid @RequestBody CreateRedPacket input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        CreatedRedPacket result = redPacketService.createRedPacket(auth.userUuid(), input);
        broadcast.broadcastToConversation(
                result.redPacket().conversationId(),
                "message:created",
                Map.of("eventId", UUID.randomUUID().toString(), "message", result.message())
        );
        broadcast.broadcastToUser(auth.userId(), "wallet:updated", Map.of("wallet", result.wallet()));
        return ApiEnvelope.success(result, request);
    }

    @GetMapping("/{id}")
    public ApiEnvelope.Success<RedPacket> get(@PathVariable String id, HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(
                redPacketService.getRedPacket(DomainErrors.parseId(id, "红包"), auth.userUuid()),
                request
        );
    }

    @PostMapping("/{id}/claim")
    public ApiEnvelope.Success<ClaimedRedPacket> claim(
            @PathVariable String id,
            @Valid @RequestBody ClaimRedPacket input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        ClaimedRedPacket result = redPacketService.claimRedPacket(
                DomainErrors.parseId(id, "红包"), auth.userUuid(), input.idempotencyKey());
        broadcast.broadcastToConversation(
                result.redPacket().conversationId(),
                "red-packet:updated",
                Map.of("eventId", UUID.randomUUID().toString(), "redPacket", result.redPacket())
        );
        broadcast.broadcastToUser(auth.userId(), "wallet:updated", Map.of("wallet", result.wallet()));
        return ApiEnvelope.success(result, request);
    }
}
