package com.mic.datasync.database.dialect;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writer 侧方言：标识符引用与 UPSERT SQL 生成。
 *
 * <p>默认实现面向 PostgreSQL 系（KingbaseES/Vastbase/PostgreSQL）的
 * {@code INSERT ... ON CONFLICT} 语法；原生 openGauss 由
 * {@code OpenGaussTargetAdapter} 覆写为 {@code ON DUPLICATE KEY UPDATE}。</p>
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
        return buildUpsertSql(schema, table, columns, uniqueKeys, nonUpdatableColumns, false, null, false);
    }

    /**
     * 生成批量写入 SQL；{@code skipOnConflict} 为 true 时冲突跳过（保留目标已有行）。
     *
     * <p>默认实现面向 PostgreSQL 系（KingbaseES/Vastbase/PostgreSQL）：冲突跳过
     * 生成不带 {@code conflict_target} 的 {@code ON CONFLICT DO NOTHING}，任意唯一
     * 约束/唯一索引冲突均跳过该行，避免目标表存在多个唯一索引（如业务唯一键之外
     * 还有 {@code (hospital_id, study_pk)} 类索引）时撞到非配置键冲突导致整批回滚。
     * 不支持该语法的数据库（如原生 openGauss）由适配器覆写，并需要
     * {@code noOpColumn} 生成等价的无操作更新。</p>
     *
     * @param schema            目标 Schema（可为空）
     * @param table             目标表
     * @param columns           写入字段（顺序固定）
     * @param uniqueKeys        UPSERT/冲突跳过唯一 Key（为空时退化为普通 INSERT）
     * @param nonUpdatableColumns 主键/唯一索引列（不参与 SET）
     * @param skipOnConflict    冲突时跳过（DO NOTHING / 无操作更新）而非覆盖
     * @param noOpColumn        冲突跳过用于无操作更新的非键列（openGauss 系需要）
     */
    default String buildUpsertSql(String schema, String table, List<String> columns,
                                  List<String> uniqueKeys, Set<String> nonUpdatableColumns,
                                  boolean skipOnConflict, String noOpColumn) {
        return buildUpsertSql(schema, table, columns, uniqueKeys, nonUpdatableColumns,
                skipOnConflict, noOpColumn, false);
    }

    /**
     * 生成批量写入 SQL；{@code postgresProtocol} 为 true 表示连接使用
     * {@code jdbc:postgresql://} 协议（如 PostgreSQL 兼容模式下的 Vastbase），
     * 冲突处理一律按 PostgreSQL 标准语法生成。
     */
    default String buildUpsertSql(String schema, String table, List<String> columns,
                                  List<String> uniqueKeys, Set<String> nonUpdatableColumns,
                                  boolean skipOnConflict, String noOpColumn,
                                  boolean postgresProtocol) {
        String qualifiedTable = (schema == null || schema.isBlank())
                ? quoteIdentifier(table)
                : quoteIdentifier(schema) + "." + quoteIdentifier(table);
        String columnList = columns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        String valuePlaceholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + qualifiedTable + " (" + columnList + ") VALUES (" + valuePlaceholders + ")";
        if (uniqueKeys != null && !uniqueKeys.isEmpty()) {
            if (skipOnConflict) {
                // 省略 conflict_target：ON CONFLICT DO NOTHING 会跳过违反任何
                // 唯一约束/唯一索引的行（PostgreSQL 9.5+ 标准行为）
                sql += " ON CONFLICT DO NOTHING";
            } else {
                String conflictColumns = uniqueKeys.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
                String updates = columns.stream()
                        .filter(c -> !isProtected(c, uniqueKeys, nonUpdatableColumns))
                        .map(c -> quoteIdentifier(c) + " = EXCLUDED." + quoteIdentifier(c))
                        .collect(Collectors.joining(", "));
                sql += " ON CONFLICT (" + conflictColumns + ") DO UPDATE SET " + updates;
            }
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
