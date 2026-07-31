package com.openim.server.realtime;

import com.openim.server.auth.TokenService;
import com.openim.server.domain.repository.DeviceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.UUID;

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
    private final DeviceRepository deviceRepository;

    public WebSocketAuthenticationInterceptor(
            TokenService tokenService,
            DeviceRepository deviceRepository
    ) {
        this.tokenService = tokenService;
        this.deviceRepository = deviceRepository;
    }

    /**
     * 握手鉴权：校验 token、deviceId 与设备会话有效性，通过后把用户信息
     * 注入会话属性。
     *
     * 任一环节失败（token 无效/过期、deviceId 与 token 不匹配、设备会话
     * 已失效）都返回 false 并以 401 拒绝握手。WebSocket 没有重试会话，
     * 握手是唯一的一次性鉴权入口，必须在建立连接之前把住。
     */
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
        UUID userId;
        try {
            userId = UUID.fromString(payload.userId());
        } catch (IllegalArgumentException ignored) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        if (!deviceRepository.isSessionActive(deviceId, userId)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(USER_ID, payload.userId());
        attributes.put(DEVICE_ID, payload.deviceId());
        return true;
    }

    /** 握手完成后的钩子：无需额外处理，鉴权结果已在 {@link #beforeHandshake} 写入会话属性 */
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
