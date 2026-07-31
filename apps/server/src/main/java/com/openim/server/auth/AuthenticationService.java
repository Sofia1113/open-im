package com.openim.server.auth;

import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.repository.AuditRepository;
import com.openim.server.domain.repository.CredentialRepository;
import com.openim.server.domain.repository.CredentialRepository.Credential;
import com.openim.server.domain.repository.CredentialRepository.LockState;
import com.openim.server.domain.repository.UserRepository;
import com.openim.server.domain.service.DomainErrors;
import com.openim.server.domain.service.UserService;
import com.openim.server.observability.AuthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 身份验证
 *
 * 这一层只回答"你是不是你声称的那个人"。验证通过后由 {@link SessionService}
 * 签发会话——两者刻意分开：会话管理（JWT 轮换、设备撤销）与身份验证
 * （凭证比对、失败锁定）的变更原因完全不同，未来接入短信或 OAuth 时
 * 只需要在这一层增加验证方式，签发链路不动。
 *
 * <b>三条安全约束贯穿本类：</b>
 * <ol>
 *   <li>失败原因对外统一为 INVALID_CREDENTIALS，不区分"用户不存在"与"密码错误"，
 *       否则登录接口就成了账号枚举接口</li>
 *   <li>用户不存在时仍执行一次哈希比对，消除响应时间差异——
 *       否则计时攻击可以在不看响应内容的情况下枚举账号</li>
 *   <li>失败计数与锁定落在数据库，多实例共享，不依赖可降级的 Redis</li>
 * </ol>
 */
@Service
public class AuthenticationService {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    /**
     * 用于"用户不存在"分支的哑哈希。
     *
     * 必须是一个真实的、cost 与线上一致的 BCrypt 哈希，这样比对开销才与
     * 真实路径相同。明文是随机 UUID，任何输入都不可能匹配。
     */
    private final String dummyHash;

    private final PasswordEncoder passwordEncoder;
    private final CredentialRepository credentialRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final SessionService sessionService;
    private final LoginAttemptService loginAttemptService;
    private final AuditRepository auditRepository;
    private final AuthMetrics metrics;

    public AuthenticationService(
            CredentialRepository credentialRepository,
            UserRepository userRepository,
            UserService userService,
            SessionService sessionService,
            LoginAttemptService loginAttemptService,
            AuditRepository auditRepository,
            AuthMetrics metrics,
            @Value("${openim.auth.bcrypt-cost:12}") int bcryptCost
    ) {
        this.credentialRepository = credentialRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.sessionService = sessionService;
        this.loginAttemptService = loginAttemptService;
        this.auditRepository = auditRepository;
        this.metrics = metrics;
        this.passwordEncoder = new BCryptPasswordEncoder(bcryptCost);
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * 凭证登录。
     *
     * 顺序：查凭证 → 判锁定 → 比对哈希 → 记录结果 → 签发会话。
     * 锁定检查放在哈希比对之前，避免被锁定的账号仍然消耗 CPU 做 BCrypt 计算
     * （cost 12 大约 200-400ms，这本身就是一个放大攻击面）。
     */
    @Transactional
    public SessionService.Session login(
            String identifier,
            String password,
            String deviceId,
            String deviceName
    ) {
        Optional<Credential> found = credentialRepository.findByLoginIdentifier(identifier);

        if (found.isEmpty()) {
            // 消除计时差异：不存在的账号也走一次同等开销的比对
            passwordEncoder.matches(password, dummyHash);
            metrics.recordLoginFailure("unknown_identifier");
            throw invalidCredentials();
        }

        Credential credential = found.get();

        if (credential.locked()) {
            metrics.recordLoginFailure("locked");
            loginAttemptService.recordBlockedAttempt(credential.userId(), deviceId);
            // 明确告知锁定：攻击者通过连续失败自己就能推断出锁定状态，
            // 隐瞒只会让真实用户困惑于"密码明明是对的"
            throw DomainErrors.forbidden("ACCOUNT_LOCKED",
                    "账号已临时锁定，请稍后再试或重置密码");
        }

        if (!passwordEncoder.matches(password, credential.passwordHash())) {
            metrics.recordLoginFailure("bad_password");
            // 计数走独立事务：本方法随后抛出的异常会回滚当前事务，
            // 若计数在同一事务内则会被一并撤销，锁定永远不会触发
            LockState state = loginAttemptService.recordFailure(
                    credential.userId(), "auth.login_failed", Map.of("deviceId", deviceId));
            if (state.locked()) {
                throw DomainErrors.forbidden("ACCOUNT_LOCKED",
                        "连续登录失败次数过多，账号已临时锁定");
            }
            throw invalidCredentials();
        }

        loginAttemptService.recordSuccess(credential.userId());
        metrics.recordLoginSuccess();

        User user = userService.getUser(credential.userId());
        return sessionService.login(user, deviceId, deviceName);
    }

    /**
     * 注册。
     *
     * 用户名/邮箱冲突由数据库唯一约束判定而不是先查后写——并发注册同一个
     * 用户名时，预检查会让两个请求都通过，约束才是正确性来源。
     */
    @Transactional
    public SessionService.Session register(
            String username,
            String email,
            String displayName,
            String password,
            String deviceId,
            String deviceName
    ) {
        PasswordPolicy.validate(password, username);

        UUID userId = UUID.randomUUID();
        try {
            userRepository.insertWithCredentials(
                    userId, username, email, displayName, avatarFor(displayName));
            credentialRepository.insert(userId, passwordEncoder.encode(password));
        } catch (DuplicateKeyException duplicate) {
            // 不区分用户名与邮箱冲突：两者都会让注册接口变成"该账号是否已注册"的探测器。
            // 这是可用性与账号枚举之间的取舍，此处偏向后者。
            metrics.recordRegistrationConflict();
            throw DomainErrors.conflict("IDENTIFIER_TAKEN", "该用户名或邮箱已被使用");
        }

        auditRepository.recordUserAction(userId, "auth.register",
                Map.of("deviceId", deviceId, "hasEmail", email != null));
        metrics.recordRegistration();

        User user = userService.getUser(userId);
        return sessionService.login(user, deviceId, deviceName);
    }

    /**
     * 改密。
     *
     * 必须验证当前密码：仅凭有效的访问令牌就允许改密，等于令牌泄漏后
     * 攻击者可以直接接管账号。
     *
     * 改密成功后撤销该用户的全部设备会话（含发起改密的这一台）。
     * 这是改密的核心语义——如果账号确实已被入侵，改密必须把入侵者踢出去。
     * 客户端会因此需要重新登录，这是有意的。
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        Credential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> DomainErrors.notFound("凭证不存在"));

        if (!passwordEncoder.matches(currentPassword, credential.passwordHash())) {
            metrics.recordLoginFailure("change_password_bad_current");
            // 改密路径同样计入失败次数：否则它就成了一个不受锁定保护的密码爆破入口。
            // 同样走独立事务——理由见 login()
            loginAttemptService.recordFailure(
                    userId, "auth.change_password_failed", Map.of());
            throw invalidCredentials();
        }

        User user = userService.getUser(userId);
        PasswordPolicy.validate(newPassword, user.username());

        if (passwordEncoder.matches(newPassword, credential.passwordHash())) {
            throw DomainErrors.validation("新密码不能与当前密码相同");
        }

        credentialRepository.updatePassword(userId, passwordEncoder.encode(newPassword));
        int revoked = sessionService.logoutAllDevices(userId, "password_changed");

        auditRepository.recordUserAction(userId, "auth.change_password",
                Map.of("revokedSessions", revoked));
        metrics.recordPasswordChange();
        log.info("Password changed and {} sessions revoked userId={}", revoked, userId);
    }

    /**
     * 统一的凭证失败响应。
     *
     * 401 而非 403：客户端据此触发跳转登录（见 web/src/api.ts 的错误处理）。
     * 错误码与消息对"用户不存在"和"密码错误"完全一致。
     */
    private static com.openim.server.web.ApiException invalidCredentials() {
        return new com.openim.server.web.ApiException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "用户名或密码不正确"
        );
    }

    /** 取展示名首字符作为头像占位，与演示数据的约定一致 */
    private static String avatarFor(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "?";
        }
        return displayName.substring(0, 1);
    }
}
