package com.openim.server.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * 统一捕获 ApiException（业务异常）、参数校验失败和未预期异常，
 * 返回标准化的 JSON 错误响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 处理业务异常 {@link ApiException}：透传其 HTTP 状态码、业务错误码与详细信息 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiEnvelope.Failure> handleApiException(
            ApiException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(exception.status()).body(new ApiEnvelope.Failure(
                new ApiEnvelope.ErrorBody(
                        exception.code(),
                        exception.getMessage(),
                        exception.details()
                ),
                ApiEnvelope.requestId(request)
        ));
    }

    /** 处理参数校验失败：返回 400 VALIDATION_ERROR，details 为字段名到错误消息的映射 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiEnvelope.Failure> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(new ApiEnvelope.Failure(
                new ApiEnvelope.ErrorBody(
                        "VALIDATION_ERROR",
                        "请求参数不合法",
                        details
                ),
                ApiEnvelope.requestId(request)
        ));
    }

    /** 兜底处理未预期异常：记录错误日志并返回 500 INTERNAL_ERROR，不向客户端暴露异常细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope.Failure> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error("Unhandled request error requestId={}", ApiEnvelope.requestId(request), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ApiEnvelope.Failure(
                        new ApiEnvelope.ErrorBody(
                                "INTERNAL_ERROR",
                                "服务暂时不可用",
                                null
                        ),
                        ApiEnvelope.requestId(request)
                )
        );
    }
}
