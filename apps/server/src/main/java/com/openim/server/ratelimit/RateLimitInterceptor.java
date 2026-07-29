package com.openim.server.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openim.server.auth.AuthPrincipal;
import com.openim.server.observability.DomainMetrics;
import com.openim.server.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * HTTP 限流拦截器
 *
 * 注册在认证拦截器之后：已认证请求按 userId 计量，未认证请求（登录、刷新）按客户端 IP 计量。
 * 按用户而不是按 IP 计量已认证请求，是为了避免同一出口 IP 下的多个正常用户相互影响。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final DomainMetrics metrics;

    public RateLimitInterceptor(
            RateLimiter rateLimiter,
            ObjectMapper objectMapper,
            DomainMetrics metrics
    ) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
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

        RateLimitPolicy policy = policyFor(request);
        if (policy == null) {
            return true;
        }

        String identifier = identifierFor(request);
        RateLimiter.Decision decision = rateLimiter.tryConsume(policy, identifier);
        if (decision.allowed()) {
            return true;
        }

        metrics.recordRateLimitRejection();
        // 只记录限流键的类别，不记录完整 IP（见 SECURITY.md §9 对日志的要求）
        log.warn("Rate limit exceeded path={} method={}",
                request.getRequestURI(), request.getMethod());
        writeTooManyRequests(request, response, decision);
        return false;
    }

    /**
     * 选择策略。
     *
     * 返回 null 表示该请求不限流（健康检查、静态资源等）。
     */
    private RateLimitPolicy policyFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.startsWith("/api/v1/auth/demo-login")) {
            return RateLimitPolicy.LOGIN;
        }
        if (path.startsWith("/api/v1/auth/refresh")) {
            return RateLimitPolicy.TOKEN_REFRESH;
        }
        if (path.startsWith("/api/v1/red-packets")) {
            if ("POST".equals(method)) {
                return path.endsWith("/claim")
                        ? RateLimitPolicy.CLAIM_RED_PACKET
                        : RateLimitPolicy.CREATE_RED_PACKET;
            }
            return RateLimitPolicy.DEFAULT_API;
        }
        if (path.startsWith("/api/v1/calls")) {
            // 仅对创建通话限流；接听/拒绝/离开是对既有邀请的响应，限流会伤害正常使用
            return "POST".equals(method) && "/api/v1/calls".equals(path)
                    ? RateLimitPolicy.CREATE_CALL
                    : RateLimitPolicy.DEFAULT_API;
        }
        if (path.startsWith("/api/v1/")) {
            return RateLimitPolicy.DEFAULT_API;
        }
        return null;
    }

    private String identifierFor(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        if (auth != null) {
            return "user:" + auth.userId();
        }
        return "ip:" + clientIp(request);
    }

    /**
     * 客户端 IP。
     *
     * 生产环境启用了 {@code forward-headers-strategy: framework}，
     * Spring 会依据受信任的代理头还原真实来源，因此这里直接取 remoteAddr。
     * 不要在没有可信代理的前提下自行解析 X-Forwarded-For——那个头可由客户端伪造，
     * 会让攻击者通过伪造 IP 绕过限流。
     */
    private String clientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null ? "unknown" : remoteAddr;
    }

    private void writeTooManyRequests(
            HttpServletRequest request,
            HttpServletResponse response,
            RateLimiter.Decision decision
    ) {
        try {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));

            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode error = root.putObject("error");
            error.put("code", "RATE_LIMITED");
            error.put("message", "操作过于频繁，请稍后再试");
            Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
            root.put("requestId", requestId instanceof String id ? id : "unknown");
            objectMapper.writeValue(response.getWriter(), root);
        } catch (Exception ignored) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }
}
