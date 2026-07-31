package com.openim.server.web;

import com.openim.server.auth.AuthenticationService;
import com.openim.server.auth.PasswordResetService;
import com.openim.server.auth.SessionService;
import com.openim.server.config.ProductionProfile;
import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.Requests.CredentialLogin;
import com.openim.server.domain.Requests.DemoLogin;
import com.openim.server.domain.Requests.ForgotPassword;
import com.openim.server.domain.Requests.Refresh;
import com.openim.server.domain.Requests.Register;
import com.openim.server.domain.Requests.ResetPassword;
import com.openim.server.domain.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 *
 * 只包含无需已有会话即可调用的端点。登出、设备管理与改密需要认证，
 * 因此放在 {@link SessionController}（走认证拦截器）而不是这里。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "认证", description = "无需已有会话即可调用的端点")
// 本控制器下全部端点都不需要 Bearer，覆盖 OpenApiConfig 里的全局要求
@SecurityRequirements
public class AuthController {
    private final UserService userService;
    private final SessionService sessionService;
    private final AuthenticationService authenticationService;
    private final PasswordResetService passwordResetService;
    private final ClientIpHasher clientIpHasher;
    private final boolean demoLoginEnabled;
    private final boolean registrationEnabled;

    public AuthController(
            UserService userService,
            SessionService sessionService,
            AuthenticationService authenticationService,
            PasswordResetService passwordResetService,
            ClientIpHasher clientIpHasher,
            @Value("${openim.demo-login-enabled:true}") boolean demoLoginEnabled,
            @Value("${openim.auth.registration-enabled:true}") boolean registrationEnabled,
            ProductionProfile productionProfile
    ) {
        this.userService = userService;
        this.sessionService = sessionService;
        this.authenticationService = authenticationService;
        this.passwordResetService = passwordResetService;
        this.clientIpHasher = clientIpHasher;
        this.demoLoginEnabled = demoLoginEnabled && !productionProfile.active();
        this.registrationEnabled = registrationEnabled;
    }

    /**
     * 凭证登录。
     *
     * 这是生产环境唯一的登录入口。失败一律返回 401 INVALID_CREDENTIALS，
     * 不区分账号不存在与密码错误；连续失败达阈值后返回 403 ACCOUNT_LOCKED。
     */
    @PostMapping("/login")
    @Operation(summary = "凭证登录",
            description = "生产环境唯一的登录入口。identifier 可以是用户名或邮箱（邮箱大小写不敏感）。")
    @ApiResponse(responseCode = "200", description = "登录成功，返回访问令牌与刷新令牌")
    @ApiResponse(responseCode = "401", description =
            "INVALID_CREDENTIALS。刻意不区分账号不存在与密码错误——区分会让本端点成为账号枚举工具",
            content = @io.swagger.v3.oas.annotations.media.Content)
    @ApiResponse(responseCode = "403", description =
            "ACCOUNT_LOCKED。连续失败达阈值后锁定，可通过密码重置解除",
            content = @io.swagger.v3.oas.annotations.media.Content)
    @ApiResponse(responseCode = "429", description = "超出配额，见 Retry-After 头",
            content = @io.swagger.v3.oas.annotations.media.Content)
    public ApiEnvelope.Success<SessionService.Session> login(
            @Valid @RequestBody CredentialLogin input,
            HttpServletRequest request
    ) {
        return ApiEnvelope.success(
                authenticationService.login(
                        input.identifier(), input.password(),
                        input.deviceId(), input.deviceName()),
                request
        );
    }

    /**
     * 注册并直接登录。
     *
     * 可通过 {@code openim.auth.registration-enabled=false} 关闭，
     * 用于邀请制部署或注册滥用应急处置。
     */
    @PostMapping("/register")
    public ApiEnvelope.Success<SessionService.Session> register(
            @Valid @RequestBody Register input,
            HttpServletRequest request
    ) {
        if (!registrationEnabled) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "REGISTRATION_DISABLED",
                    "当前未开放注册"
            );
        }
        return ApiEnvelope.success(
                authenticationService.register(
                        input.username(), input.email(), input.displayName(),
                        input.password(), input.deviceId(), input.deviceName()),
                request
        );
    }

    /**
     * 演示登录：不校验任何凭证，仅按用户名签发会话。
     *
     * 只在非 prod profile 下可用。它的存在是为了让本地评审和演示不必先注册，
     * 但任何环境下把它当作登录方式都是一个完整的认证绕过。
     */
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

    /**
     * 申请密码重置。
     *
     * <b>无论邮箱是否注册都返回 202</b>，且响应体不含任何区分信息。
     * 返回"该邮箱未注册"会把这个接口变成批量验证邮箱是否注册的工具。
     *
     * 202 而非 200：请求已受理，但令牌的送达是异步的（取决于邮件服务），
     * 服务端无法在响应时确认用户已收到。
     */
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "申请密码重置",
            description = """
                    无论邮箱是否注册都返回 202，响应体不含任何区分信息——
                    返回"该邮箱未注册"会把本端点变成批量验证邮箱是否注册的工具。

                    202 而非 200：令牌的送达是异步的（取决于邮件服务），
                    服务端无法在响应时确认用户已收到。
                    """)
    @ApiResponse(responseCode = "202", description = "请求已受理，不表示邮箱存在")
    @ApiResponse(responseCode = "501", description =
            "PASSWORD_RESET_DISABLED，本部署未启用该功能",
            content = @io.swagger.v3.oas.annotations.media.Content)
    public ApiEnvelope.Success<AcceptedResponse> forgotPassword(
            @Valid @RequestBody ForgotPassword input,
            HttpServletRequest request
    ) {
        passwordResetService.requestReset(input.email(), clientIpHasher.hashOf(request));
        return ApiEnvelope.success(
                new AcceptedResponse("若该邮箱已注册，重置链接已发送"), request);
    }

    /**
     * 用令牌完成重置。
     *
     * 成功后该用户的<b>全部</b>设备会话被撤销，需要用新密码重新登录。
     * 这是重置的核心语义：走到这一步通常意味着账号可能已被入侵。
     *
     * 令牌无效、过期、已使用一律返回同一个 INVALID_RESET_TOKEN，
     * 不区分具体原因。
     */
    @PostMapping("/password/reset")
    @Operation(summary = "用令牌完成密码重置",
            description = """
                    成功后该用户的**全部**设备会话被撤销，需要用新密码重新登录。
                    走到这一步通常意味着账号可能已被入侵。

                    同时解除账号锁定——否则"账号已锁定，请重置密码"的提示就是一句空话。
                    """)
    @ApiResponse(responseCode = "400", description =
            "INVALID_RESET_TOKEN。令牌不存在、已过期、已使用一律返回此码，不区分具体原因",
            content = @io.swagger.v3.oas.annotations.media.Content)
    public ApiEnvelope.Success<AcceptedResponse> resetPassword(
            @Valid @RequestBody ResetPassword input,
            HttpServletRequest request
    ) {
        passwordResetService.confirmReset(input.token(), input.newPassword());
        return ApiEnvelope.success(
                new AcceptedResponse("密码已重置，请用新密码登录"), request);
    }

    /**
     * 无数据可返回时的响应体。
     *
     * 刻意不返回布尔值或状态码之外的任何字段——密码重置的两个端点都必须
     * 对"邮箱是否存在""令牌为何无效"保持沉默。
     */
    public record AcceptedResponse(String message) {
    }
}
