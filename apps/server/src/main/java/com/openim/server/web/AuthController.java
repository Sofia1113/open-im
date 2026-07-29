package com.openim.server.web;

import com.openim.server.auth.TokenService;
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

import java.time.Instant;

/**
 * 认证控制器
 * 提供演示登录和令牌刷新接口。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserService userService;
    private final TokenService tokenService;
    private final boolean demoLoginEnabled;

    public AuthController(
            UserService userService,
            TokenService tokenService,
            @Value("${openim.demo-login-enabled:true}") boolean demoLoginEnabled,
            ProductionProfile productionProfile
    ) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.demoLoginEnabled = demoLoginEnabled && !productionProfile.active();
    }

    @PostMapping("/demo-login")
    public ApiEnvelope.Success<SessionResponse> demoLogin(
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
        return ApiEnvelope.success(issueSession(user, input.deviceId()), request);
    }

    @PostMapping("/refresh")
    public ApiEnvelope.Success<SessionResponse> refresh(
            @Valid @RequestBody Refresh input,
            HttpServletRequest request
    ) {
        TokenService.TokenPayload payload = tokenService
                .verify(input.refreshToken(), "refresh")
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        "AUTH_REQUIRED",
                        "刷新令牌无效"
                ));
        User user = userService.getUser(payload.userId());
        return ApiEnvelope.success(issueSession(user, payload.deviceId()), request);
    }

    private SessionResponse issueSession(User user, String deviceId) {
        TokenService.IssuedToken access = tokenService.issue(
                user.id(),
                deviceId,
                "access",
                60 * 60
        );
        TokenService.IssuedToken refresh = tokenService.issue(
                user.id(),
                deviceId,
                "refresh",
                7 * 24 * 60 * 60
        );
        return new SessionResponse(
                access.token(),
                refresh.token(),
                access.expiresAt(),
                user
        );
    }

    public record SessionResponse(
            String accessToken,
            String refreshToken,
            Instant expiresAt,
            User user
    ) {
    }
}
