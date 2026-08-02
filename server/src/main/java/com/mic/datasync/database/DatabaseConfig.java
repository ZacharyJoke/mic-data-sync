package com.mic.datasync.database;

import java.time.Instant;

/**
 * 已保存的数据库连接配置（密码为解密后的明文，仅在内存中使用）。
 *
 * @param id           数据源档案 ID
 * @param endpointId   所属端注册 ID（sync_endpoint）
 * @param name         数据源显示名称
 * @param role         角色（SOURCE/SINK）
 * @param databaseType 数据库类型
 * @param jdbcUrl      JDBC 连接串
 * @param username     用户名
 * @param password     密码（解密后）
 * @param driverType   驱动类型（对应内置驱动）
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 */
public record DatabaseConfig(
        String id,
        String endpointId,
        String name,
        DatabaseRole role,
        DatabaseType databaseType,
        String jdbcUrl,
        String username,
        String password,
        String driverType,
        Instant createdAt,
        Instant updatedAt) {
}
