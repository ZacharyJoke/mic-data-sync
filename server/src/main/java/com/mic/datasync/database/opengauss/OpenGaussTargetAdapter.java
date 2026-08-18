package com.mic.datasync.database.opengauss;

import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.database.dialect.WriterDialect;
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

    /** PostgreSQL 标准方言（ON CONFLICT）委托实现，供 jdbc:postgresql 协议复用。 */
    private static final WriterDialect POSTGRES_STYLE = new WriterDialect() {
    };

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
        return buildUpsertSql(schema, table, columns, uniqueKeys, nonUpdatableColumns, false, null, false);
    }

    /**
     * openGauss 不支持 PostgreSQL 的 {@code ON CONFLICT DO NOTHING}，冲突跳过
     * 用 {@code ON DUPLICATE KEY UPDATE} 的“无操作更新”（非键列自赋值）实现，
     * 目标行内容保持不变。noOpColumn 需为目标表中非主键/非唯一索引列。
     */
    @Override
    public String buildUpsertSql(String schema, String table, List<String> columns,
                                 List<String> uniqueKeys, Set<String> nonUpdatableColumns,
                                 boolean skipOnConflict, String noOpColumn) {
        return buildUpsertSql(schema, table, columns, uniqueKeys, nonUpdatableColumns,
                skipOnConflict, noOpColumn, false);
    }

    /**
     * 按连接协议分派冲突语法：{@code jdbc:postgresql://}（PostgreSQL 兼容模式的
     * Vastbase 等）走 PostgreSQL 标准 {@code ON CONFLICT}；{@code jdbc:opengauss://}
     * （原生 openGauss）走 {@code ON DUPLICATE KEY UPDATE}。
     */
    @Override
    public String buildUpsertSql(String schema, String table, List<String> columns,
                                 List<String> uniqueKeys, Set<String> nonUpdatableColumns,
                                 boolean skipOnConflict, String noOpColumn,
                                 boolean postgresProtocol) {
        if (postgresProtocol) {
            return POSTGRES_STYLE.buildUpsertSql(schema, table, columns, uniqueKeys,
                    nonUpdatableColumns, skipOnConflict, noOpColumn, true);
        }
        String qualifiedTable = (schema == null || schema.isBlank())
                ? quoteIdentifier(table)
                : quoteIdentifier(schema) + "." + quoteIdentifier(table);
        String columnList = columns.stream().map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
        String valuePlaceholders = columns.stream().map(c -> "?")
                .collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + qualifiedTable + " (" + columnList + ") VALUES (" + valuePlaceholders + ")";
        if (uniqueKeys != null && !uniqueKeys.isEmpty()) {
            if (skipOnConflict) {
                if (noOpColumn == null || noOpColumn.isBlank()) {
                    throw new IllegalArgumentException(
                            "openGauss/Vastbase 冲突跳过模式需要目标表至少存在一个非主键/唯一键字段作为无操作更新列");
                }
                String quoted = quoteIdentifier(noOpColumn);
                sql += " ON DUPLICATE KEY UPDATE " + quoted + " = " + quoted;
            } else {
                String updates = columns.stream()
                        .filter(c -> !isProtected(c, uniqueKeys, nonUpdatableColumns))
                        .map(c -> quoteIdentifier(c) + " = EXCLUDED." + quoteIdentifier(c))
                        .collect(Collectors.joining(", "));
                // openGauss ON DUPLICATE KEY UPDATE 支持 EXCLUDED 引用
                sql += " ON DUPLICATE KEY UPDATE " + updates;
            }
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
