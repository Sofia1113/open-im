package com.openim.server.domain.service;

import com.openim.server.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * 领域异常与 ID 解析工具
 *
 * 错误码是客户端已依赖的契约（见 docs/01-architecture/API_AND_EVENTS.md），
 * 迁移到数据库存储后必须保持不变。
 */
public final class DomainErrors {
    private DomainErrors() {
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    /**
     * 未认证。
     *
     * 统一使用 AUTH_REQUIRED 错误码：客户端据此触发刷新或跳转登录，
     * 且不向调用方区分"签名无效"与"会话已撤销"，避免泄漏鉴权内部状态。
     */
    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", message);
    }

    /**
     * 把外部传入的 ID 字符串解析为 UUID。
     *
     * 格式非法一律按"资源不存在"处理，而不是返回参数错误：避免攻击者通过区分
     * 400 与 404 来探测哪些 ID 格式是系统内部使用的。
     */
    public static UUID parseId(String value, String resourceName) {
        if (value == null || value.isBlank()) {
            throw notFound(resourceName + "不存在");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            throw notFound(resourceName + "不存在");
        }
    }
}
