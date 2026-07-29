package com.openim.server.config;

import com.openim.server.realtime.RealtimeWebSocketHandler;
import com.openim.server.realtime.WebSocketAuthenticationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 配置
 * 注册 /ws 端点，挂载认证拦截器和实时消息处理器。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RealtimeWebSocketHandler handler;
    private final WebSocketAuthenticationInterceptor authenticationInterceptor;
    private final WebMvcConfig webMvcConfig;
    private final int maxTextMessageSize;
    private final long idleTimeoutMillis;

    public WebSocketConfig(
            RealtimeWebSocketHandler handler,
            WebSocketAuthenticationInterceptor authenticationInterceptor,
            WebMvcConfig webMvcConfig,
            @Value("${openim.realtime.max-text-message-size:65536}") int maxTextMessageSize,
            @Value("${openim.realtime.idle-timeout-millis:120000}") long idleTimeoutMillis
    ) {
        this.handler = handler;
        this.authenticationInterceptor = authenticationInterceptor;
        this.webMvcConfig = webMvcConfig;
        this.maxTextMessageSize = maxTextMessageSize;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    /**
     * 容器级别的连接限制。
     *
     * 此前未配置该 Bean，容器沿用 Tomcat 默认的 8KB 文本缓冲区，
     * 而业务层却按 1MB 校验信令载荷——超过 8KB 的 SDP 在到达业务校验之前
     * 就被容器判定为过大并断开连接，那条校验实际上是失效的。
     * 这里把容器上限提到 64KB，业务层的信令上限收紧到远小于它的值，
     * 使"过大"由业务层用明确的错误码拒绝，而不是以断连的形式表现。
     *
     * 空闲超时兜底清理半开连接：对端异常掉线时 TCP 可能长时间不返回 FIN，
     * 应用层心跳先发现，容器超时作为第二道防线。
     *
     * 该 Bean 需要真实的 Servlet 容器提供 {@code ServerContainer}，
     * 在 MockMvc 测试环境中不存在，因此由属性开关在测试里关闭。
     */
    @Bean
    @ConditionalOnProperty(
            name = "openim.realtime.container-config-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(maxTextMessageSize);
        container.setMaxBinaryMessageBufferSize(maxTextMessageSize);
        container.setMaxSessionIdleTimeout(idleTimeoutMillis);
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .addInterceptors(authenticationInterceptor)
                .setAllowedOrigins(webMvcConfig.allowedOrigins());
    }
}
