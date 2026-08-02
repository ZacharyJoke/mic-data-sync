package com.mic.datasync.database.metadata;

import java.util.List;

/**
 * 表元数据（Reader/Writer 能力探查结果）。
 *
 * @param schema            表所属 Schema
 * @param table             表名
 * @param columns           字段列表（按表定义顺序）
 * @param primaryKeyColumns 主键字段（组合主键按序号排序）
 * @param uniqueIndexes     唯一索引字段集合（不含主键）
 */
public record TableMetadata(
        String schema,
        String table,
        List<ColumnMetadata> columns,
        List<String> primaryKeyColumns,
        List<List<String>> uniqueIndexes) {

    public TableMetadata {
        columns = columns == null ? List.of() : List.copyOf(columns);
        primaryKeyColumns = primaryKeyColumns == null ? List.of() : List.copyOf(primaryKeyColumns);
        uniqueIndexes = uniqueIndexes == null ? List.of() : List.copyOf(uniqueIndexes);
    }

    /** 字段名 → 字段元数据。 */
    public java.util.Map<String, ColumnMetadata> columnMap() {
        java.util.Map<String, ColumnMetadata> map = new java.util.LinkedHashMap<>();
        for (ColumnMetadata column : columns) {
            map.put(column.name().toLowerCase(), column);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    /** 表内是否存在主键。 */
    public boolean hasPrimaryKey() {
        return !primaryKeyColumns.isEmpty();
    }
}
