package com.openim.server.web;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 客户端 IP 的单向标识
 *
 * {@code audit_logs} 表的注释写着"ip 只存哈希，不存明文"，且专门有一列
 * {@code ip_hash}——但在此之前那一列从未被写入过，审计记录里没有任何来源信息。
 * 排查"这些重置请求来自哪里"时无从下手。
 *
 * <b>为什么必须加盐</b>：IPv4 地址空间只有 2³² 个值。无盐的 SHA-256 可以在
 * 普通机器上几分钟内全量枚举建成彩虹表，届时"哈希"提供的隐私保护为零——
 * 任何拿到数据库的人都能还原出每一条记录的精确 IP。加一个部署级密钥后，
 * 攻击者必须同时拿到数据库和密钥才能反查。
 *
 * 盐值固定（不是每条记录随机）是有意的：审计的用途是"找出同一来源的多次操作"，
 * 需要同一个 IP 每次得到相同的哈希。这是可链接性与不可还原性之间的取舍，
 * 对审计场景来说前者是必需的。
 *
 * <b>盐值轮换的代价</b>：轮换后新旧哈希无法互相比对，历史记录变成孤立的。
 * 因此不要例行轮换——只在怀疑盐值泄漏时轮换，并接受历史审计失去可链接性。
 */
@Component
public class ClientIpHasher {
    private static final Logger log = LoggerFactory.getLogger(ClientIpHasher.class);

    /** 未知来源的固定标识。不参与哈希，避免把"取不到 IP"也变成一个不可读的哈希值 */
    public static final String UNKNOWN = "unknown";

    private final String salt;
    private final boolean usingDerivedSalt;

    public ClientIpHasher(
            @Value("${openim.audit.ip-hash-salt:}") String configuredSalt,
            @Value("${openim.jwt.keys.k1:}") String fallbackSecret
    ) {
        if (configuredSalt != null && !configuredSalt.isBlank()) {
            this.salt = configuredSalt;
            this.usingDerivedSalt = false;
        } else {
            // 未单独配置时从签名密钥派生，而不是用一个硬编码常量——
            // 硬编码盐等于没有盐（所有部署共用同一张彩虹表）。
            // 派生而非直接使用，避免审计哈希与令牌签名共享同一个输入。
            this.salt = "openim-audit-ip:" + fallbackSecret;
            this.usingDerivedSalt = true;
        }
    }

    /** 启动时若盐值是从签名密钥派生的，打印提示日志（涉及密钥轮换后审计哈希的可比性） */
    @PostConstruct
    void warnAboutDerivedSalt() {
        if (usingDerivedSalt) {
            log.info("IP 哈希盐值未单独配置，已从 JWT 签名密钥派生。"
                    + "轮换签名密钥会使历史审计记录中的 IP 哈希不再可比对——"
                    + "若需要跨密钥轮换保持可链接性，请显式配置 openim.audit.ip-hash-salt。");
        }
    }

    /**
     * 请求来源 IP 的哈希。
     *
     * 生产环境启用了 {@code forward-headers-strategy: framework}，Spring 会依据
     * 受信任的代理头还原真实来源，因此直接取 {@code remoteAddr}。
     * 不自行解析 {@code X-Forwarded-For}——那个头可由客户端伪造。
     */
    public String hashOf(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        return hash(request.getRemoteAddr());
    }

    /** 哈希一个 IP 字符串。空值返回 {@link #UNKNOWN} 而不是空串的哈希 */
    public String hash(String ip) {
        if (ip == null || ip.isBlank()) {
            return UNKNOWN;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((salt + "|" + ip).getBytes(StandardCharsets.UTF_8));
            // 截断到 32 个十六进制字符（128 位）：足以避免碰撞，
            // 又让审计记录不至于被哈希值撑满
            return HexFormat.of().formatHex(hashed).substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required but unavailable", exception);
        }
    }
}
