package com.openim.server.auth;

import com.openim.server.domain.repository.AuditRepository;
import com.openim.server.domain.repository.DeviceRepository;
import com.openim.server.observability.DomainMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 设备撤销
 *
 * 单独成类是为了拿到 {@code REQUIRES_NEW} 事务语义：检测到令牌重放时，
 * 调用方随后会抛出 401，如果撤销与之处于同一事务，回滚会把撤销一并撤掉，
 * 结果是"检测到了攻击但设备依然可用"。撤销必须独立提交。
 *
 * 同理，方法内不得抛出业务异常——它的职责是确保撤销落库。
 */
@Service
public class DeviceRevocationService {
    private final DeviceRepository deviceRepository;
    private final AuditRepository auditRepository;
    private final DomainMetrics metrics;
    private final SessionValidityCache sessionValidityCache;

    public DeviceRevocationService(
            DeviceRepository deviceRepository,
            AuditRepository auditRepository,
            DomainMetrics metrics,
            SessionValidityCache sessionValidityCache
    ) {
        this.deviceRepository = deviceRepository;
        this.auditRepository = auditRepository;
        this.metrics = metrics;
        this.sessionValidityCache = sessionValidityCache;
    }

    /** 检测到令牌重放：撤销设备、广播失效并留痕。必须独立提交，理由见类注释 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeForTokenReuse(String deviceId, UUID userId) {
        deviceRepository.revoke(deviceId, "token_reuse");
        // 失效注册在这个 REQUIRES_NEW 事务上，因此在它独立提交后立即触发，
        // 不会被外层随后抛出的 401 连带回滚——与撤销本身独立提交的理由相同。
        sessionValidityCache.invalidate(deviceId, userId);
        auditRepository.recordUserAction(userId, "auth.token_reuse_detected",
                Map.of("deviceId", deviceId));
        metrics.recordTokenReuseDetected();
    }
}
