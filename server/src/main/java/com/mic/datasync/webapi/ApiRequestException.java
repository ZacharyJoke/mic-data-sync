package com.mic.datasync.webapi;

/**
 * 可安全返回给客户端的请求参数异常。
 */
public class ApiRequestException extends RuntimeException {

    private final String code;
    private final String field;

    public ApiRequestException(String code, String message, String field) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }
}
