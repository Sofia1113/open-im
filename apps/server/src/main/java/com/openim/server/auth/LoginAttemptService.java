package com.openim.server.auth;

import com.openim.server.domain.repository.AuditRepository;
import com.openim.server.domain.repository.CredentialRepository;
import com.openim.server.domain.repository.CredentialRepository.LockState;
import com.openim.server.observability.AuthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 登录失败计数
 *
 * 单独成类是为了拿到 {@code REQUIRES_NEW} 事务语义。这不是风格选择，而是
 * 正确性要求：调用方在记录失败后必须抛出 401/403，如果计数与之处于同一事务，
 * 回滚会把计数一并撤销——结果是失败次数永远停留在 0，账号锁定永远不触发。
 *
 * 这个缺陷在集成测试里表现为"连续失败 3 次后仍返回 INVALID_CREDENTIALS
 * 而不是 ACCOUNT_LOCKED"。同类问题已经在
 * {@link DeviceRevocationService} 处理过一次（撤销必须独立提交）。
 *
 * 与之相应，本类的方法不得抛出业务异常——它的职责是确保计数与审计落库。
 */
@Service
public class LoginAttemptService {
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final CredentialRepository credentialRepository;
    private final AuditRepository auditRepository;
    private final AuthMetrics metrics;
    private final int maxFailedAttempts;
    private final long lockSeconds;

    public LoginAttemptService(
            CredentialRepository credentialRepository,
            AuditRepository auditRepository,
            AuthMetrics metrics,
            @Value("${openim.auth.max-failed-attempts:5}") int maxFailedAttempts,
            @Value("${openim.auth.lock-duration-seconds:900}") long lockSeconds
    ) {
        this.credentialRepository = credentialRepository;
        this.auditRepository = auditRepository;
        this.metrics = metrics;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockSeconds = lockSeconds;
    }

    /**
     * 记录一次密码校验失败，必要时锁定账号。
     *
     * @param action 审计动作名，区分登录失败与改密时的当前密码校验失败
     * @return 本次失败之后的锁定状态
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LockState recordFailure(UUID userId, String action, Map<String, Object> metadata) {
        LockState state = credentialRepository.recordFailure(userId, maxFailedAttempts, lockSeconds);

        Map<String, Object> enriched = new java.util.LinkedHashMap<>(metadata);
        enriched.put("failedAttempts", state.failedAttempts());
        auditRepository.recordUserAction(userId, action, enriched);

        if (state.locked()) {
            log.warn("Account locked after {} failed attempts userId={}",
                    state.failedAttempts(), userId);
            metrics.recordAccountLocked();
        }
        return state;
    }

    /**
     * 记录一次被锁定拦截的尝试。
     *
     * 与 {@link #recordFailure} 分开：被锁定期间的尝试不应继续累加计数，
     * 否则攻击者可以通过持续尝试无限延长锁定时间，把账号锁定变成一种
     * 针对特定用户的拒绝服务手段。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBlockedAttempt(UUID userId, String deviceId) {
        auditRepository.recordUserAction(userId, "auth.login_blocked_locked",
                Map.of("deviceId", deviceId));
    }

    /** 登录成功：清零计数并记录审计 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(UUID userId) {
        credentialRepository.recordSuccess(userId);
    }
}
