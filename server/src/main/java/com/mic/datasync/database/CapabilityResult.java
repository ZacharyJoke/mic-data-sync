package com.mic.datasync.database;

import java.util.List;

/**
 * 统一能力探查结果。
 *
 * @param status      就绪状态
 * @param errorCode   稳定错误码（BLOCKED 时必填，对应公开错误码）
 * @param message     面向用户说明
 * @param suggestions 用户可执行建议（可多条）
 */
public record CapabilityResult(Status status, String errorCode, String message, List<String> suggestions) {

    public enum Status {
        READY,
        BLOCKED
    }

    public static CapabilityResult ready() {
        return new CapabilityResult(Status.READY, null, "能力就绪", List.of());
    }

    public static CapabilityResult blocked(String errorCode, String message, List<String> suggestions) {
        return new CapabilityResult(Status.BLOCKED, errorCode, message,
                suggestions == null ? List.of() : List.copyOf(suggestions));
    }
}
