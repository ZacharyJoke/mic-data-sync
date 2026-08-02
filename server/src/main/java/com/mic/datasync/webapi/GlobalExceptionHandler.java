package com.mic.datasync.webapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/**
 * 全局异常兜底处理。
 *
 * <p>捕获所有未处理的异常，统一返回 500 + {@link ApiError}，
 * 并将原始异常记录到服务端日志，方便排查问题。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiRequestException.class)
    public ResponseEntity<ApiError> handleApiRequestException(ApiRequestException ex) {
        return ResponseEntity
                .badRequest()
                .body(new ApiError(
                        ex.code(),
                        ex.getMessage(),
                        UUID.randomUUID().toString(),
                        Map.of("field", ex.field())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleException(Exception ex) {
        // 完整异常仅记录在服务端日志，不暴露给客户端
        log.error("未处理的系统异常", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ex));
    }
}
