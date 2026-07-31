package com.openim.server.web;

import com.openim.server.config.ProductionProfile;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安全响应头
 *
 * 对应 docs/02-features/SECURITY.md §5 的要求。这里设置的是 API 响应头；
 * Web 客户端由 CDN/静态服务器托管时，同一套策略需要在那一侧再设置一遍——
 * 浏览器只认真正返回 HTML 的那个响应上的 CSP。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {
    /**
     * API 只返回 JSON，因此可以用最严格的 CSP：不允许加载任何资源，
     * 也不允许被嵌入到框架中。即使某个端点意外返回了 HTML，脚本也无法执行。
     */
    private static final String API_CONTENT_SECURITY_POLICY =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    /** 关闭本服务用不到的强力设备权限，缩小被利用的面 */
    private static final String PERMISSIONS_POLICY =
            "accelerometer=(), geolocation=(), gyroscope=(), magnetometer=(), payment=(), usb=()";

    private final boolean production;

    public SecurityHeadersFilter(ProductionProfile productionProfile) {
        this.production = productionProfile.active();
    }

    /** 为所有响应设置安全响应头；仅生产环境额外下发 HSTS */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", API_CONTENT_SECURITY_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        // 令牌等敏感响应不应被中间代理或浏览器缓存
        response.setHeader("Cache-Control", "no-store");

        if (production) {
            // 仅在生产启用 HSTS：本地开发走 HTTP，一旦下发会把 localhost 永久锁到 HTTPS，
            // 且该状态会残留在开发者浏览器中难以清除
            response.setHeader("Strict-Transport-Security",
                    "max-age=31536000; includeSubDomains");
        }

        filterChain.doFilter(request, response);
    }
}
