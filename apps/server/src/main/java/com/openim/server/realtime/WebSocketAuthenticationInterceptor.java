package com.openim.server.realtime;

import com.openim.server.auth.TokenService;
import com.openim.server.domain.InMemoryStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WebSocket 握手认证拦截器
 * 从查询参数中提取 token 和 deviceId 进行验证，
 * 通过后将用户信息注入会话属性。
 */
@Component
public class WebSocketAuthenticationInterceptor implements HandshakeInterceptor {
    public static final String USER_ID = "openim.userId";
    public static final String DEVICE_ID = "openim.deviceId";

    private final TokenService tokenService;
    private final InMemoryStore store;

    public WebSocketAuthenticationInterceptor(TokenService tokenService, InMemoryStore store) {
        this.tokenService = tokenService;
        this.store = store;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        MultiValueMap<String, String> query = UriComponentsBuilder
                .fromUri(request.getURI())
                .build()
                .getQueryParams();
        String token = query.getFirst("token");
        String deviceId = query.getFirst("deviceId");
        TokenService.TokenPayload payload = tokenService.verify(token, "access").orElse(null);
        if (payload == null || deviceId == null || !deviceId.equals(payload.deviceId())) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            store.getUser(payload.userId());
        } catch (RuntimeException ignored) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(USER_ID, payload.userId());
        attributes.put(DEVICE_ID, payload.deviceId());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // No post-handshake action is required.
    }
}
