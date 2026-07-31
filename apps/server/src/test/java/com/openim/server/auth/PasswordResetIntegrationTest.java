package com.openim.server.auth;

import com.openim.server.domain.repository.CredentialRepository;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import com.openim.server.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密码重置集成测试
 *
 * 覆盖的是这个流程的安全属性，而不只是"能不能走通"。密码重置是账号接管的
 * 主要攻击面——它允许在不知道当前密码的前提下更改密码，因此每一条约束
 * 都必须有测试锁定。
 */
@SpringBootTest(properties = {
        "openim.auth.password-reset-enabled=true",
        "openim.auth.password-reset-ttl-seconds=1800"
})
class PasswordResetIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * 捕获令牌的送达实现。
     *
     * 真实流程里令牌只出现在邮件里，测试必须有办法拿到它。
     * {@code @Primary} 让它覆盖 {@code PasswordResetConfig} 的兜底实现。
     */
    @TestConfiguration
    static class CapturingNotifierConfig {
        @Bean
        @Primary
        CapturingNotifier capturingNotifier() {
            return new CapturingNotifier();
        }
    }

    static class CapturingNotifier implements PasswordResetNotifier {
        final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public void sendResetToken(
                String email, String displayName, String token, Duration validFor) {
            sent.add(new Sent(email, displayName, token, validFor));
        }

        @Override
        public boolean productionReady() {
            return true;
        }

        String latestToken() {
            if (sent.isEmpty()) {
                throw new AssertionError("没有任何令牌被送达");
            }
            return sent.get(sent.size() - 1).token();
        }

        void clear() {
            sent.clear();
        }

        record Sent(String email, String displayName, String token, Duration validFor) {
        }
    }

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private CredentialRepository credentialRepository;

    @Autowired
    private CapturingNotifier notifier;

    @Autowired
    private JdbcClient jdbcClient;

    private String email;
    private String username;
    private UUID userId;

    @BeforeEach
    void createUser() {
        notifier.clear();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        username = "reset_" + suffix;
        email = username + "@example.test";
        authenticationService.register(
                username, email, "重置测试", "InitialPassw0rd!", "device-" + suffix, "测试设备");
        userId = jdbcClient.sql("SELECT id FROM users WHERE username = :u")
                .param("u", username)
                .query(UUID.class)
                .single();
    }

    // ── 账号枚举防护 ────────────────────────────────────────

    /**
     * 未注册邮箱不得产生任何可区分的行为。
     *
     * 这是本流程最容易泄漏信息的地方：返回"该邮箱未注册"会把接口变成
     * 批量验证邮箱是否注册的工具。
     */
    @Test
    void requestingResetForUnknownEmailSucceedsSilently() {
        assertThatCode(() -> passwordResetService.requestReset(
                "definitely-not-registered@example.test", "iphash"))
                .doesNotThrowAnyException();

        // 没有令牌被发出，但调用方无法区分
        assertThat(notifier.sent).isEmpty();
    }

    /** 针对未注册邮箱的请求仍然留痕——大量此类请求本身就是枚举探测的信号 */
    @Test
    void unknownEmailRequestIsAudited() {
        long before = countAudit("auth.password_reset_requested_unknown");

        passwordResetService.requestReset("nobody-" + UUID.randomUUID() + "@example.test", "h1");

        assertThat(countAudit("auth.password_reset_requested_unknown")).isEqualTo(before + 1);
    }

    /** 邮箱大小写不敏感，与 uq_users_email_lower 的唯一性判定一致 */
    @Test
    void emailLookupIsCaseInsensitive() {
        passwordResetService.requestReset(email.toUpperCase(), "iphash");

        assertThat(notifier.sent).hasSize(1);
        assertThat(notifier.sent.get(0).email()).isEqualTo(email);
    }

    // ── 令牌生命周期 ────────────────────────────────────────

    @Test
    void tokenCanBeUsedOnceToResetPassword() {
        passwordResetService.requestReset(email, "iphash");
        String token = notifier.latestToken();

        passwordResetService.confirmReset(token, "BrandNewPassw0rd!");

        // 新密码可以登录
        assertThatCode(() -> authenticationService.login(
                username, "BrandNewPassw0rd!", "device-new", "新设备"))
                .doesNotThrowAnyException();
    }

    /**
     * 令牌单次有效。
     *
     * 第二次使用必须失败——否则一个泄漏的令牌可以被反复用来接管账号，
     * 而用户在第一次重置后不会再关注它。
     */
    @Test
    void tokenCannotBeReused() {
        passwordResetService.requestReset(email, "iphash");
        String token = notifier.latestToken();

        passwordResetService.confirmReset(token, "FirstNewPassw0rd!");

        assertThatThrownBy(() -> passwordResetService.confirmReset(token, "SecondNewPassw0rd!"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无效或已过期");
    }

    /**
     * 申请新令牌作废此前未使用的令牌。
     *
     * 否则用户连点三次"忘记密码"会得到三个同时有效的令牌，攻击面是三倍，
     * 而用户只会用最新那一封邮件里的。
     */
    @Test
    void requestingANewTokenInvalidatesPriorOnes() {
        passwordResetService.requestReset(email, "iphash");
        String firstToken = notifier.latestToken();

        passwordResetService.requestReset(email, "iphash");
        String secondToken = notifier.latestToken();

        assertThat(firstToken).isNotEqualTo(secondToken);

        // 旧令牌已失效
        assertThatThrownBy(() -> passwordResetService.confirmReset(firstToken, "Passw0rd!New1"))
                .isInstanceOf(ApiException.class);

        // 新令牌可用
        assertThatCode(() -> passwordResetService.confirmReset(secondToken, "Passw0rd!New2"))
                .doesNotThrowAnyException();
    }

    @Test
    void expiredTokenIsRejected() {
        passwordResetService.requestReset(email, "iphash");
        String token = notifier.latestToken();

        // 直接把过期时间推到过去，比等 30 分钟现实
        jdbcClient.sql("""
                        UPDATE password_reset_tokens
                        SET expires_at = now() - interval '1 minute'
                        WHERE user_id = :userId AND used_at IS NULL
                        """)
                .param("userId", userId)
                .update();

        assertThatThrownBy(() -> passwordResetService.confirmReset(token, "Passw0rd!Expired"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无效或已过期");
    }

    @Test
    void unknownTokenIsRejectedWithTheSameErrorAsExpiredOne() {
        // 不存在、过期、已使用三种情况的对外响应必须一致，
        // 否则攻击者能验证一个令牌是否曾经存在
        assertThatThrownBy(() -> passwordResetService.confirmReset(
                "totally-made-up-token", "Passw0rd!Fake"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无效或已过期");
    }

    /** 服务端不留存明文令牌——数据库里只有哈希 */
    @Test
    void plaintextTokenIsNeverStored() {
        passwordResetService.requestReset(email, "iphash");
        String token = notifier.latestToken();

        List<String> storedHashes = jdbcClient
                .sql("SELECT token_hash FROM password_reset_tokens WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .list();

        assertThat(storedHashes).isNotEmpty();
        assertThat(storedHashes).doesNotContain(token);
        // 存的是 SHA-256 十六进制：64 个字符
        assertThat(storedHashes.get(0)).hasSize(64).matches("[0-9a-f]+");
    }

    // ── 重置的副作用 ────────────────────────────────────────

    /**
     * 重置后撤销全部设备会话。
     *
     * 走到重置流程通常意味着账号可能已被入侵，必须把入侵者的会话一并踢出。
     */
    @Test
    void resetRevokesAllExistingSessions() {
        // 先建立两个设备会话
        authenticationService.login(username, "InitialPassw0rd!", "device-a", "设备A");
        authenticationService.login(username, "InitialPassw0rd!", "device-b", "设备B");
        assertThat(activeDeviceCount()).isGreaterThanOrEqualTo(2);

        passwordResetService.requestReset(email, "iphash");
        passwordResetService.confirmReset(notifier.latestToken(), "AfterResetPassw0rd!");

        assertThat(activeDeviceCount())
                .as("重置后所有设备会话都应被撤销")
                .isZero();
    }

    /**
     * <b>重置必须解除账号锁定。</b>
     *
     * 这条是整个流程存在的直接理由之一：账号锁定的提示写着
     * "账号已临时锁定，请稍后再试或重置密码"。如果重置后仍然处于锁定状态，
     * 那句提示就是一句空话——用户按提示做了，结果还是登不进去。
     *
     * 锁定检查在密码比对之前执行（见 AuthenticationService.login），
     * 因此密码正确也无法绕过。
     */
    @Test
    void resetClearsAccountLockSoTheLockoutHintIsActuallyActionable() {
        // 连续失败直到锁定
        for (int i = 0; i < 6; i++) {
            try {
                authenticationService.login(username, "WrongPassword!", "device-x", "设备X");
            } catch (RuntimeException ignored) {
                // 预期失败
            }
        }
        assertThat(credentialRepository.findByUserId(userId))
                .get()
                .satisfies(c -> assertThat(c.locked())
                        .as("前置条件：账号应已被锁定")
                        .isTrue());

        passwordResetService.requestReset(email, "iphash");
        passwordResetService.confirmReset(notifier.latestToken(), "UnlockedPassw0rd!");

        assertThat(credentialRepository.findByUserId(userId))
                .get()
                .satisfies(c -> {
                    assertThat(c.locked()).as("重置后锁定应被解除").isFalse();
                    assertThat(c.failedAttempts()).as("失败计数应清零").isZero();
                });

        // 真正能登录才算修好
        assertThatCode(() -> authenticationService.login(
                username, "UnlockedPassw0rd!", "device-after-unlock", "解锁后设备"))
                .doesNotThrowAnyException();
    }

    @Test
    void resetEnforcesPasswordPolicy() {
        passwordResetService.requestReset(email, "iphash");
        String token = notifier.latestToken();

        assertThatThrownBy(() -> passwordResetService.confirmReset(token, "123"))
                .isInstanceOf(RuntimeException.class);

        // 策略拒绝不应消耗令牌——用户改用一个合规密码还能继续
        assertThatCode(() -> passwordResetService.confirmReset(token, "CompliantPassw0rd!"))
                .doesNotThrowAnyException();
    }

    @Test
    void completedResetIsAudited() {
        long before = countAudit("auth.password_reset_completed");

        passwordResetService.requestReset(email, "iphash");
        passwordResetService.confirmReset(notifier.latestToken(), "AuditedPassw0rd!");

        assertThat(countAudit("auth.password_reset_completed")).isEqualTo(before + 1);
    }

    /** 审计记录带来源哈希，使"同一来源试探了多少账号"可查 */
    @Test
    void auditRecordsCarryIpHash() {
        passwordResetService.requestReset(email, "distinctive-hash-value");

        String storedHash = jdbcClient.sql("""
                        SELECT ip_hash FROM audit_logs
                         WHERE action = 'auth.password_reset_requested'
                           AND actor_id = :userId
                         ORDER BY created_at DESC LIMIT 1
                        """)
                .param("userId", userId)
                .query(String.class)
                .single();

        assertThat(storedHash).isEqualTo("distinctive-hash-value");
    }

    // ── 清理 ───────────────────────────────────────────────

    @Test
    void staleTokensArePurged() {
        passwordResetService.requestReset(email, "iphash");
        passwordResetService.confirmReset(notifier.latestToken(), "PurgeTestPassw0rd!");

        // 把使用时间推到保留期之前
        jdbcClient.sql("""
                        UPDATE password_reset_tokens
                        SET used_at = now() - interval '30 days'
                        WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .update();

        int purged = passwordResetService.purgeStaleTokens(Duration.ofDays(7));

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(tokenCount()).isZero();
    }

    /** 保留期内的记录不被清理——排查"账号是谁重置的"需要它们 */
    @Test
    void recentTokensSurvivePurge() {
        passwordResetService.requestReset(email, "iphash");
        passwordResetService.confirmReset(notifier.latestToken(), "RecentPassw0rd!");

        passwordResetService.purgeStaleTokens(Duration.ofDays(7));

        assertThat(tokenCount()).isEqualTo(1);
    }

    // ── 辅助 ───────────────────────────────────────────────

    private long countAudit(String action) {
        return jdbcClient.sql("SELECT count(*) FROM audit_logs WHERE action = :action")
                .param("action", action)
                .query(Long.class)
                .single();
    }

    private long activeDeviceCount() {
        return jdbcClient.sql("""
                        SELECT count(*) FROM devices
                         WHERE user_id = :userId AND revoked_at IS NULL
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private long tokenCount() {
        return jdbcClient.sql("SELECT count(*) FROM password_reset_tokens WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
