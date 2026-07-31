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
import com.openim.server.realtime.RealtimeEvent;
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
 * 红包控制器
 * 处理红包的创建、查询和领取操作，
 * 操作完成后通过 WebSocket 推送实时更新（含跨实例广播）。
 */
@Tag(name = "红包与钱包", description = "虚拟红包发放、领取与余额查询。金额单位为分，不接真实支付")
@RestController
@RequestMapping("/api/v1/red-packets")
public class RedPacketController {
    private final RedPacketService redPacketService;
    private final RealtimeBroadcastService broadcast;

    public RedPacketController(RedPacketService redPacketService, RealtimeBroadcastService broadcast) {
        this.redPacketService = redPacketService;
        this.broadcast = broadcast;
    }

    /** 发红包。需 Bearer 令牌；红包消息推送到会话，并通知自己钱包已更新 */
    @PostMapping
    public ApiEnvelope.Success<CreatedRedPacket> create(
            @Valid @RequestBody CreateRedPacket input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        CreatedRedPacket result = redPacketService.createRedPacket(auth.userUuid(), input);
        // 红包消息本身是一条会话消息，事件与普通消息同构
        broadcast.broadcastToConversation(
                result.redPacket().conversationId(),
                RealtimeEvent.messageCreated(result.message())
        );
        broadcast.broadcastToUser(auth.userId(), "wallet:updated", Map.of("wallet", result.wallet()));
        return ApiEnvelope.success(result, request);
    }

    /** 查询红包详情。需 Bearer 令牌，仅会话成员可见 */
    @GetMapping("/{id}")
    public ApiEnvelope.Success<RedPacket> get(@PathVariable String id, HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(
                redPacketService.getRedPacket(DomainErrors.parseId(id, "红包"), auth.userUuid()),
                request
        );
    }

    /** 领取红包。需 Bearer 令牌；idempotencyKey 保证同一红包的重复领取请求幂等 */
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
                RealtimeEvent.redPacketUpdated(result.redPacket())
        );
        broadcast.broadcastToUser(auth.userId(), "wallet:updated", Map.of("wallet", result.wallet()));
        return ApiEnvelope.success(result, request);
    }
}
