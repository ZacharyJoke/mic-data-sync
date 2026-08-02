package com.mic.datasync.source.sql;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 结果字段探查器。
 *
 * <p>使用「零行包装查询」（{@code SELECT * FROM (<sql>) mic_sync_sub WHERE 1 = 0}）
 * 通过 {@link PreparedStatement#getMetaData()} 获取结果列，不执行任何业务数据；
 * 以 {@code columnLabel} 作为字段名，并固化 JDBC 类型、逻辑类型、可空性与结构指纹。</p>
 */
@Component
public class SqlMetadataInspector {

    /** 探查结果。 */
    public record InspectionResult(
            List<ResultColumn> columns,
            List<String> duplicateNames,
            String structureFingerprint) {

        public InspectionResult {
            columns = columns == null ? List.of() : List.copyOf(columns);
            duplicateNames = duplicateNames == null ? List.of() : List.copyOf(duplicateNames);
        }

        public boolean hasDuplicates() {
            return !duplicateNames.isEmpty();
        }
    }

    /** 结果字段（columnLabel 为字段名）。 */
    public record ResultColumn(
            String name,
            int jdbcType,
            String typeName,
            String logicalType,
            boolean nullable) {
    }

    /** 探查 SQL 结果字段。 */
    public InspectionResult inspect(Connection connection, String sql) throws SQLException {
        String wrapped = "SELECT * FROM (" + sql + ") mic_sync_sub WHERE 1 = 0";
        List<ResultColumn> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(wrapped)) {
            ResultSetMetaData meta = statement.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String label = meta.getColumnLabel(i);
                int jdbcType = meta.getColumnType(i);
                String typeName = meta.getColumnTypeName(i);
                boolean nullable = meta.isNullable(i) == ResultSetMetaData.columnNullable;
                columns.add(new ResultColumn(label, jdbcType, typeName,
                        mapLogicalType(jdbcType, typeName), nullable));
            }
        }

        // 重复列名检测（以 columnLabel 小写判定）
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResultColumn column : columns) {
            String normalized = column.name().toLowerCase(Locale.ROOT);
            if (!seen.add(normalized)) {
                duplicates.add(column.name());
            }
        }

        return new InspectionResult(columns, duplicates, structureFingerprint(columns));
    }

    /** JDBC 类型 → 逻辑类型（MVP 冻结映射）。 */
    private String mapLogicalType(int jdbcType, String typeName) {
        return switch (jdbcType) {
            case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> "DECIMAL";
            case java.sql.Types.VARCHAR, java.sql.Types.CHAR, java.sql.Types.NVARCHAR,
                 java.sql.Types.NCHAR, java.sql.Types.LONGVARCHAR -> "STRING";
            case java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> "DATETIME";
            case java.sql.Types.DATE -> "DATE";
            case java.sql.Types.TIME, java.sql.Types.TIME_WITH_TIMEZONE -> "TIME";
            case java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER,
                 java.sql.Types.BIGINT -> "INTEGER";
            case java.sql.Types.FLOAT, java.sql.Types.DOUBLE, java.sql.Types.REAL -> "FLOAT";
            case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> "BOOLEAN";
            case java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY,
                 java.sql.Types.BLOB -> "BINARY";
            default -> "OTHER";
        };
    }

    /** 结构指纹：列名 + JDBC 类型 + 顺序的 SHA-256。 */
    private String structureFingerprint(List<ResultColumn> columns) {
        StringBuilder content = new StringBuilder();
        for (ResultColumn column : columns) {
            content.append(column.name().toLowerCase(Locale.ROOT))
                    .append(':').append(column.jdbcType()).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
