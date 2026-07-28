package com.openim.server.config;

import com.openim.server.realtime.RealtimeWebSocketHandler;
import com.openim.server.realtime.WebSocketAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

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

    public WebSocketConfig(
            RealtimeWebSocketHandler handler,
            WebSocketAuthenticationInterceptor authenticationInterceptor,
            WebMvcConfig webMvcConfig
    ) {
        this.handler = handler;
        this.authenticationInterceptor = authenticationInterceptor;
        this.webMvcConfig = webMvcConfig;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .addInterceptors(authenticationInterceptor)
                .setAllowedOrigins(webMvcConfig.allowedOrigins());
    }
}
