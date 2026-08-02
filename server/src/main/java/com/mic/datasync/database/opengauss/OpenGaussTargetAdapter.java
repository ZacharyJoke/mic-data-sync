package com.mic.datasync.database.opengauss;

import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.database.support.PostgresLikeTargetAdapter;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * openGauss Target（Writer）适配器。
 *
 * <p>openGauss 不支持 PostgreSQL 的 {@code INSERT ... ON CONFLICT} 语法，
 * 使用其支持的 {@code INSERT ... ON DUPLICATE KEY UPDATE}（兼容 MySQL 语法）。</p>
 */
public class OpenGaussTargetAdapter extends PostgresLikeTargetAdapter {

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.OPEN_GAUSS;
    }

    @Override
    public String buildUpsertSql(String schema, String table, List<String> columns, List<String> uniqueKeys) {
        return buildUpsertSql(schema, table, columns, uniqueKeys, Set.of());
    }

    @Override
    public String buildUpsertSql(String schema, String table, List<String> columns,
                                 List<String> uniqueKeys, Set<String> nonUpdatableColumns) {
        String qualifiedTable = (schema == null || schema.isBlank())
                ? quoteIdentifier(table)
                : quoteIdentifier(schema) + "." + quoteIdentifier(table);
        String columnList = columns.stream().map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
        String valuePlaceholders = columns.stream().map(c -> "?")
                .collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + qualifiedTable + " (" + columnList + ") VALUES (" + valuePlaceholders + ")";
        if (uniqueKeys != null && !uniqueKeys.isEmpty()) {
            String updates = columns.stream()
                    .filter(c -> !isProtected(c, uniqueKeys, nonUpdatableColumns))
                    .map(c -> quoteIdentifier(c) + " = EXCLUDED." + quoteIdentifier(c))
                    .collect(Collectors.joining(", "));
            // openGauss ON DUPLICATE KEY UPDATE 支持 EXCLUDED 引用
            sql += " ON DUPLICATE KEY UPDATE " + updates;
        }
        return sql;
    }

    private boolean isProtected(String column, List<String> uniqueKeys, Set<String> nonUpdatableColumns) {
        String normalized = column.toLowerCase(Locale.ROOT);
        if (nonUpdatableColumns != null && nonUpdatableColumns.contains(normalized)) {
            return true;
        }
        return uniqueKeys != null && uniqueKeys.stream()
                .anyMatch(key -> key != null && key.equalsIgnoreCase(column));
    }
}
