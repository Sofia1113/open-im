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

    /**
     * 限流入口。
     *
     * CORS 预检请求直接放行（预检不携带业务意图，不应计入配额）；
     * 没有对应策略的路径（健康检查等）同样放行。配额耗尽时记录拒绝指标、
     * 写入 429 响应并返回 false 终止后续处理。
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

        if (path.startsWith("/api/v1/auth/login")
                || path.startsWith("/api/v1/auth/demo-login")) {
            return RateLimitPolicy.LOGIN;
        }
        if (path.startsWith("/api/v1/auth/register")) {
            return RateLimitPolicy.REGISTER;
        }
        if (path.startsWith("/api/v1/auth/refresh")) {
            return RateLimitPolicy.TOKEN_REFRESH;
        }
        // 密码重置的两个环节配额不同：申请会触发发邮件（可被用来骚扰任意邮箱），
        // 提交令牌只是一次哈希查找。必须放在 /password 的通用判断之前，
        // 否则 password/reset 会先匹配到下面的 CHANGE_PASSWORD。
        if (path.startsWith("/api/v1/auth/password/reset")) {
            return RateLimitPolicy.PASSWORD_RESET_CONFIRM;
        }
        if (path.startsWith("/api/v1/auth/password/forgot")) {
            return RateLimitPolicy.PASSWORD_RESET_REQUEST;
        }
        // 改密需要当前密码，因此它同样是一个密码尝试入口，必须单独限流。
        // 已认证请求按 userId 计量，攻击者无法通过换 IP 绕过。
        if (path.startsWith("/api/v1/sessions/password")) {
            return RateLimitPolicy.CHANGE_PASSWORD;
        }
        // 注销同样要验证当前密码，是一个密码尝试入口；且它不可逆，
        // 配额比改密更紧。复用 CHANGE_PASSWORD 会让两者共享配额——
        // 那意味着有人反复尝试注销就能耗尽正常用户改密的配额。
        if (path.startsWith("/api/v1/account")) {
            return RateLimitPolicy.DELETE_ACCOUNT;
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
        // 用户名查找单独限流：接口要求完整用户名，单次请求泄露不了什么，
        // 但不限速的话可以用字典枚举出哪些用户名存在——那等于把已经移除的
        // 全站用户列表还回去了。配额是可见性边界的一部分，不只是防滥用。
        if (path.startsWith("/api/v1/users/search")) {
            return RateLimitPolicy.USER_SEARCH;
        }
        if (path.startsWith("/api/v1/conversations")) {
            // 创建单聊/群聊的配额单独且更紧：创建出来的会话没有删除接口，
            // 被刷出来的垃圾会永久留在对端的列表里。
            if ("/api/v1/conversations/direct".equals(path)
                    || "/api/v1/conversations/group".equals(path)) {
                return RateLimitPolicy.CREATE_CONVERSATION;
            }
            // 已读上报虽然是 POST，但它的调用频率与读操作同级——每看一批消息就
            // 上报一次。按成员管理的配额（30/分钟）限它会在正常聊天中被误伤。
            if (path.endsWith("/read")) {
                return RateLimitPolicy.DEFAULT_API;
            }
            // 成员与资料的写操作。读操作（会话列表、消息历史、成员列表）
            // 走默认配额——它们是每次打开界面都要调用的路径。
            if ("POST".equals(method) || "DELETE".equals(method) || "PATCH".equals(method)) {
                return RateLimitPolicy.CONVERSATION_ADMIN;
            }
            return RateLimitPolicy.DEFAULT_API;
        }
        if (path.startsWith("/api/v1/")) {
            return RateLimitPolicy.DEFAULT_API;
        }
        return null;
    }

    /** 已认证请求按 userId 计量（避免同一出口 IP 下的多个用户相互影响），未认证请求按客户端 IP 计量 */
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

    /**
     * 写入 429 响应。
     *
     * 固定 JSON 错误体（含请求 ID 便于排障），并带 Retry-After 头告知客户端
     * 何时可以重试。写响应本身失败时退化为只设置状态码，保证请求一定被拒绝。
     */
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
