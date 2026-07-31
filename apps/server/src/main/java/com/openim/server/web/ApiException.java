package com.openim.server.web;

import org.springframework.http.HttpStatus;

/**
 * API 业务异常
 * 携带 HTTP 状态码、业务错误码和可选详细信息，
 * 由 GlobalExceptionHandler 统一捕获并返回标准错误响应。
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Object details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    /** 异常对应的 HTTP 状态码 */
    public HttpStatus status() {
        return status;
    }

    /** 业务错误码 */
    public String code() {
        return code;
    }

    /** 可选详细信息 */
    public Object details() {
        return details;
    }
}
