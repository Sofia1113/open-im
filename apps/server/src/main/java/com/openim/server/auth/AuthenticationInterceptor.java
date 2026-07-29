package com.openim.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openim.server.domain.service.UserService;
import com.openim.server.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * 认证拦截器
 * 从 Authorization 请求头中提取 Bearer Token，验证其有效性，
 * 并将认证信息注入请求属性供后续使用。
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public AuthenticationInterceptor(
            TokenService tokenService,
            UserService userService,
            ObjectMapper objectMapper
    ) {
        this.tokenService = tokenService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7)
                : "";
        TokenService.TokenPayload payload = tokenService.verify(token, "access").orElse(null);
        if (payload == null) {
            writeUnauthorized(request, response);
            return false;
        }
        // 令牌有效不代表账号仍然可用：封禁或注销后应立即拒绝，而不是等令牌自然过期
        UUID userId = parseUserId(payload.userId());
        if (userId == null || !userService.isActiveUser(userId)) {
            writeUnauthorized(request, response);
            return false;
        }
        request.setAttribute(
                AuthPrincipal.ATTRIBUTE,
                new AuthPrincipal(payload.userId(), payload.deviceId())
        );
        return true;
    }

    private static UUID parseUserId(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void writeUnauthorized(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        try {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode error = root.putObject("error");
            error.put("code", "AUTH_REQUIRED");
            error.put("message", "请先登录");
            Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
            root.put("requestId", requestId instanceof String id ? id : "unknown");
            objectMapper.writeValue(response.getWriter(), root);
        } catch (Exception ignored) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        }
    }
}
