package com.openim.server.auth;

import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.repository.AuditRepository;
import com.openim.server.domain.repository.CredentialRepository;
import com.openim.server.domain.repository.PasswordResetRepository;
import com.openim.server.domain.repository.PasswordResetRepository.ResetToken;
import com.openim.server.domain.repository.UserRepository;
import com.openim.server.domain.repository.UserRepository.ResetTarget;
import com.openim.server.domain.service.DomainErrors;
import com.openim.server.domain.service.UserService;
import com.openim.server.observability.AuthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 密码重置（找回密码）
 *
 * <b>此前的缺口</b>：唯一的改密入口要求提供当前密码，而"忘记密码"的定义正是
 * 不知道当前密码。用户忘记密码就永久失去账号，且账号锁定的提示写着
 * "请稍后再试或重置密码"——那条路并不存在。
 *
 * <b>四条安全约束：</b>
 * <ol>
 *   <li><b>账号枚举防护</b>：无论邮箱是否注册，请求重置都返回同样的成功响应。
 *       这是本流程最容易泄漏信息的地方——"该邮箱未注册"的提示会把接口
 *       变成一个批量验证邮箱是否注册的工具。</li>
 *   <li><b>令牌单次使用</b>：由 {@code UPDATE ... WHERE used_at IS NULL} 的
 *       行数判定，并发提交同一令牌只有一个能成功。</li>
 *   <li><b>重置后撤销全部会话</b>：与改密一致。走到重置流程通常意味着
 *       账号可能已被入侵，必须把入侵者的会话一并踢掉。</li>
 *   <li><b>令牌只存哈希</b>：明文只在送达用户的那一次存在。</li>
 * </ol>
 *
 * <b>为什么请求重置不需要限流之外的防护</b>：它的代价是发一封邮件，
 * 而限流已经按 IP 与邮箱两个维度约束了频率。真正需要保护的是<b>确认</b>环节，
 * 那里由令牌的长度（256 位随机）与短有效期承担——暴力猜测一个 256 位令牌
 * 不是一个需要额外防护的攻击路径。
 */
@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /**
     * 令牌字节长度。32 字节 = 256 位，Base64URL 编码后 43 个字符。
     * 足够长到不可猜测，又短到能放进一行 URL。
     */
    private static final int TOKEN_BYTES = 32;

    /** 令牌必须来自 CSPRNG。用 Random 的话令牌可以被预测 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordResetRepository resetRepository;
    private final CredentialRepository credentialRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final SessionService sessionService;
    private final PasswordResetNotifier notifier;
    private final AuditRepository auditRepository;
    private final AuthMetrics metrics;
    private final PasswordEncoder passwordEncoder;

    private final boolean enabled;
    private final Duration tokenTtl;

    public PasswordResetService(
            PasswordResetRepository resetRepository,
            CredentialRepository credentialRepository,
            UserRepository userRepository,
            UserService userService,
            SessionService sessionService,
            PasswordResetNotifier notifier,
            AuditRepository auditRepository,
            AuthMetrics metrics,
            @Value("${openim.auth.bcrypt-cost:12}") int bcryptCost,
            @Value("${openim.auth.password-reset-enabled:true}") boolean enabled,
            @Value("${openim.auth.password-reset-ttl-seconds:1800}") long tokenTtlSeconds
    ) {
        this.resetRepository = resetRepository;
        this.credentialRepository = credentialRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.sessionService = sessionService;
        this.notifier = notifier;
        this.auditRepository = auditRepository;
        this.metrics = metrics;
        this.passwordEncoder = new BCryptPasswordEncoder(bcryptCost);
        this.enabled = enabled;
        this.tokenTtl = Duration.ofSeconds(tokenTtlSeconds);
    }

    /**
     * 请求重置。
     *
     * <b>无论邮箱是否存在都返回成功</b>，且两条路径的可观测行为尽可能一致。
     * 唯一的差异是存在时会多做一次数据库写入与一次邮件发送——两者都在
     * 毫秒级且方差远大于差值，不构成可用的计时侧信道。
     *
     * 不做"存在时才返回成功"的原因不只是防枚举，还有一个实际考虑：
     * 用户记错了注册邮箱时，"该邮箱未注册"和"已发送，请查收"这两个提示，
     * 后者反而让他去检查另一个邮箱，前者会让他确信自己没注册过。
     */
    @Transactional
    public void requestReset(String email, String requestedIpHash) {
        requireEnabled();

        Optional<ResetTarget> found = userRepository.findActiveByEmail(email);
        if (found.isEmpty()) {
            // 不存在：什么都不做，但记录审计——针对不存在邮箱的大量请求本身就是信号。
            // 按 IP 哈希留痕，使"同一来源试探了多少个邮箱"可以被查出来。
            auditRepository.recordSystemAction("auth.password_reset_requested_unknown",
                    requestedIpHash, Map.of());
            metrics.recordPasswordResetRequested(false);
            log.info("Password reset requested for unregistered email ipHash={}",
                    requestedIpHash);
            return;
        }

        ResetTarget target = found.get();

        // 作废此前未使用的令牌：连续点三次"忘记密码"不应产生三个同时有效的令牌
        int invalidated = resetRepository.invalidateAllForUser(target.userId());

        String token = generateToken();
        Instant expiresAt = Instant.now().plus(tokenTtl);
        resetRepository.insert(
                UUID.randomUUID(), target.userId(), hash(token), expiresAt, requestedIpHash);

        // 送达失败不抛异常：否则邮件服务抖动会让接口返回 500，
        // 而 500 只会发生在邮箱存在的情况下——状态码本身就泄漏了存在性
        notifier.sendResetToken(target.email(), target.displayName(), token, tokenTtl);

        auditRepository.recordUserAction(target.userId(), "auth.password_reset_requested",
                requestedIpHash, Map.of("invalidatedPriorTokens", invalidated));
        metrics.recordPasswordResetRequested(true);
        log.info("Password reset token issued userId={} invalidatedPrior={}",
                target.userId(), invalidated);
    }

    /**
     * 用令牌确认重置。
     *
     * 失败原因（不存在 / 已过期 / 已使用）对外统一为同一个错误——
     * 区分它们会让攻击者能验证一个令牌是否曾经存在过。
     */
    @Transactional
    public void confirmReset(String token, String newPassword) {
        requireEnabled();

        if (token == null || token.isBlank()) {
            throw invalidToken();
        }

        Optional<ResetToken> found = resetRepository.findByHash(hash(token));
        if (found.isEmpty()) {
            metrics.recordPasswordResetFailure("unknown_token");
            throw invalidToken();
        }

        ResetToken resetToken = found.get();
        if (!resetToken.usable()) {
            // 已用或已过期。审计里区分，响应里不区分。
            String reason = resetToken.used() ? "already_used" : "expired";
            auditRepository.recordUserAction(resetToken.userId(),
                    "auth.password_reset_rejected", Map.of("reason", reason));
            metrics.recordPasswordResetFailure(reason);
            throw invalidToken();
        }

        User user = userService.getUser(resetToken.userId());
        PasswordPolicy.validate(newPassword, user.username());

        // 标记已使用必须在改密之前，且靠 UPDATE 的行数判定并发：
        // 两个请求同时提交同一个令牌时，只有一个能标记成功。
        // 顺序反过来的话，两个请求会各自改一次密码——后者覆盖前者，
        // 而用户不知道自己的密码最终是哪一个。
        if (!resetRepository.markUsed(resetToken.id())) {
            metrics.recordPasswordResetFailure("concurrent_use");
            throw invalidToken();
        }

        // updatePassword 同时清零失败计数并解除锁定。
        // 这一点对本流程是必需的：因忘记密码而连续失败被锁定的用户，
        // 重置后必须能立刻登录——否则锁定提示里的"或重置密码"仍然是一句空话。
        credentialRepository.updatePassword(
                resetToken.userId(), passwordEncoder.encode(newPassword));

        int revoked = sessionService.logoutAllDevices(resetToken.userId(), "password_reset");

        auditRepository.recordUserAction(resetToken.userId(), "auth.password_reset_completed",
                Map.of("revokedSessions", revoked));
        metrics.recordPasswordResetCompleted();
        log.info("Password reset completed userId={} revokedSessions={}",
                resetToken.userId(), revoked);
    }

    /**
     * 清理过期与已使用的令牌。
     *
     * 保留 7 天而非立即删除：排查"这个账号是谁重置的"需要这些记录。
     * 但它是一张只增不减的表，必须有清理。
     */
    @Transactional
    public int purgeStaleTokens(Duration retention) {
        return resetRepository.deleteExpiredBefore(Instant.now().minus(retention));
    }

    /** 功能开关检查：未启用时返回 501 而非 404，便于排查"为什么收不到重置邮件" */
    private void requireEnabled() {
        if (!enabled) {
            // 501 而非 404：功能存在但未在本部署中启用，
            // 这个区别对排查"为什么收不到重置邮件"很重要
            throw new com.openim.server.web.ApiException(
                    org.springframework.http.HttpStatus.NOT_IMPLEMENTED,
                    "PASSWORD_RESET_DISABLED",
                    "本部署未启用密码重置功能");
        }
    }

    /** 令牌相关的失败一律返回同一个响应，不区分不存在、过期与已使用 */
    private static com.openim.server.web.ApiException invalidToken() {
        return new com.openim.server.web.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "INVALID_RESET_TOKEN",
                "重置链接无效或已过期，请重新申请");
    }

    /** Base64URL 无填充：可直接放进 URL，不需要转义 */
    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 而非 BCrypt。
     *
     * 令牌是 256 位的高熵随机值，不需要抗暴力破解的慢哈希——BCrypt 在这里
     * 只会让每次验证多花几百毫秒。慢哈希是为低熵的人类密码设计的。
     * 同时 SHA-256 是确定性的，可以直接按哈希建唯一索引查找；
     * BCrypt 每次加盐结果不同，无法用于查找。
     */
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required but unavailable", exception);
        }
    }

}
