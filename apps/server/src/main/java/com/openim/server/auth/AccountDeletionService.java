package com.openim.server.auth;

import com.openim.server.domain.repository.AuditRepository;
import com.openim.server.domain.repository.CredentialRepository;
import com.openim.server.domain.repository.CredentialRepository.Credential;
import com.openim.server.domain.repository.PasswordResetRepository;
import com.openim.server.domain.repository.UserRepository;
import com.openim.server.domain.repository.WalletRepository;
import com.openim.server.domain.service.DomainErrors;
import com.openim.server.observability.AuthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 账号注销与个人数据删除
 *
 * 履行个人信息保护法与 GDPR 的删除权。此前 {@code users.status} 的 CHECK 约束里
 * 就有 {@code 'DELETED'} 取值，但没有一行代码会写入它——删除权无法履行。
 *
 * <b>实现方式是匿名化，不是 DELETE。</b>原因见 {@code V6__account_deletion.sql}
 * 的注释：{@code users} 被 14 处外键引用，硬删除会破坏账务完整性
 * （复式记账的"所有账户余额之和恒定"不再成立）或让其他用户的会话历史出现空洞。
 *
 * <b>清除的数据</b>
 * <table>
 *   <caption>匿名化范围</caption>
 *   <tr><th>数据</th><th>处理</th></tr>
 *   <tr><td>username</td><td>替换为 {@code deleted_<uuid前8位>}（唯一约束要求非空且唯一）</td></tr>
 *   <tr><td>display_name</td><td>替换为"已注销用户"</td></tr>
 *   <tr><td>email</td><td>置 NULL（唯一索引带 status &lt;&gt; 'DELETED' 条件，不会与新注册冲突）</td></tr>
 *   <tr><td>avatar</td><td>置为占位符</td></tr>
 *   <tr><td>密码凭证</td><td><b>整行删除</b>——凭证不存在保留的理由</td></tr>
 *   <tr><td>设备会话</td><td>全部撤销，刷新令牌哈希清空</td></tr>
 *   <tr><td>密码重置令牌</td><td>全部删除</td></tr>
 * </table>
 *
 * <b>保留的数据及理由</b>
 * <table>
 *   <caption>保留范围</caption>
 *   <tr><th>数据</th><th>理由</th></tr>
 *   <tr><td>账务流水（wallet_*）</td><td>删除会让借贷不平、总额不守恒。
 *       金额本身不是个人身份信息，且账务记录属于"存在法律义务需要保留的数据"</td></tr>
 *   <tr><td>消息正文</td><td>群聊与单聊消息同时是<b>其他参与者</b>的通信记录。
 *       发送者已匿名化，正文保留——完全删除会在他人的会话里留下无法解释的空洞</td></tr>
 *   <tr><td>审计日志</td><td>合规依据。{@code actor_id} 保留以维持可追溯性，
 *       但它已不再指向任何可识别信息</td></tr>
 * </table>
 *
 * <b>这个边界是一个有法律含义的设计选择，不是技术妥协。</b>上线前应由法务确认，
 * 尤其是"消息正文保留"这一条——不同司法辖区对通信记录的共同所有权认定不同。
 *
 * <b>为什么要验证密码</b>：仅凭有效访问令牌就允许注销，等于令牌泄漏后攻击者
 * 可以直接销毁账号，而这个操作<b>不可逆</b>。这与改密要求验证当前密码是同一个理由，
 * 但后果更严重——改密可以再改回来，注销不能。
 */
@Service
public class AccountDeletionService {
    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private static final String ANONYMIZED_DISPLAY_NAME = "已注销用户";
    private static final String ANONYMIZED_AVATAR = "?";

    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final WalletRepository walletRepository;
    private final SessionService sessionService;
    private final AuditRepository auditRepository;
    private final AuthMetrics metrics;
    private final PasswordEncoder passwordEncoder;

    public AccountDeletionService(
            UserRepository userRepository,
            CredentialRepository credentialRepository,
            PasswordResetRepository passwordResetRepository,
            WalletRepository walletRepository,
            SessionService sessionService,
            AuditRepository auditRepository,
            AuthMetrics metrics,
            @Value("${openim.auth.bcrypt-cost:12}") int bcryptCost
    ) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.walletRepository = walletRepository;
        this.sessionService = sessionService;
        this.auditRepository = auditRepository;
        this.metrics = metrics;
        this.passwordEncoder = new BCryptPasswordEncoder(bcryptCost);
    }

    /**
     * 注销账号。
     *
     * <b>不可逆。</b>调用方（前端）必须先做明确的二次确认。
     *
     * @param password 当前密码。注销是不可逆操作，不能只凭令牌授权
     * @param ipHash   来源哈希，写入审计
     */
    @Transactional
    public void deleteAccount(UUID userId, String password, String ipHash) {
        Credential credential = credentialRepository.findByUserId(userId)
                .orElseThrow(() -> DomainErrors.notFound("凭证不存在"));

        if (!passwordEncoder.matches(password, credential.passwordHash())) {
            // 不计入登录失败锁定：注销失败不该把账号锁死——那会让一次误操作
            // 变成"既没注销成功，又登不进去"。限流已经约束了尝试频率。
            metrics.recordAccountDeletionFailure();
            throw new com.openim.server.web.ApiException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    "密码不正确");
        }

        // 余额非零时拒绝注销：虚拟资产的归属必须先由用户自己处置完，
        // 否则注销之后那笔钱既不属于任何人、又还在账务总额里，对账时会成为一个
        // 无法解释的余额。真实支付场景下这一步应该是提现或退回。
        long balance = walletRepository.findWalletByUserId(userId)
                .map(com.openim.server.domain.DomainModels.Wallet::balance)
                .orElse(0L);
        if (balance != 0) {
            throw DomainErrors.conflict("WALLET_NOT_EMPTY",
                    "钱包余额不为零，请先处置余额后再注销");
        }

        // 顺序有意：先撤销会话（立即失效），再清凭证，最后匿名化。
        // 反过来的话，匿名化之后到会话撤销之前的窗口里，旧令牌仍然可用，
        // 而此时用户资料已经是匿名状态——一个既登录着又不存在的账号。
        int revoked = sessionService.logoutAllDevices(userId, "account_deleted");
        credentialRepository.deleteByUserId(userId);
        passwordResetRepository.deleteAllForUser(userId);
        userRepository.anonymize(userId, anonymizedUsername(userId),
                ANONYMIZED_DISPLAY_NAME, ANONYMIZED_AVATAR);

        // 审计必须在匿名化之后仍然可写——actor_id 指向的行还在（已匿名化），
        // 外键约束成立
        auditRepository.recordUserAction(userId, "auth.account_deleted", ipHash,
                Map.of("revokedSessions", revoked));
        metrics.recordAccountDeletion();
        log.info("Account deleted and anonymized userId={} revokedSessions={}",
                userId, revoked);
    }

    /**
     * 匿名化后的用户名。
     *
     * {@code username} 有非空唯一约束，因此不能置空——必须给一个唯一值。
     * 用 id 前 8 位而不是完整 UUID：足够避免碰撞（同一系统内 id 本就唯一），
     * 又让它在人工排查时短到可读。前缀 {@code deleted_} 使它一眼可辨。
     */
    private static String anonymizedUsername(UUID userId) {
        return "deleted_" + userId.toString().substring(0, 8);
    }
}
