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
 * @param incrementalStrategy 增量策略（默认 TIME_WINDOW）
 * @param incrementalLookbackMinutes 增量回看分钟数（默认 10）
 * @param previewSql          只读预览 SQL（LIMIT 20）
 * @param structureFingerprint 结构指纹（用于 Schema Drift 检测）
 * @param pagination          分页策略（KEYSET / OFFSET）
 */
public record ReadPlan(
        String mode,
        String schema,
        String table,
        List<String> columns,
        List<FilterCondition> filters,
        List<String> paginationKeys,
        String updatedTimeField,
        IncrementalStrategy incrementalStrategy,
        Integer incrementalLookbackMinutes,
        String previewSql,
        String structureFingerprint,
        PaginationStrategy pagination) {

    public ReadPlan {
        columns = columns == null ? List.of() : List.copyOf(columns);
        filters = filters == null ? List.of() : List.copyOf(filters);
        paginationKeys = paginationKeys == null ? List.of() : List.copyOf(paginationKeys);
        incrementalStrategy = incrementalStrategy == null
                ? IncrementalStrategy.TIME_WINDOW : incrementalStrategy;
        incrementalLookbackMinutes = incrementalLookbackMinutes == null
                ? 10 : incrementalLookbackMinutes;
        if (incrementalLookbackMinutes < 1) {
            throw new IllegalArgumentException("增量回看分钟数必须 >= 1");
        }
        pagination = pagination == null ? PaginationStrategy.KEYSET : pagination;
    }

    /** 兼容旧调用：默认 Keyset 分页。 */
    public ReadPlan(
            String mode,
            String schema,
            String table,
            List<String> columns,
            List<FilterCondition> filters,
            List<String> paginationKeys,
            String updatedTimeField,
            String previewSql,
            String structureFingerprint) {
        this(mode, schema, table, columns, filters, paginationKeys,
                updatedTimeField, IncrementalStrategy.TIME_WINDOW, 10,
                previewSql, structureFingerprint, PaginationStrategy.KEYSET);
    }

    /** 兼容旧调用：默认时间窗口策略 + 10 分钟回看，指定分页策略。 */
    public ReadPlan(
            String mode,
            String schema,
            String table,
            List<String> columns,
            List<FilterCondition> filters,
            List<String> paginationKeys,
            String updatedTimeField,
            String previewSql,
            String structureFingerprint,
            PaginationStrategy pagination) {
        this(mode, schema, table, columns, filters, paginationKeys,
                updatedTimeField, IncrementalStrategy.TIME_WINDOW, 10,
                previewSql, structureFingerprint, pagination);
    }

    /** 兼容旧调用：默认 Keyset 分页（含增量策略透传）。 */
    public ReadPlan(
            String mode,
            String schema,
            String table,
            List<String> columns,
            List<FilterCondition> filters,
            List<String> paginationKeys,
            String updatedTimeField,
            IncrementalStrategy incrementalStrategy,
            Integer incrementalLookbackMinutes,
            String previewSql,
            String structureFingerprint) {
        this(mode, schema, table, columns, filters, paginationKeys,
                updatedTimeField, incrementalStrategy, incrementalLookbackMinutes,
                previewSql, structureFingerprint, PaginationStrategy.KEYSET);
    }

    /** 分页策略。 */
    public enum PaginationStrategy {
        /** Keyset（游标）分页：分页键组合须唯一且非 NULL。 */
        KEYSET,
        /** OFFSET 快照分页：不依赖唯一键，要求同步期间源表静止（REPLACE_ALL 使用）。 */
        OFFSET
    }
}
