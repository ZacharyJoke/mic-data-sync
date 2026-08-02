package com.mic.datasync.webapi;

import com.mic.datasync.shared.error.ErrorCode;

import java.util.Map;
import java.util.UUID;

/**
 * 统一错误响应 DTO。
 *
 * <p>所有未被业务代码捕获的异常最终都会以该结构返回给客户端，
 * 不暴露任何异常堆栈或内部实现细节。</p>
 *
 * @param code      错误码
 * @param message   面向用户的错误描述
 * @param requestId 请求追踪 ID（随机 UUID，用于日志关联）
 * @param details   附加错误详情（默认空对象）
 */
public record ApiError(
        String code,
        String message,
        String requestId,
        Map<String, Object> details
) {

    /**
     * 构造一个通用的内部错误响应。
     *
     * @param ex 触发错误的异常（仅用于日志，不对外暴露）
     * @return 统一错误响应
     */
    public static ApiError of(Throwable ex) {
        return new ApiError(
                "INTERNAL_ERROR",
                "系统内部错误",
                UUID.randomUUID().toString(),
                Map.of()
        );
    }

    /**
     * 构造一个公开错误码对应的错误响应。
     *
     * @param code 公开错误码（与 {@code docs/help/error-codes.md} 一一对应）
     * @return 统一错误响应
     */
    public static ApiError of(ErrorCode code) {
        return new ApiError(code.name(), code.message(), UUID.randomUUID().toString(), Map.of());
    }
}
