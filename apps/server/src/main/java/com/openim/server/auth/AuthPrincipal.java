package com.openim.server.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 认证主体记录
 * 从请求属性中提取已认证用户的 userId 和 deviceId，
 * 供 Controller 层便捷获取当前用户信息。
 */
public record AuthPrincipal(String userId, String deviceId) {
    public static final String ATTRIBUTE = "openim.auth";

    public static AuthPrincipal from(HttpServletRequest request) {
        return (AuthPrincipal) request.getAttribute(ATTRIBUTE);
    }
}
