package com.openim.server.web;

import com.openim.server.auth.AuthPrincipal;
import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.DomainModels.Wallet;
import com.openim.server.domain.service.ConversationService;
import com.openim.server.domain.service.DomainErrors;
import com.openim.server.domain.service.MessageService;
import com.openim.server.domain.service.RedPacketService;
import com.openim.server.domain.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 通用数据查询控制器
 * 提供健康检查、用户列表、会话列表、消息历史和钱包余额等只读接口。
 */
@RestController
public class GeneralController {
    private final UserService userService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final RedPacketService redPacketService;

    public GeneralController(
            UserService userService,
            ConversationService conversationService,
            MessageService messageService,
            RedPacketService redPacketService
    ) {
        this.userService = userService;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.redPacketService = redPacketService;
    }

    @GetMapping("/health")
    public ApiEnvelope.Success<Map<String, Object>> health(HttpServletRequest request) {
        return ApiEnvelope.success(Map.of(
                "status", "ok",
                "version", "0.1.0-java",
                "now", Instant.now()
        ), request);
    }

    @GetMapping("/api/v1/me")
    public ApiEnvelope.Success<User> me(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(userService.getUser(auth.userUuid()), request);
    }

    @GetMapping("/api/v1/users")
    public ApiEnvelope.Success<List<User>> users(HttpServletRequest request) {
        return ApiEnvelope.success(userService.listUsers(), request);
    }

    @GetMapping("/api/v1/conversations")
    public ApiEnvelope.Success<List<Conversation>> conversations(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(conversationService.listConversations(auth.userUuid()), request);
    }

    @GetMapping("/api/v1/conversations/{id}/messages")
    public ApiEnvelope.Success<List<ImMessage>> messages(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") long afterSeq,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(
                messageService.listMessages(
                        DomainErrors.parseId(id, "会话"),
                        auth.userUuid(),
                        Math.max(0, afterSeq)),
                request
        );
    }

    @GetMapping("/api/v1/wallet")
    public ApiEnvelope.Success<Wallet> wallet(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(redPacketService.getWallet(auth.userUuid()), request);
    }
}
