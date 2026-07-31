package com.openim.server.auth;

import com.openim.server.config.ProductionProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JWT 签名密钥集合
 *
 * 支持多密钥并存以实现无停机轮换：
 * <ol>
 *   <li>加入新密钥（此时它只用于验证，不用于签发）</li>
 *   <li>把 {@code active-key-id} 切到新密钥，新签发的令牌开始使用它</li>
 *   <li>等待旧令牌自然过期（≥ 刷新令牌 TTL）后移除旧密钥</li>
 * </ol>
 *
 * 令牌头部携带 {@code kid}，验证时据此选择密钥，因此轮换期间新旧令牌都能通过校验。
 *
 * 配置示例：
 * <pre>
 * openim.jwt.active-key-id: k2
 * openim.jwt.keys:
 *   k1: &lt;旧密钥&gt;
 *   k2: &lt;新密钥&gt;
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "openim.jwt")
public class JwtKeySet {
    private static final Logger log = LoggerFactory.getLogger(JwtKeySet.class);

    /** HMAC-SHA256 要求密钥长度不低于摘要长度，否则安全强度会被削弱 */
    private static final int MIN_SECRET_LENGTH = 32;

    private final ProductionProfile productionProfile;

    private Map<String, String> keys = new LinkedHashMap<>();
    private String activeKeyId;

    public JwtKeySet(ProductionProfile productionProfile) {
        this.productionProfile = productionProfile;
    }

    /** 密钥集合的配置绑定入口，null 归一为空集合 */
    public void setKeys(Map<String, String> keys) {
        this.keys = keys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(keys);
    }

    /** 当前签发密钥 ID 的配置绑定入口 */
    public void setActiveKeyId(String activeKeyId) {
        this.activeKeyId = activeKeyId;
    }

    /**
     * 校验密钥配置。
     *
     * 由 {@link JwtService} 在构造时调用而非用 {@code @PostConstruct}，
     * 以保证配置非法时应用启动直接失败，而不是在第一次签发令牌时才暴露。
     */
    void validate() {
        if (keys.isEmpty()) {
            throw new IllegalStateException(
                    "openim.jwt.keys must contain at least one signing key");
        }
        if (activeKeyId == null || !keys.containsKey(activeKeyId)) {
            throw new IllegalStateException(
                    "openim.jwt.active-key-id must reference a key present in openim.jwt.keys");
        }
        boolean production = productionProfile.active();
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String secret = entry.getValue();
            if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
                throw new IllegalStateException(
                        "JWT key '" + entry.getKey() + "' must contain at least "
                                + MIN_SECRET_LENGTH + " characters");
            }
            if (production && secret.contains("do-not-use-in-production")) {
                throw new IllegalStateException(
                        "The local demo JWT key cannot be used in production");
            }
        }
        if (keys.size() > 1) {
            log.info("JWT key rotation in progress: {} keys loaded, signing with '{}'",
                    keys.size(), activeKeyId);
        }
    }

    /** 当前用于签发的密钥 ID */
    public String activeKeyId() {
        return activeKeyId;
    }

    /** 当前签发密钥的字节内容（UTF-8） */
    public byte[] activeSecret() {
        return keys.get(activeKeyId).getBytes(StandardCharsets.UTF_8);
    }

    /** 按 kid 查找验证密钥；未知 kid 返回空，令牌按无效处理 */
    public Optional<byte[]> secretForKeyId(String keyId) {
        return Optional.ofNullable(keys.get(keyId))
                .map(secret -> secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 已加载的密钥 ID 列表（含轮换期的旧密钥），供验证时按 kid 查找 */
    public List<String> keyIds() {
        return List.copyOf(keys.keySet());
    }
}
