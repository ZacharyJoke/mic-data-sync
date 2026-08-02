package com.mic.datasync.source;

/**
 * JDBC 类型 → 逻辑类型映射（规范化与类型兼容校验共用）。
 */
public final class LogicalTypeMapper {

    private LogicalTypeMapper() {
    }

    /** 按 JDBC 类型映射逻辑类型。 */
    public static String fromJdbcType(int jdbcType) {
        return switch (jdbcType) {
            case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> "DECIMAL";
            case java.sql.Types.VARCHAR, java.sql.Types.CHAR, java.sql.Types.NVARCHAR,
                 java.sql.Types.NCHAR, java.sql.Types.LONGVARCHAR, java.sql.Types.LONGNVARCHAR -> "STRING";
            case java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> "DATETIME";
            case java.sql.Types.DATE -> "DATE";
            case java.sql.Types.TIME, java.sql.Types.TIME_WITH_TIMEZONE -> "TIME";
            case java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER,
                 java.sql.Types.BIGINT -> "INTEGER";
            case java.sql.Types.FLOAT, java.sql.Types.DOUBLE, java.sql.Types.REAL -> "FLOAT";
            case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> "BOOLEAN";
            case java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY,
                 java.sql.Types.BLOB -> "BINARY";
            case java.sql.Types.ARRAY -> "ARRAY";
            case java.sql.Types.SQLXML -> "XML";
            default -> "OTHER";
        };
    }
}
