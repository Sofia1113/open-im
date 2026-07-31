package com.openim.server.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 每个请求都检查设备状态，使远程下线和令牌重放撤销能快速生效，
 * 而不是等访问令牌自然过期——这是"能撤销"与"只能等过期"的区别。
 *
 * 该检查走 {@link SessionValidityCache} 而非直接查库：此前每个请求都打一次
 * 数据库，在 20 连接的 Hikari 池下会先于任何业务逻辑成为瓶颈。缓存配合
 * 撤销时的主动失效广播，把"立即生效"放宽为"一次网络往返内生效，
 * Redis 故障时最坏 30 秒"，代价见 {@link SessionValidityCache} 的类注释。
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;
    private final SessionValidityCache sessionValidityCache;
    private final ObjectMapper objectMapper;

    public AuthenticationInterceptor(
            TokenService tokenService,
            SessionValidityCache sessionValidityCache,
            ObjectMapper objectMapper
    ) {
        this.tokenService = tokenService;
        this.sessionValidityCache = sessionValidityCache;
        this.objectMapper = objectMapper;
    }

    /**
     * 鉴权入口：校验令牌签名、账号状态与设备授权，通过后把认证主体注入请求属性。
     * 任一环节不通过都返回统一的 AUTH_REQUIRED 401，不区分失败原因。
     */
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
        // 令牌有效不代表会话仍然有效：账号被封禁或设备被撤销都应拒绝
        UUID userId = parseUserId(payload.userId());
        if (userId == null || !sessionValidityCache.isSessionActive(payload.deviceId(), userId)) {
            writeUnauthorized(request, response);
            return false;
        }
        request.setAttribute(
                AuthPrincipal.ATTRIBUTE,
                new AuthPrincipal(payload.userId(), payload.deviceId())
        );
        return true;
    }

    /** 解析令牌中的用户 ID；缺失或非法时返回 null，按未认证处理 */
    private static UUID parseUserId(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 写统一的 AUTH_REQUIRED 401 响应；写响应失败时只保证状态码，不抛出 */
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
