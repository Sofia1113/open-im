package com.openim.server.web;

import com.openim.server.auth.AuthPrincipal;
import com.openim.server.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话与设备管理
 *
 * 位于 {@code /api/v1/sessions} 而非 {@code /api/v1/auth} 之下，
 * 因为这些操作需要已认证的身份，会经过认证拦截器。
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {
    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
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
     * 用户怀疑账号被盗时的自助处置手段；改密流程后续也应调用它。
     */
    @DeleteMapping
    public ApiEnvelope.Success<Map<String, Object>> logoutAll(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        int revoked = sessionService.logoutAllDevices(auth.userUuid(), "user_requested");
        return ApiEnvelope.success(Map.of("revoked", true, "revokedCount", revoked), request);
    }
}
