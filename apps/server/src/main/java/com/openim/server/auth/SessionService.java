package com.openim.server.auth;

import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.repository.AuditRepository;
import com.openim.server.domain.repository.DeviceRepository;
import com.openim.server.domain.repository.DeviceRepository.DeviceSession;
import com.openim.server.domain.service.DomainErrors;
import com.openim.server.domain.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * 会话生命周期编排
 *
 * 刷新令牌采用"一次性使用 + 轮换"策略：每次刷新都签发新的刷新令牌并作废旧的。
 * 如果一个已被轮换掉的令牌再次出现，只可能是它在传输或存储环节被窃取，
 * 此时立即撤销该设备的全部会话，把损失限制在单台设备上——这是 refresh token
 * rotation with reuse detection 的标准做法。
 */
@Service
public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private static final String ACCESS = "access";
    private static final String REFRESH = "refresh";

    private final TokenService tokenService;
    private final DeviceRepository deviceRepository;
    private final DeviceRevocationService deviceRevocationService;
    private final AuditRepository auditRepository;
    private final UserService userService;
    private final SessionValidityCache sessionValidityCache;
    private final long accessTokenTtlSeconds;
    private final long refreshTokenTtlSeconds;
    private final long refreshGraceSeconds;

    public SessionService(
            TokenService tokenService,
            DeviceRepository deviceRepository,
            DeviceRevocationService deviceRevocationService,
            AuditRepository auditRepository,
            UserService userService,
            SessionValidityCache sessionValidityCache,
            @Value("${openim.jwt.access-token-ttl-seconds:900}") long accessTokenTtlSeconds,
            @Value("${openim.jwt.refresh-token-ttl-seconds:604800}") long refreshTokenTtlSeconds,
            @Value("${openim.jwt.refresh-grace-seconds:60}") long refreshGraceSeconds
    ) {
        this.tokenService = tokenService;
        this.deviceRepository = deviceRepository;
        this.deviceRevocationService = deviceRevocationService;
        this.auditRepository = auditRepository;
        this.userService = userService;
        this.sessionValidityCache = sessionValidityCache;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
        this.refreshGraceSeconds = refreshGraceSeconds;
    }

    /** 登录：签发新会话并登记设备 */
    @Transactional
    public Session login(User user, String deviceId, String deviceName) {
        UUID userId = UUID.fromString(user.id());
        IssuedPair pair = issuePair(user.id(), deviceId);
        deviceRepository.upsert(deviceId, userId, deviceName,
                hash(pair.refreshToken()), pair.refreshExpiresAt());
        // upsert 会把 revoked_at 清空——设备可以"复活"。若某个实例缓存了
        // 这台设备此前的"已撤销"结果，不失效的话新令牌会被拒到缓存过期为止。
        sessionValidityCache.invalidate(deviceId, userId);
        auditRepository.recordUserAction(userId, "auth.login", Map.of("deviceId", deviceId));
        return new Session(pair.accessToken(), pair.refreshToken(), pair.accessExpiresAt(), user);
    }

    /**
     * 刷新会话。
     *
     * 校验顺序刻意如此：先验签，再查设备，最后比对哈希。
     * 任一步失败都返回同样的 AUTH_REQUIRED，不告诉调用方具体是哪一步不通过。
     */
    @Transactional
    public Session refresh(String refreshToken) {
        TokenService.TokenPayload payload = tokenService.verify(refreshToken, REFRESH)
                .orElseThrow(() -> DomainErrors.unauthorized("刷新令牌无效"));

        UUID userId = parseUserId(payload.userId());
        DeviceSession device = deviceRepository.findById(payload.deviceId())
                .orElseThrow(() -> DomainErrors.unauthorized("刷新令牌无效"));

        if (device.revoked() || !device.userId().equals(userId)) {
            throw DomainErrors.unauthorized("会话已失效，请重新登录");
        }

        String presentedHash = hash(refreshToken);
        boolean matchesCurrent = constantTimeEquals(device.refreshTokenHash(), presentedHash);
        boolean matchesPrevious = constantTimeEquals(device.previousRefreshTokenHash(), presentedHash);

        if (!matchesCurrent && !matchesPrevious) {
            // 既不是当前令牌也不是上一个令牌，只可能是被窃取的更早令牌。
            // 撤销走独立事务，否则紧接着抛出的 401 会把它一起回滚掉。
            deviceRevocationService.revokeForTokenReuse(device.deviceId(), userId);
            log.warn("Refresh token reuse detected, device revoked userId={} deviceId={}",
                    userId, device.deviceId());
            throw DomainErrors.unauthorized("会话已失效，请重新登录");
        }

        User user = userService.getUser(userId);
        IssuedPair pair = issuePair(user.id(), device.deviceId());
        String newHash = hash(pair.refreshToken());

        boolean exchanged = matchesCurrent
                // 比较并交换：并发刷新只有一个能命中当前哈希
                ? deviceRepository.rotateRefreshToken(
                        device.deviceId(), presentedHash, newHash, pair.refreshExpiresAt())
                // 上一个令牌：多标签页或网络重试的正常情况，宽限期内允许再次交换
                : deviceRepository.rotateWithinGracePeriod(
                        device.deviceId(), presentedHash, newHash,
                        pair.refreshExpiresAt(), refreshGraceSeconds);

        if (!exchanged) {
            if (matchesPrevious) {
                // 命中上一个令牌但已超出宽限窗口，按重放处理
                deviceRevocationService.revokeForTokenReuse(device.deviceId(), userId);
                log.warn("Refresh token reused outside grace period, device revoked "
                        + "userId={} deviceId={}", userId, device.deviceId());
            }
            throw DomainErrors.unauthorized("会话已失效，请重新登录");
        }

        auditRepository.recordUserAction(userId, "auth.token_refresh",
                Map.of("deviceId", device.deviceId(), "withinGracePeriod", matchesPrevious));
        return new Session(pair.accessToken(), pair.refreshToken(), pair.accessExpiresAt(), user);
    }

    /** 退出当前设备 */
    @Transactional
    public void logout(UUID userId, String deviceId) {
        deviceRepository.revoke(deviceId, "logout");
        sessionValidityCache.invalidate(deviceId, userId);
        auditRepository.recordUserAction(userId, "auth.logout", Map.of("deviceId", deviceId));
    }

    /** 退出全部设备，用于改密或风控处置 */
    @Transactional
    public int logoutAllDevices(UUID userId, String reason) {
        int revoked = deviceRepository.revokeAllForUser(userId, reason);
        // 受影响的 deviceId 集合此处未知，整体清空。改密与风控处置是低频操作，
        // 多一次全局回源换取"绝不漏掉一个设备"是值得的。
        sessionValidityCache.invalidateAll();
        auditRepository.recordUserAction(userId, "auth.revoke_all_devices",
                Map.of("reason", reason, "revokedCount", revoked));
        return revoked;
    }

    /** 为设备签发一对访问令牌与刷新令牌，有效期各自独立配置 */
    private IssuedPair issuePair(String userId, String deviceId) {
        TokenService.IssuedToken access =
                tokenService.issue(userId, deviceId, ACCESS, accessTokenTtlSeconds);
        TokenService.IssuedToken refresh =
                tokenService.issue(userId, deviceId, REFRESH, refreshTokenTtlSeconds);
        return new IssuedPair(
                access.token(), refresh.token(), access.expiresAt(), refresh.expiresAt());
    }

    /** 解析令牌中的用户 ID；非法 UUID 按无效令牌拒绝，不区分错误原因 */
    private UUID parseUserId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            throw DomainErrors.unauthorized("刷新令牌无效");
        }
    }

    /** 刷新令牌只存哈希，数据库泄漏时无法直接换取新会话 */
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required but unavailable", exception);
        }
    }

    /** 定长比较，避免通过响应时间差异推断哈希前缀 */
    private static boolean constantTimeEquals(String stored, String presented) {
        if (stored == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private record IssuedPair(
            String accessToken,
            String refreshToken,
            Instant accessExpiresAt,
            Instant refreshExpiresAt
    ) {
    }

    public record Session(
            String accessToken,
            String refreshToken,
            Instant expiresAt,
            User user
    ) {
    }
}
