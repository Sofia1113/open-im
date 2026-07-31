package com.openim.server.web;

import com.openim.server.auth.AuthPrincipal;
import com.openim.server.auth.AuthenticationService;
import com.openim.server.auth.SessionService;
import com.openim.server.domain.Requests.ChangePassword;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话与设备管理
 *
 * 位于 {@code /api/v1/sessions} 而非 {@code /api/v1/auth} 之下，
 * 因为这些操作需要已认证的身份，会经过认证拦截器。
 */
@Tag(name = "会话与设备", description = "登录状态的建立与撤销，以及改密")
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {
    private final SessionService sessionService;
    private final AuthenticationService authenticationService;

    public SessionController(
            SessionService sessionService,
            AuthenticationService authenticationService
    ) {
        this.sessionService = sessionService;
        this.authenticationService = authenticationService;
    }

    /** 退出当前设备：撤销该设备的刷新令牌，其访问令牌也随之失效 */
    @DeleteMapping("/current")
    public ApiEnvelope.Success<Map<String, Object>> logout(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        sessionService.logout(auth.userUuid(), auth.deviceId());
        return ApiEnvelope.success(Map.of("revoked", true), request);
    }

    /**
     * 退出全部设备。
     *
     * 用户怀疑账号被盗时的自助处置手段。
     */
    @DeleteMapping
    public ApiEnvelope.Success<Map<String, Object>> logoutAll(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        int revoked = sessionService.logoutAllDevices(auth.userUuid(), "user_requested");
        return ApiEnvelope.success(Map.of("revoked", true, "revokedCount", revoked), request);
    }

    /**
     * 修改密码。
     *
     * 需要提供当前密码——仅凭有效访问令牌就能改密的话，令牌泄漏即等于账号丢失。
     *
     * 成功后该用户的<b>全部</b>设备会话被撤销，包括发起本次请求的这一台，
     * 客户端需要引导用户用新密码重新登录。这是有意的：改密的语义就是
     * "此前的会话都不再可信"。
     */
    @PostMapping("/password")
    public ApiEnvelope.Success<Map<String, Object>> changePassword(
            @Valid @RequestBody ChangePassword input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        authenticationService.changePassword(
                auth.userUuid(), input.currentPassword(), input.newPassword());
        return ApiEnvelope.success(
                Map.of("changed", true, "reauthenticationRequired", true), request);
    }
}
