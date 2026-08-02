package com.mic.datasync.database.dialect;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writer 侧方言：标识符引用与 UPSERT SQL 生成。
 *
 * <p>KingbaseES 与 openGauss 均支持 PostgreSQL 的 {@code INSERT ... ON CONFLICT} 语法。</p>
 */
public interface WriterDialect {

    /** 引用标识符（防注入与保留字冲突）。 */
    default String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * 生成批量 UPSERT SQL（单条 INSERT 多行 VALUES + ON CONFLICT DO UPDATE）。
     *
     * @param schema     目标 Schema（可为空）
     * @param table      目标表
     * @param columns    写入字段（顺序固定）
     * @param uniqueKeys UPSERT 唯一 Key（为空时退化为普通 INSERT）
     */
    default String buildUpsertSql(String schema, String table, List<String> columns, List<String> uniqueKeys) {
        return buildUpsertSql(schema, table, columns, uniqueKeys, Set.of());
    }

    /**
     * 生成批量 UPSERT SQL；除 uniqueKeys 外，nonUpdatableColumns（主键/唯一索引列）
     * 也会从 SET 子句中排除，避免数据库拒绝更新主键或唯一键列。
     */
    default String buildUpsertSql(String schema, String table, List<String> columns,
                                  List<String> uniqueKeys, Set<String> nonUpdatableColumns) {
        String qualifiedTable = (schema == null || schema.isBlank())
                ? quoteIdentifier(table)
                : quoteIdentifier(schema) + "." + quoteIdentifier(table);
        String columnList = columns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        String valuePlaceholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + qualifiedTable + " (" + columnList + ") VALUES (" + valuePlaceholders + ")";
        if (uniqueKeys != null && !uniqueKeys.isEmpty()) {
            String conflictColumns = uniqueKeys.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
            String updates = columns.stream()
                    .filter(c -> !isProtected(c, uniqueKeys, nonUpdatableColumns))
                    .map(c -> quoteIdentifier(c) + " = EXCLUDED." + quoteIdentifier(c))
                    .collect(Collectors.joining(", "));
            sql += " ON CONFLICT (" + conflictColumns + ") DO UPDATE SET " + updates;
        }
        return sql;
    }

    /** 大小写不敏感判断字段是否属于冲突键或数据库级不可更新键。 */
    private boolean isProtected(String column, List<String> uniqueKeys, Set<String> nonUpdatableColumns) {
        String normalized = column.toLowerCase(Locale.ROOT);
        if (nonUpdatableColumns != null && nonUpdatableColumns.contains(normalized)) {
            return true;
        }
        return uniqueKeys != null && uniqueKeys.stream()
                .anyMatch(key -> key != null && key.equalsIgnoreCase(column));
    }
}
