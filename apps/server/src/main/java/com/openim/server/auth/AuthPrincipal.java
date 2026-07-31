package com.openim.server.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * 认证主体记录
 * 从请求属性中提取已认证用户的 userId 和 deviceId，
 * 供 Controller 层便捷获取当前用户信息。
 */
public record AuthPrincipal(String userId, String deviceId) {
    public static final String ATTRIBUTE = "openim.auth";

    /** 从请求属性中取出认证主体；未通过鉴权的请求返回 null */
    public static AuthPrincipal from(HttpServletRequest request) {
        return (AuthPrincipal) request.getAttribute(ATTRIBUTE);
    }

    /**
     * 令牌中的用户标识。
     *
     * 令牌由服务端签发，userId 必然是合法 UUID；解析失败说明签名校验存在缺陷，
     * 此时应作为内部错误暴露而不是静默降级为"用户不存在"。
     */
    public UUID userUuid() {
        return UUID.fromString(userId);
    }
}
