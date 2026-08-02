package com.mic.datasync.source.domain;

import java.util.List;

/**
 * Table 模式读取定义：保存 Schema、表、字段和 AND 过滤条件。
 *
 * @param schema          源 Schema（可为空，由数据库默认）
 * @param table           源表名
 * @param selectedColumns 选择的字段（空表示全字段）
 * @param filters         追加过滤条件（AND 语义，MVP 阶段为简单条件；条件树由后续任务扩展）
 * @param paginationKeys  组合后稳定且唯一的分页 Key
 * @param updatedTimeField 增量更新时间字段（可为空）
 */
public record TableReadDefinition(
        String schema,
        String table,
        List<String> selectedColumns,
        List<FilterCondition> filters,
        List<String> paginationKeys,
        String updatedTimeField
) implements ReadDefinition {

    public TableReadDefinition {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("源表名不能为空");
        }
        selectedColumns = selectedColumns == null ? List.of() : List.copyOf(selectedColumns);
        filters = filters == null ? List.of() : List.copyOf(filters);
        paginationKeys = paginationKeys == null ? List.of() : List.copyOf(paginationKeys);
    }

    @Override
    public ReadMode mode() {
        return ReadMode.TABLE;
    }
}
