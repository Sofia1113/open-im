package com.openim.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openim.server.domain.InMemoryStore;
import com.openim.server.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器
 * 从 Authorization 请求头中提取 Bearer Token，验证其有效性，
 * 并将认证信息注入请求属性供后续使用。
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;
    private final InMemoryStore store;
    private final ObjectMapper objectMapper;

    public AuthenticationInterceptor(
            TokenService tokenService,
            InMemoryStore store,
            ObjectMapper objectMapper
    ) {
        this.tokenService = tokenService;
        this.store = store;
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
        try {
            store.getUser(payload.userId());
        } catch (RuntimeException ignored) {
            writeUnauthorized(request, response);
            return false;
        }
        request.setAttribute(
                AuthPrincipal.ATTRIBUTE,
                new AuthPrincipal(payload.userId(), payload.deviceId())
        );
        return true;
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
