package com.mic.datasync.database.support;

import com.mic.datasync.database.dialect.SourceDialect;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL 系数据库（KingbaseES/openGauss）的 JDBC 元数据读取工具。
 */
public final class PostgresMetadataReader {

    private static final SourceDialect DIALECT = new SourceDialect() {
    };

    private PostgresMetadataReader() {
    }

    /** 列出非系统 Schema。 */
    public static List<String> listSchemas(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        List<String> schemas = new ArrayList<>();
        try (ResultSet rs = meta.getSchemas()) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                // 排除系统 Schema
                if (isSystemSchema(schema)) {
                    continue;
                }
                schemas.add(schema);
            }
        }
        return schemas;
    }

    /** 列出指定 Schema 下的业务表。 */
    public static List<String> listTables(Connection connection, String schema) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(schema, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    /** 读取表元数据。 */
    public static TableMetadata readTableMetadata(Connection connection, String schema, String table)
            throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();

        // 字段
        List<ColumnMetadata> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(schema, null, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                int jdbcType = rs.getInt("DATA_TYPE");
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                columns.add(new ColumnMetadata(name, jdbcType, typeName, size, nullable, false));
            }
        }

        // 主键（按 KEY_SEQ 排序）
        Map<String, Integer> pkSequence = new LinkedHashMap<>();
        try (ResultSet rs = meta.getPrimaryKeys(schema, null, table)) {
            while (rs.next()) {
                pkSequence.put(rs.getString("COLUMN_NAME"), rs.getInt("KEY_SEQ"));
            }
        }
        List<String> primaryKey = pkSequence.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
        // 回填主键标记
        Set<String> pkSet = new LinkedHashSet<>(primaryKey);
        columns = columns.stream()
                .map(c -> new ColumnMetadata(c.name(), c.jdbcType(), c.typeName(), c.size(),
                        c.nullable(), pkSet.contains(c.name())))
                .toList();

        // 唯一索引（排除主键）
        List<List<String>> uniqueIndexes = new ArrayList<>();
        try (ResultSet rs = meta.getIndexInfo(schema, null, table, true, false)) {
            Map<String, List<String>> indexColumns = new LinkedHashMap<>();
            Map<String, Integer> indexPositions = new LinkedHashMap<>();
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                if (indexName == null) {
                    continue;
                }
                if (indexName.startsWith("sqlite_autoindex")) {
                    continue;
                }
                String columnName = rs.getString("COLUMN_NAME");
                if (columnName == null) {
                    continue;
                }
                indexColumns.computeIfAbsent(indexName, k -> new ArrayList<>()).add(columnName);
                indexPositions.put(indexName, rs.getInt("ORDINAL_POSITION"));
            }
            for (Map.Entry<String, List<String>> entry : indexColumns.entrySet()) {
                List<String> indexed = new ArrayList<>(entry.getValue());
                // 排除与主键完全一致的唯一索引
                if (!indexed.equals(primaryKey)) {
                    uniqueIndexes.add(indexed);
                }
            }
        }

        return new TableMetadata(schema, table, columns, primaryKey, uniqueIndexes);
    }

    /** 当前数据库时间。 */
    public static String currentDatabaseTime(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT " + DIALECT.currentTimestampExpression())) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** 按字段读取最多 maxRows 行。 */
    public static List<List<Object>> sampleRows(Connection connection, String schema, String table,
                                                List<String> columns, int maxRows) throws SQLException {
        String qualifiedTable = (schema == null || schema.isBlank())
                ? DIALECT.quoteIdentifier(table)
                : DIALECT.quoteIdentifier(schema) + "." + DIALECT.quoteIdentifier(table);
        String columnList = columns.stream().map(DIALECT::quoteIdentifier)
                .reduce((a, b) -> a + ", " + b)
                .orElse("*");
        String sql = "SELECT " + columnList + " FROM " + qualifiedTable
                + DIALECT.paginationSuffix(maxRows, 0);
        List<List<Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= columns.size(); i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static boolean isSystemSchema(String schema) {
        return schema == null || schema.isBlank()
                || schema.startsWith("pg_")
                || schema.equals("information_schema")
                || schema.equals("sys");
    }
}
