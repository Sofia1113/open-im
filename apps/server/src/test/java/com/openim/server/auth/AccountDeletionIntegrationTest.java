package com.openim.server.auth;

import com.openim.server.domain.repository.WalletRepository;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import com.openim.server.support.DemoUsers;
import com.openim.server.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账号注销集成测试
 *
 * 注销是不可逆操作，且涉及个人数据删除的合规义务。这里锁定的是两组性质相反的约束：
 * <ul>
 *   <li><b>必须清除</b>的个人身份信息——否则删除权没有被真正履行</li>
 *   <li><b>必须保留</b>的账务与他人数据——否则对账不平、别人的会话出现空洞</li>
 * </ul>
 * 后一组尤其需要测试保护：一次"顺手把关联数据也删掉"的改动会静默破坏账务完整性，
 * 而症状要到下一次对账时才显现。
 */
@SpringBootTest
class AccountDeletionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PASSWORD = "DeleteMePassw0rd!";

    @Autowired
    private AccountDeletionService deletionService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private JdbcClient jdbcClient;

    private String username;
    private String email;
    private UUID userId;

    @BeforeEach
    void createUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        username = "del_" + suffix;
        email = username + "@example.test";
        authenticationService.register(
                username, email, "注销测试", PASSWORD, "device-" + suffix, "测试设备");
        userId = jdbcClient.sql("SELECT id FROM users WHERE username = :u")
                .param("u", username)
                .query(UUID.class)
                .single();
    }

    // ── 授权 ───────────────────────────────────────────────

    /**
     * 必须验证当前密码。
     *
     * 仅凭有效访问令牌就能注销，等于令牌泄漏后攻击者可以永久摧毁账号。
     * 这比改密的风险更高——改密可以再改回来。
     */
    @Test
    void deletionRequiresTheCurrentPassword() {
        assertThatThrownBy(() -> deletionService.deleteAccount(userId, "WrongPassword!", "h"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("密码不正确");

        // 账号仍然可用
        assertThat(userStatus()).isEqualTo("ACTIVE");
        assertThatCode(() -> authenticationService.login(
                username, PASSWORD, "device-still-ok", "设备"))
                .doesNotThrowAnyException();
    }

    /**
     * 注销失败不得触发账号锁定。
     *
     * 否则一次误操作会变成"既没注销成功、又登不进去"。
     * 频率由限流约束，不需要用锁定来防护。
     */
    @Test
    void failedDeletionDoesNotLockTheAccount() {
        for (int i = 0; i < 6; i++) {
            try {
                deletionService.deleteAccount(userId, "WrongPassword!", "h");
            } catch (RuntimeException ignored) {
                // 预期失败
            }
        }

        assertThatCode(() -> authenticationService.login(
                username, PASSWORD, "device-not-locked", "设备"))
                .as("注销失败不应导致账号被锁定")
                .doesNotThrowAnyException();
    }

    /** 余额不为零时拒绝注销——那笔钱注销后既不属于任何人、又还在账务总额里 */
    @Test
    void deletionIsRejectedWhileWalletHasBalance() {
        creditWallet(1000);

        assertThatThrownBy(() -> deletionService.deleteAccount(userId, PASSWORD, "h"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("余额不为零");

        assertThat(userStatus()).isEqualTo("ACTIVE");
    }

    // ── 必须清除的数据 ──────────────────────────────────────

    @Test
    void deletionAnonymizesIdentifyingInformation() {
        deletionService.deleteAccount(userId, PASSWORD, "h");

        Map<String, Object> row = jdbcClient.sql("""
                        SELECT username, display_name, email, status, avatar,
                               deleted_at, anonymized_at
                          FROM users WHERE id = :id
                        """)
                .param("id", userId)
                .query()
                .singleRow();

        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat((String) row.get("username"))
                .as("用户名有非空唯一约束，必须替换而非清空")
                .startsWith("deleted_")
                .isNotEqualTo(username);
        assertThat(row.get("display_name")).isEqualTo("已注销用户");
        assertThat(row.get("email")).as("邮箱必须清除").isNull();
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("anonymized_at")).isNotNull();
    }

    /** 凭证整行删除——保留一个已注销账号的密码哈希只是多一份可被离线破解的材料 */
    @Test
    void credentialsAreHardDeletedNotAnonymized() {
        deletionService.deleteAccount(userId, PASSWORD, "h");

        long remaining = jdbcClient
                .sql("SELECT count(*) FROM user_credentials WHERE user_id = :id")
                .param("id", userId)
                .query(Long.class)
                .single();

        assertThat(remaining).isZero();
    }

    @Test
    void allSessionsAreRevoked() {
        authenticationService.login(username, PASSWORD, "device-a", "设备A");
        authenticationService.login(username, PASSWORD, "device-b", "设备B");

        deletionService.deleteAccount(userId, PASSWORD, "h");

        long active = jdbcClient.sql("""
                        SELECT count(*) FROM devices
                         WHERE user_id = :id AND revoked_at IS NULL
                        """)
                .param("id", userId)
                .query(Long.class)
                .single();
        assertThat(active).isZero();
    }

    /** 注销后无法再登录：凭证已删除 */
    @Test
    void deletedAccountCannotLogInAgain() {
        deletionService.deleteAccount(userId, PASSWORD, "h");

        assertThatThrownBy(() -> authenticationService.login(
                username, PASSWORD, "device-after", "设备"))
                .isInstanceOf(RuntimeException.class);
    }

    /**
     * 释放邮箱，使它可以重新注册。
     *
     * {@code uq_users_email_lower} 带 {@code status <> 'DELETED'} 条件，
     * 这个条件当初就是为此预留的。
     */
    @Test
    void emailBecomesAvailableForRegistrationAgain() {
        deletionService.deleteAccount(userId, PASSWORD, "h");

        assertThatCode(() -> authenticationService.register(
                username + "_new", email, "新账号", "AnotherPassw0rd!",
                "device-reuse", "设备"))
                .as("注销后同一邮箱应当可以重新注册")
                .doesNotThrowAnyException();
    }

    // ── 必须保留的数据 ──────────────────────────────────────

    /**
     * <b>账务流水必须保留。</b>
     *
     * 删除会让复式记账的"所有账户余额之和恒定"不再成立，对账永久不平。
     * 金额本身不是个人身份信息，且账务记录属于"存在法律义务需要保留的数据"。
     */
    @Test
    void accountingRecordsSurviveDeletion() {
        // 先产生一笔真实的账务流水，再把余额清回零
        creditWallet(500);
        long entriesBefore = walletEntryCount();
        assertThat(entriesBefore).isPositive();
        debitWallet(500);

        long totalBefore = totalBalance();

        deletionService.deleteAccount(userId, PASSWORD, "h");

        assertThat(walletEntryCount())
                .as("账务流水不得随注销删除")
                .isGreaterThanOrEqualTo(entriesBefore);
        assertThat(totalBalance())
                .as("全部账户余额之和不得被注销改变")
                .isEqualTo(totalBefore);
        assertThat(unbalancedTransactionCount())
                .as("注销后不应出现借贷不平衡的交易")
                .isZero();
    }

    /** 钱包账户行保留：它被流水的外键引用 */
    @Test
    void walletAccountRowSurvivesDeletion() {
        deletionService.deleteAccount(userId, PASSWORD, "h");

        long accounts = jdbcClient
                .sql("SELECT count(*) FROM wallet_accounts WHERE user_id = :id")
                .param("id", userId)
                .query(Long.class)
                .single();

        assertThat(accounts).isEqualTo(1);
    }

    /** 审计记录保留，且注销本身也被审计 */
    @Test
    void deletionIsAuditedAndPriorAuditSurvives() {
        long auditBefore = auditCountForUser();
        assertThat(auditBefore).as("注册时应已产生审计记录").isPositive();

        deletionService.deleteAccount(userId, PASSWORD, "h");

        assertThat(auditCountForUser())
                .as("既有审计记录不得随注销删除，且注销本身要留痕")
                .isGreaterThan(auditBefore);

        long deletionAudits = jdbcClient.sql("""
                        SELECT count(*) FROM audit_logs
                         WHERE actor_id = :id AND action = 'auth.account_deleted'
                        """)
                .param("id", userId)
                .query(Long.class)
                .single();
        assertThat(deletionAudits).isEqualTo(1);
    }

    // ── 辅助 ───────────────────────────────────────────────

    private String userStatus() {
        return jdbcClient.sql("SELECT status FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
    }

    private UUID accountId() {
        return jdbcClient.sql("SELECT id FROM wallet_accounts WHERE user_id = :id")
                .param("id", userId)
                .query(UUID.class)
                .single();
    }

    /**
     * 给测试用户的钱包转入。
     *
     * 走生产代码的 {@code WalletRepository.transfer} 而不是手写 INSERT：
     * 手写 SQL 需要自己维护借贷两条分录和余额更新，一旦写错，测试断言的
     * "总额守恒"就变成在验证测试代码自己的正确性，而不是生产代码的。
     *
     * 资金来源是演示用户 Alice 而不是系统托管账户——托管账户的余额恒为 0
     * （"已发出未领取"之外的时刻），从它转出会因余额不足而失败。
     * 从一个真实有余额的账户转出，同时也验证了总额守恒在跨账户转移下成立。
     */
    private void creditWallet(long amount) {
        UUID txId = UUID.randomUUID();
        UUID source = walletRepository.findAccountIdByUserId(DemoUsers.ALICE)
                .orElseThrow(() -> new AssertionError("演示用户 Alice 的钱包不存在"));
        boolean ok = walletRepository.transfer(
                txId, "RED_PACKET_CLAIM", txId, "test-credit:" + txId,
                source, accountId(), amount);
        assertThat(ok).as("测试前置：从 Alice 转入应当成功").isTrue();
    }

    /** 把余额转回去，使注销前钱包为零 */
    private void debitWallet(long amount) {
        UUID txId = UUID.randomUUID();
        UUID sink = walletRepository.findAccountIdByUserId(DemoUsers.ALICE)
                .orElseThrow(() -> new AssertionError("演示用户 Alice 的钱包不存在"));
        boolean ok = walletRepository.transfer(
                txId, "RED_PACKET_SEND", txId, "test-debit:" + txId,
                accountId(), sink, amount);
        assertThat(ok).as("测试前置：转出应当成功").isTrue();
    }

    private long walletEntryCount() {
        return jdbcClient.sql("""
                        SELECT count(*) FROM wallet_entries e
                          JOIN wallet_accounts a ON a.id = e.account_id
                         WHERE a.user_id = :id
                        """)
                .param("id", userId)
                .query(Long.class)
                .single();
    }

    /**
     * 全部账户余额之和。
     *
     * 用生产代码的 {@code totalBalance()} 而不是自己写 SQL——刚开始这里写的是
     * {@code sum(amount) FROM wallet_entries} 并期望它为 0，那是错的：
     * {@code wallet_entries.amount} 有 {@code CHECK (amount > 0)} 约束，
     * 借贷方向由 {@code direction} 列表示，不是正负号。
     */
    private long totalBalance() {
        return walletRepository.totalBalance();
    }

    /**
     * 借贷不平衡的交易数。
     *
     * 同样复用生产代码的对账查询：它按 {@code direction} 分别求和后比较，
     * 这是这套 schema 下唯一正确的判定方式。
     */
    private long unbalancedTransactionCount() {
        return walletRepository.findUnbalancedTransactions().size();
    }

    private long auditCountForUser() {
        return jdbcClient.sql("SELECT count(*) FROM audit_logs WHERE actor_id = :id")
                .param("id", userId)
                .query(Long.class)
                .single();
    }
}
