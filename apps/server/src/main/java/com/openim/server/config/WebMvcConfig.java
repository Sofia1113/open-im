package com.openim.server.config;

import com.openim.server.auth.AuthenticationInterceptor;
import com.openim.server.ratelimit.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Web MVC 配置
 * - 配置 CORS 允许跨域请求
 * - 注册认证拦截器，保护 /api/v1/** 接口（除 /api/v1/auth/**）
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final AuthenticationInterceptor authenticationInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final String[] allowedOrigins;

    public WebMvcConfig(
            AuthenticationInterceptor authenticationInterceptor,
            RateLimitInterceptor rateLimitInterceptor,
            @Value("${openim.client-origin}") String clientOrigin,
            ProductionProfile productionProfile
    ) {
        this.authenticationInterceptor = authenticationInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        boolean productionMode = productionProfile.active();
        if (productionMode && !clientOrigin.startsWith("https://")) {
            throw new IllegalStateException("CLIENT_ORIGIN must use HTTPS in production");
        }
        Set<String> origins = new LinkedHashSet<>();
        origins.add(clientOrigin);
        if (!productionMode) {
            origins.add("http://localhost:5173");
            origins.add("http://127.0.0.1:5173");
        }
        this.allowedOrigins = origins.toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Request-Id")
                .exposedHeaders("X-Request-Id")
                .allowCredentials(true);
    }

    /**
     * 拦截器顺序有意如此：认证在前，限流在后。
     *
     * 这样已认证请求可以按 userId 计量配额，避免同一出口 IP 下的多个正常用户相互影响；
     * 登录和刷新被排除在认证之外，限流拦截器拿不到身份时自动退回按 IP 计量。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/**");
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/**");
    }

    public String[] allowedOrigins() {
        return allowedOrigins.clone();
    }
}
