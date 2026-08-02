package com.mic.datasync.endpoint;

import com.mic.datasync.database.DatabaseRole;

import java.time.Instant;

/**
 * 端注册记录。
 *
 * @param id            端 ID
 * @param name          显示名称
 * @param role          角色（v1：Source 固定自身，Sink 可多个）
 * @param baseUrl       访问地址（远程端必填；自身端可为空）
 * @param instanceId    探活回填的实例 ID
 * @param sinkToken       该 Sink 端的访问令牌明文（仅内存使用；响应不输出）
 * @param isSelf        是否当前实例（self-source / self-sink）
 * @param status        READY / NOT_READY / UNREACHABLE / UNKNOWN
 * @param lastProbeAt   最近探活时间
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 */
public record EndpointRecord(
        String id,
        String name,
        DatabaseRole role,
        String baseUrl,
        String instanceId,
        String sinkToken,
        boolean isSelf,
        String status,
        Instant lastProbeAt,
        Instant createdAt,
        Instant updatedAt) {
}
