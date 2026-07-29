package com.openim.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openim.server.domain.repository.DeviceRepository;
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
 *
 * 从 Authorization 请求头中提取 Bearer Token，验证签名与声明，
 * 再确认账号状态和设备授权仍然有效，最后把认证信息注入请求属性。
 *
 * 每个请求都查一次设备状态，使远程下线和令牌重放撤销能立即生效，
 * 而不是等访问令牌自然过期——这是"能撤销"与"只能等过期"的区别。
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;
    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;

    public AuthenticationInterceptor(
            TokenService tokenService,
            DeviceRepository deviceRepository,
            ObjectMapper objectMapper
    ) {
        this.tokenService = tokenService;
        this.deviceRepository = deviceRepository;
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
        // 令牌有效不代表会话仍然有效：账号被封禁或设备被撤销都应立即拒绝
        UUID userId = parseUserId(payload.userId());
        if (userId == null || !deviceRepository.isSessionActive(payload.deviceId(), userId)) {
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
