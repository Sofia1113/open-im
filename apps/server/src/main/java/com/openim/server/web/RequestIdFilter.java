package com.openim.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求 ID 过滤器
 *
 * 为每个 HTTP 请求分配唯一的 requestId（优先使用客户端传入的 X-Request-Id），
 * 注入请求属性、响应头和日志 MDC。
 *
 * 放入 MDC 是关键一步：此前 requestId 只存在于请求属性和响应头中，
 * 日志里并不带它，"客户端报错 → 找到对应服务端日志"这条链路实际上断的。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_ATTRIBUTE = "openim.requestId";
    private static final String MDC_KEY = "requestId";
    private static final int MAX_REQUEST_ID_LENGTH = 128;

    /** 生成或规范化请求 ID，注入请求属性、响应头与日志 MDC，链路结束后清理 MDC */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = sanitize(request.getHeader("X-Request-Id"));
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);

        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理：线程会被后续请求复用，残留的 requestId 会把日志关联到错误的请求上
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 规范化客户端传入的请求 ID。
     *
     * 客户端可以自带 ID 以便端到端关联，但该值会进入日志，
     * 因此限制长度并只保留安全字符，避免换行注入伪造日志行。
     */
    private String sanitize(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > MAX_REQUEST_ID_LENGTH) {
            return UUID.randomUUID().toString();
        }
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            boolean allowed = Character.isLetterOrDigit(character)
                    || character == '-' || character == '_' || character == '.';
            if (!allowed) {
                return UUID.randomUUID().toString();
            }
        }
        return candidate;
    }
}
