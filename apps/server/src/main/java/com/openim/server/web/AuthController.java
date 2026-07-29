package com.openim.server.web;

import com.openim.server.auth.SessionService;
import com.openim.server.config.ProductionProfile;
import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.Requests.DemoLogin;
import com.openim.server.domain.Requests.Refresh;
import com.openim.server.domain.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 *
 * 只包含无需已有会话即可调用的端点。登出与设备管理需要认证，
 * 因此放在 {@link SessionController}（走认证拦截器）而不是这里。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserService userService;
    private final SessionService sessionService;
    private final boolean demoLoginEnabled;

    public AuthController(
            UserService userService,
            SessionService sessionService,
            @Value("${openim.demo-login-enabled:true}") boolean demoLoginEnabled,
            ProductionProfile productionProfile
    ) {
        this.userService = userService;
        this.sessionService = sessionService;
        this.demoLoginEnabled = demoLoginEnabled && !productionProfile.active();
    }

    @PostMapping("/demo-login")
    public ApiEnvelope.Success<SessionService.Session> demoLogin(
            @Valid @RequestBody DemoLogin input,
            HttpServletRequest request
    ) {
        if (!demoLoginEnabled) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "NOT_FOUND",
                    "演示登录未启用"
            );
        }
        User user = userService.getUserByUsername(input.username());
        return ApiEnvelope.success(
                sessionService.login(user, input.deviceId(), input.deviceName()),
                request
        );
    }

    /**
     * 刷新会话。
     *
     * 每次调用都会轮换刷新令牌，客户端必须保存返回的新令牌；
     * 继续使用旧令牌会被判定为重放并撤销该设备的全部会话。
     */
    @PostMapping("/refresh")
    public ApiEnvelope.Success<SessionService.Session> refresh(
            @Valid @RequestBody Refresh input,
            HttpServletRequest request
    ) {
        return ApiEnvelope.success(sessionService.refresh(input.refreshToken()), request);
    }
}
