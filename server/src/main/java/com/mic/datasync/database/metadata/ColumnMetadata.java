package com.mic.datasync.database.metadata;

/**
 * 字段元数据。
 *
 * @param name        字段名
 * @param jdbcType    JDBC 类型（java.sql.Types 常量）
 * @param typeName    数据库类型名（如 varchar、bigint）
 * @param size        长度/精度（无意义时为 0）
 * @param nullable    是否允许 NULL
 * @param primaryKey  是否主键字段
 */
public record ColumnMetadata(
        String name,
        int jdbcType,
        String typeName,
        int size,
        boolean nullable,
        boolean primaryKey) {
}
