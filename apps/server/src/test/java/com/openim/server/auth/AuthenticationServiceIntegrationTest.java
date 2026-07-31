package com.openim.server.auth;

import com.openim.server.domain.repository.AuditRepository;
import com.openim.server.domain.repository.CredentialRepository;
import com.openim.server.domain.repository.DeviceRepository;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import com.openim.server.support.DemoUsers;
import com.openim.server.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 凭证认证集成测试
 *
 * 覆盖此前完全缺失的能力：prod profile 下唯一的登录入口。
 *
 * BCrypt cost 降到 4：默认的 12 每次哈希约 200-400ms，本类有十余次哈希操作，
 * 用默认值会让这个测试类独占十几秒。cost 不影响被测逻辑的正确性。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "openim.auth.bcrypt-cost=4",
        "openim.auth.max-failed-attempts=3",
        "openim.auth.lock-duration-seconds=900"
})
class AuthenticationServiceIntegrationTest extends AbstractPostgresIntegrationTest {
    /** 与 db/seed/R__demo_seed.sql 中的演示凭证一致 */
    private static final String DEMO_PASSWORD = "openim-demo-2026";

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private CredentialRepository credentialRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private JdbcClient jdbcClient;

    /**
     * 每个用例前重置演示账号的凭证状态。
     *
     * 种子数据的哈希是 cost 12 的，而本类用 cost 4 的编码器——BCrypt 的
     * 校验会读取哈希自带的 cost，因此仍然能正确验证，无需重写。
     * 这里只清理失败计数与锁定，避免用例之间相互影响。
     */
    @BeforeEach
    void resetCredentialState() {
        credentialRepository.unlock(DemoUsers.ALICE);
        credentialRepository.unlock(DemoUsers.BOB);
        credentialRepository.unlock(DemoUsers.CAROL);
    }

    @Test
    void loginWithCorrectPasswordIssuesASession() {
        SessionService.Session session = authenticationService.login(
                "alice", DEMO_PASSWORD, "cred-device-1", "Integration Test");

        assertThat(session.accessToken()).isNotBlank();
        assertThat(session.refreshToken()).isNotBlank();
        assertThat(session.user().username()).isEqualTo("alice");
        // 登录成功必须登记设备，否则后续鉴权会因设备不存在而失败
        assertThat(deviceRepository.isSessionActive("cred-device-1", DemoUsers.ALICE)).isTrue();
    }

    @Test
    void loginAcceptsEmailAsIdentifier() {
        SessionService.Session session = authenticationService.login(
                "alice@example.test", DEMO_PASSWORD, "cred-device-2", "Integration Test");

        assertThat(session.user().id()).isEqualTo(DemoUsers.ALICE.toString());
    }

    @Test
    void emailMatchingIsCaseInsensitive() {
        // 邮箱大小写不敏感：否则同一个人用 Alice@ 和 alice@ 会得到不同结果
        SessionService.Session session = authenticationService.login(
                "ALICE@EXAMPLE.TEST", DEMO_PASSWORD, "cred-device-3", "Integration Test");

        assertThat(session.user().id()).isEqualTo(DemoUsers.ALICE.toString());
    }

    @Test
    void wrongPasswordAndUnknownAccountAreIndistinguishable() {
        ApiException wrongPassword = catchApiException(() ->
                authenticationService.login("alice", "definitely-not-the-password",
                        "cred-device-4", "Test"));
        ApiException unknownUser = catchApiException(() ->
                authenticationService.login("no-such-user", "definitely-not-the-password",
                        "cred-device-4", "Test"));

        // 两条路径的状态码、错误码和消息必须完全一致，
        // 否则登录接口就成了账号枚举接口
        assertThat(wrongPassword.status()).isEqualTo(unknownUser.status());
        assertThat(wrongPassword.code()).isEqualTo(unknownUser.code());
        assertThat(wrongPassword.getMessage()).isEqualTo(unknownUser.getMessage());
        assertThat(wrongPassword.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void accountLocksAfterConfiguredFailedAttempts() {
        // max-failed-attempts=3：前两次返回凭证错误
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> authenticationService.login(
                    "bob", "wrong-password", "cred-device-5", "Test"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).code())
                            .isEqualTo("INVALID_CREDENTIALS"));
        }

        // 第三次触发锁定
        assertThatThrownBy(() -> authenticationService.login(
                "bob", "wrong-password", "cred-device-5", "Test"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ACCOUNT_LOCKED"));

        // 锁定后即使密码正确也不放行——这正是锁定的意义
        assertThatThrownBy(() -> authenticationService.login(
                "bob", DEMO_PASSWORD, "cred-device-5", "Test"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ACCOUNT_LOCKED"));

        assertThat(credentialRepository.findByUserId(DemoUsers.BOB).orElseThrow().locked()).isTrue();
    }

    @Test
    void successfulLoginClearsFailedAttemptCounter() {
        catchApiException(() -> authenticationService.login("carol", "wrong-1", "d", "T"));
        catchApiException(() -> authenticationService.login("carol", "wrong-2", "d", "T"));
        assertThat(credentialRepository.findByUserId(DemoUsers.CAROL)
                .orElseThrow().failedAttempts()).isGreaterThan(0);

        authenticationService.login("carol", DEMO_PASSWORD, "cred-device-6", "Test");

        // 成功登录清零：否则偶发的失败会累积到锁定，误伤正常用户
        assertThat(credentialRepository.findByUserId(DemoUsers.CAROL)
                .orElseThrow().failedAttempts()).isZero();
    }

    @Test
    void registrationCreatesAnAccountThatCanLogIn() {
        String username = "newuser" + System.nanoTime() % 100000;

        SessionService.Session registered = authenticationService.register(
                username, username + "@example.test", "New User",
                "a-sufficiently-long-password", "reg-device", "Test");

        assertThat(registered.user().username()).isEqualTo(username);

        // 注册后必须能用同样的凭证登录，否则注册链路是断的
        SessionService.Session loggedIn = authenticationService.login(
                username, "a-sufficiently-long-password", "reg-device-2", "Test");
        assertThat(loggedIn.user().id()).isEqualTo(registered.user().id());
    }

    @Test
    void registrationCreatesAWalletAccount() {
        String username = "walletuser" + System.nanoTime() % 100000;

        SessionService.Session session = authenticationService.register(
                username, null, "Wallet User",
                "a-sufficiently-long-password", "wallet-device", "Test");

        // 没有钱包账户的用户在使用红包功能时会遇到运行期错误，
        // 而这个前置条件与账号创建是同一件事
        Long count = jdbcClient.sql("""
                        SELECT count(*) FROM wallet_accounts
                        WHERE owner_type = 'USER' AND user_id = :userId
                        """)
                .param("userId", UUID.fromString(session.user().id()))
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void duplicateUsernameIsRejectedWithoutRevealingWhichFieldCollided() {
        ApiException exception = catchApiException(() -> authenticationService.register(
                "alice", "different@example.test", "Impostor",
                "a-sufficiently-long-password", "dup-device", "Test"));

        assertThat(exception.code()).isEqualTo("IDENTIFIER_TAKEN");
        // 消息同时覆盖两种冲突，不告诉调用方是用户名还是邮箱已存在
        assertThat(exception.getMessage()).contains("用户名或邮箱");
    }

    @Test
    void duplicateEmailIsRejected() {
        ApiException exception = catchApiException(() -> authenticationService.register(
                "totallynew" + System.nanoTime() % 100000, "ALICE@EXAMPLE.TEST", "Impostor",
                "a-sufficiently-long-password", "dup-device-2", "Test"));

        // 大小写不同也应命中唯一约束，否则可以注册出视觉上重复的账号
        assertThat(exception.code()).isEqualTo("IDENTIFIER_TAKEN");
    }

    @Test
    void weakPasswordsAreRejectedAtRegistration() {
        String username = "weak" + System.nanoTime() % 100000;

        assertThatThrownBy(() -> authenticationService.register(
                username, null, "Weak", "short", "weak-device", "Test"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("VALIDATION_ERROR"));

        assertThatThrownBy(() -> authenticationService.register(
                username, null, "Weak", "password123", "weak-device", "Test"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void changingPasswordRevokesAllSessionsIncludingTheCurrentOne() {
        String username = "changer" + System.nanoTime() % 100000;
        SessionService.Session first = authenticationService.register(
                username, null, "Changer", "original-long-password", "change-d1", "Test");
        authenticationService.login(username, "original-long-password", "change-d2", "Test");
        UUID userId = UUID.fromString(first.user().id());

        assertThat(deviceRepository.isSessionActive("change-d1", userId)).isTrue();
        assertThat(deviceRepository.isSessionActive("change-d2", userId)).isTrue();

        authenticationService.changePassword(
                userId, "original-long-password", "a-brand-new-long-password");

        // 改密的语义是"此前所有会话都不再可信"，包括发起改密的这一台。
        // 如果账号确实已被入侵，改密必须把入侵者踢出去。
        assertThat(deviceRepository.isSessionActive("change-d1", userId)).isFalse();
        assertThat(deviceRepository.isSessionActive("change-d2", userId)).isFalse();

        // 新密码可用，旧密码不可用
        authenticationService.login(username, "a-brand-new-long-password", "change-d3", "Test");
        assertThatThrownBy(() -> authenticationService.login(
                username, "original-long-password", "change-d4", "Test"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void changingPasswordRequiresTheCurrentPassword() {
        String username = "verifier" + System.nanoTime() % 100000;
        SessionService.Session session = authenticationService.register(
                username, null, "Verifier", "original-long-password", "verify-d1", "Test");
        UUID userId = UUID.fromString(session.user().id());

        // 仅凭有效访问令牌就能改密，等于令牌泄漏即账号丢失
        ApiException exception = catchApiException(() -> authenticationService.changePassword(
                userId, "not-the-current-password", "a-brand-new-long-password"));
        assertThat(exception.code()).isEqualTo("INVALID_CREDENTIALS");

        // 原密码仍然有效，改密没有生效
        authenticationService.login(username, "original-long-password", "verify-d2", "Test");
    }

    @Test
    void failedChangePasswordCountsTowardAccountLock() {
        String username = "brute" + System.nanoTime() % 100000;
        SessionService.Session session = authenticationService.register(
                username, null, "Brute", "original-long-password", "brute-d1", "Test");
        UUID userId = UUID.fromString(session.user().id());

        // max-failed-attempts=3。改密接口若不计入失败次数，
        // 它就是一个不受锁定保护的密码爆破入口。
        for (int i = 0; i < 3; i++) {
            String guess = "guess-" + i;
            catchApiException(() -> authenticationService.changePassword(
                    userId, guess, "a-brand-new-long-password"));
        }

        assertThat(credentialRepository.findByUserId(userId).orElseThrow().locked()).isTrue();
    }

    @Test
    void newPasswordMustDifferFromCurrent() {
        String username = "same" + System.nanoTime() % 100000;
        SessionService.Session session = authenticationService.register(
                username, null, "Same", "original-long-password", "same-d1", "Test");

        ApiException exception = catchApiException(() -> authenticationService.changePassword(
                UUID.fromString(session.user().id()),
                "original-long-password", "original-long-password"));

        assertThat(exception.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void authenticationEventsAreAudited() {
        long loginsBefore = auditRepository.countByAction("auth.login");
        long failuresBefore = auditRepository.countByAction("auth.login_failed");

        authenticationService.login("alice", DEMO_PASSWORD, "audit-device", "Test");
        catchApiException(() -> authenticationService.login(
                "alice", "wrong-password", "audit-device", "Test"));

        // 登录与失败都必须留痕（SECURITY.md §8）
        assertThat(auditRepository.countByAction("auth.login")).isGreaterThan(loginsBefore);
        assertThat(auditRepository.countByAction("auth.login_failed")).isGreaterThan(failuresBefore);
    }

    /** 断言抛出的是 ApiException 并返回它，便于比较错误码与消息 */
    private static ApiException catchApiException(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected an ApiException but none was thrown");
        } catch (ApiException exception) {
            return exception;
        }
    }
}
