package com.openim.server.auth;

import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.repository.AuditRepository;
import com.openim.server.domain.repository.DeviceRepository;
import com.openim.server.domain.service.UserService;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import com.openim.server.support.DemoUsers;
import com.openim.server.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话生命周期集成测试
 *
 * 覆盖刷新令牌轮换、宽限期与重放检测三者的交界：
 * 宽限期内的重复提交是正常客户端行为，宽限期外的才是攻击信号。
 */
@SpringBootTest
class SessionServiceIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired
    private SessionService sessionService;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void refreshRotatesTheTokenAndIssuesANewPair() {
        String deviceId = newDeviceId();
        SessionService.Session initial = login(deviceId);

        SessionService.Session refreshed = sessionService.refresh(initial.refreshToken());

        assertThat(refreshed.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(refreshed.accessToken()).isNotEqualTo(initial.accessToken());
        assertThat(refreshed.user().id()).isEqualTo(DemoUsers.ALICE.toString());
        assertThat(sessionService.refresh(refreshed.refreshToken())).isNotNull();
    }

    @Test
    void reusingTheRotatedTokenWithinTheGracePeriodIsAllowed() {
        String deviceId = newDeviceId();
        SessionService.Session initial = login(deviceId);
        sessionService.refresh(initial.refreshToken());

        // 模拟第二个标签页用同一个令牌刷新：属于正常并发，不应被判定为攻击
        SessionService.Session second = sessionService.refresh(initial.refreshToken());

        assertThat(second.refreshToken()).isNotBlank();
        assertThat(deviceRepository.isActive(deviceId, DemoUsers.ALICE)).isTrue();
    }

    @Test
    void reusingTheRotatedTokenAfterTheGracePeriodRevokesTheDevice() {
        String deviceId = newDeviceId();
        SessionService.Session initial = login(deviceId);
        sessionService.refresh(initial.refreshToken());
        long auditBefore = auditRepository.countByAction("auth.token_reuse_detected");

        expireGracePeriod(deviceId);

        assertThatThrownBy(() -> sessionService.refresh(initial.refreshToken()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("会话已失效");
        assertThat(deviceRepository.isActive(deviceId, DemoUsers.ALICE)).isFalse();
        assertThat(auditRepository.countByAction("auth.token_reuse_detected"))
                .isEqualTo(auditBefore + 1);
    }

    @Test
    void reusingATokenFromTwoRotationsAgoRevokesTheDeviceImmediately() {
        String deviceId = newDeviceId();
        SessionService.Session initial = login(deviceId);
        SessionService.Session second = sessionService.refresh(initial.refreshToken());
        sessionService.refresh(second.refreshToken());
        long auditBefore = auditRepository.countByAction("auth.token_reuse_detected");

        // 隔了两次轮换的令牌既不是当前也不是上一个，无论宽限期都判定为重放
        assertThatThrownBy(() -> sessionService.refresh(initial.refreshToken()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("会话已失效");
        assertThat(deviceRepository.isActive(deviceId, DemoUsers.ALICE)).isFalse();
        assertThat(auditRepository.countByAction("auth.token_reuse_detected"))
                .isEqualTo(auditBefore + 1);
    }

    @Test
    void revokedDeviceCannotRefreshEvenWithAValidToken() {
        String deviceId = newDeviceId();
        SessionService.Session session = login(deviceId);
        deviceRepository.revoke(deviceId, "admin");

        assertThatThrownBy(() -> sessionService.refresh(session.refreshToken()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("会话已失效");
    }

    @Test
    void logoutRevokesTheDeviceSession() {
        String deviceId = newDeviceId();
        SessionService.Session session = login(deviceId);

        sessionService.logout(DemoUsers.ALICE, deviceId);

        assertThat(deviceRepository.isActive(deviceId, DemoUsers.ALICE)).isFalse();
        assertThatThrownBy(() -> sessionService.refresh(session.refreshToken()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void loggingInAgainOnARevokedDeviceRestoresAccess() {
        String deviceId = newDeviceId();
        login(deviceId);
        sessionService.logout(DemoUsers.ALICE, deviceId);

        // 用户重新通过了身份验证，此前的撤销不应阻止其正常登录
        SessionService.Session session = login(deviceId);

        assertThat(deviceRepository.isActive(deviceId, DemoUsers.ALICE)).isTrue();
        assertThat(sessionService.refresh(session.refreshToken())).isNotNull();
    }

    @Test
    void revokingAllDevicesInvalidatesEverySession() {
        String first = newDeviceId();
        String second = newDeviceId();
        SessionService.Session firstSession = login(first);
        SessionService.Session secondSession = login(second);

        int revoked = sessionService.logoutAllDevices(DemoUsers.ALICE, "user_requested");

        assertThat(revoked).isGreaterThanOrEqualTo(2);
        assertThat(deviceRepository.isActive(first, DemoUsers.ALICE)).isFalse();
        assertThat(deviceRepository.isActive(second, DemoUsers.ALICE)).isFalse();
        assertThatThrownBy(() -> sessionService.refresh(firstSession.refreshToken()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> sessionService.refresh(secondSession.refreshToken()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void malformedRefreshTokensAreRejected() {
        assertThatThrownBy(() -> sessionService.refresh("not-a-jwt"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("刷新令牌无效");
    }

    @Test
    void anAccessTokenCannotBeUsedToRefresh() {
        SessionService.Session session = login(newDeviceId());

        assertThatThrownBy(() -> sessionService.refresh(session.accessToken()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("刷新令牌无效");
    }

    @Test
    void loginIsRecordedInTheAuditLog() {
        long before = auditRepository.countByAction("auth.login");

        login(newDeviceId());

        assertThat(auditRepository.countByAction("auth.login")).isEqualTo(before + 1);
    }

    private SessionService.Session login(String deviceId) {
        User alice = userService.getUser(DemoUsers.ALICE);
        return sessionService.login(alice, deviceId, "Integration Test");
    }

    /** 把轮换时间推到过去，避免测试为了越过宽限窗口而真的等待 */
    private void expireGracePeriod(String deviceId) {
        jdbcClient.sql("""
                        UPDATE devices
                        SET refresh_token_rotated_at = now() - interval '10 minutes'
                        WHERE id = :id
                        """)
                .param("id", deviceId)
                .update();
    }

    /** 每个测试用独立设备，避免共享容器上的测试相互干扰 */
    private String newDeviceId() {
        return "test-device-" + UUID.randomUUID();
    }
}
