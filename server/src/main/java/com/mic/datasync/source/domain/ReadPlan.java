package com.mic.datasync.source.domain;

import java.util.List;

/**
 * 编译后的读取计划（Table/SQL 模式统一产物）。
 *
 * <p>预览 SQL 仅用于展示与调试，不作为任务唯一持久化内容；
 * 运行时以 {@link #paginationKeys()} 与结构指纹生成分页查询。</p>
 *
 * @param mode                读取模式（TABLE/SQL）
 * @param schema              源 Schema
 * @param table               源表
 * @param columns             读取字段
 * @param filters             过滤条件（AND）
 * @param paginationKeys      分页键（组合后唯一）
 * @param updatedTimeField    增量更新时间字段（可为空）
 * @param previewSql          只读预览 SQL（LIMIT 20）
 * @param structureFingerprint 结构指纹（用于 Schema Drift 检测）
 */
public record ReadPlan(
        String mode,
        String schema,
        String table,
        List<String> columns,
        List<FilterCondition> filters,
        List<String> paginationKeys,
        String updatedTimeField,
        String previewSql,
        String structureFingerprint) {

    public ReadPlan {
        columns = columns == null ? List.of() : List.copyOf(columns);
        filters = filters == null ? List.of() : List.copyOf(filters);
        paginationKeys = paginationKeys == null ? List.of() : List.copyOf(paginationKeys);
    }
}
