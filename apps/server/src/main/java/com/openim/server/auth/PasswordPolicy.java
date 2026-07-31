package com.openim.server.auth;

import com.openim.server.domain.service.DomainErrors;

import java.util.Set;

/**
 * 密码强度策略
 *
 * 遵循 NIST SP 800-63B 的取向：以长度为主要强度来源，不强制字符组成规则。
 * 强制"必须含大写+数字+符号"在实践中把用户推向 {@code Password1!} 这类
 * 可预测的变形，反而缩小了搜索空间。
 *
 * 上限 72 字节是 BCrypt 的硬限制：超出部分会被静默截断，
 * 那意味着两个不同的长密码可能等价。这里显式拒绝而不是让它悄悄发生。
 */
public final class PasswordPolicy {
    /** NIST 建议的最小长度下限是 8，这里取 10 作为面向消费级产品的折中 */
    public static final int MIN_LENGTH = 10;

    /** BCrypt 只处理前 72 字节，超出部分被截断 */
    public static final int MAX_BYTES = 72;

    /**
     * 最常见的弱密码。
     *
     * 真实部署应接入完整的泄漏口令库（如 Have I Been Pwned 的 k-anonymity API）。
     * 这里内置一个最小集合，让"弱密码会被拒绝"这件事在没有外部依赖时也成立，
     * 而不是留一个待办然后放行一切。
     */
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "password123", "12345678", "123456789",
            "1234567890", "qwertyuiop", "qwerty123", "abc12345", "iloveyou",
            "admin123", "letmein123", "welcome123", "monkey123", "dragon123",
            "passw0rd", "p@ssword", "p@ssw0rd", "administrator", "changeme"
    );

    private PasswordPolicy() {
    }

    /**
     * 校验密码是否满足策略，不满足则抛出校验异常。
     *
     * @param password 明文密码，仅在本方法内使用，不记录也不返回
     * @param username 用户名，用于拒绝"密码等于用户名"这类必然被首先尝试的组合
     */
    public static void validate(String password, String username) {
        if (password == null || password.isBlank()) {
            throw DomainErrors.validation("密码不能为空");
        }
        // 按字节而非字符计数：中文密码每字符 3 字节，10 个汉字就会超过 72
        int byteLength = password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (password.length() < MIN_LENGTH) {
            throw DomainErrors.validation("密码至少需要 " + MIN_LENGTH + " 个字符");
        }
        if (byteLength > MAX_BYTES) {
            throw DomainErrors.validation("密码过长，请控制在 " + MAX_BYTES + " 字节以内");
        }
        String normalized = password.toLowerCase();
        if (COMMON_PASSWORDS.contains(normalized)) {
            throw DomainErrors.validation("该密码过于常见，请更换");
        }
        if (username != null && !username.isBlank()
                && normalized.contains(username.toLowerCase())) {
            throw DomainErrors.validation("密码不能包含用户名");
        }
        // 单一重复字符（aaaaaaaaaa）长度达标但强度为零
        if (normalized.chars().distinct().count() < 4) {
            throw DomainErrors.validation("密码需要包含至少 4 种不同字符");
        }
    }
}
