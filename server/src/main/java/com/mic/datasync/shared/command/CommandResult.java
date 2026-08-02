package com.mic.datasync.shared.command;

/**
 * 统一运行命令响应：启动、暂停、继续、重试。
 */
public record CommandResult(
        boolean accepted,
        String resourceId,
        String status,
        String message) {

    public static CommandResult accepted(String resourceId, String status, String message) {
        return new CommandResult(true, resourceId, status, message);
    }
}
