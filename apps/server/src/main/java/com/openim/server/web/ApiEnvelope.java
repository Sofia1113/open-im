package com.openim.server.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * API 响应信封
 * 统一包裹成功和失败响应，包含 data/error 和 requestId 字段。
 */
public final class ApiEnvelope {
    private ApiEnvelope() {
    }

    /** 成功响应体 */
    public record Success<T>(T data, String requestId) {
    }

    /** 错误详情 */
    public record ErrorBody(String code, String message, Object details) {
    }

    /** 失败响应体 */
    public record Failure(ErrorBody error, String requestId) {
    }

    /** 构建成功响应，自动注入当前请求的 requestId */
    public static <T> Success<T> success(T data, HttpServletRequest request) {
        return new Success<>(data, requestId(request));
    }

    /** 从请求属性中获取 requestId */
    public static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value instanceof String id ? id : "unknown";
    }
}
