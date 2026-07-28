package com.openim.server.web;

import com.openim.server.auth.AuthPrincipal;
import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.DomainModels.Wallet;
import com.openim.server.domain.InMemoryStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
    private final InMemoryStore store;

    public GeneralController(InMemoryStore store) {
        this.store = store;
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
        return ApiEnvelope.success(store.getUser(auth.userId()), request);
    }

    @GetMapping("/api/v1/users")
    public ApiEnvelope.Success<List<User>> users(HttpServletRequest request) {
        return ApiEnvelope.success(store.listUsers(), request);
    }

    @GetMapping("/api/v1/conversations")
    public ApiEnvelope.Success<List<Conversation>> conversations(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(store.listConversations(auth.userId()), request);
    }

    @GetMapping("/api/v1/conversations/{id}/messages")
    public ApiEnvelope.Success<List<ImMessage>> messages(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") long afterSeq,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(
                store.listMessages(id, auth.userId(), Math.max(0, afterSeq)),
                request
        );
    }

    @GetMapping("/api/v1/wallet")
    public ApiEnvelope.Success<Wallet> wallet(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(store.getWallet(auth.userId()), request);
    }
}
