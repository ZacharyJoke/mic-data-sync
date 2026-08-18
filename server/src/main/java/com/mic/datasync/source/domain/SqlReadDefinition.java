package com.mic.datasync.source.domain;

import java.util.List;

/**
 * 单表 SQL 模式读取定义：保存原始 SQL、基表、结果字段快照和结构指纹。
 *
 * @param rawSql               原始只读 SELECT（不允许 LIMIT 与多表 JOIN）
 * @param baseTable            解析出的单表（Schema.Table）
 * @param resultColumns        结果字段快照（顺序即结果列顺序）
 * @param structureFingerprint 结果结构指纹（用于检测 Schema Drift）
 * @param paginationKeys       组合后稳定且唯一的分页 Key
 * @param updatedTimeField     增量更新时间字段（可为空）
 * @param incrementalStrategy  增量策略（可为空，默认 TIME_WINDOW）
 * @param incrementalLookbackMinutes 增量回看分钟数（可为空，默认 10）
 */
public record SqlReadDefinition(
        String rawSql,
        String baseTable,
        List<String> resultColumns,
        String structureFingerprint,
        List<String> paginationKeys,
        String updatedTimeField,
        IncrementalStrategy incrementalStrategy,
        Integer incrementalLookbackMinutes
) implements ReadDefinition {

    public SqlReadDefinition {
        if (rawSql == null || rawSql.isBlank()) {
            throw new IllegalArgumentException("原始 SQL 不能为空");
        }
        resultColumns = resultColumns == null ? List.of() : List.copyOf(resultColumns);
        paginationKeys = paginationKeys == null ? List.of() : List.copyOf(paginationKeys);
        incrementalStrategy = incrementalStrategy == null
                ? IncrementalStrategy.TIME_WINDOW : incrementalStrategy;
        incrementalLookbackMinutes = incrementalLookbackMinutes == null
                ? 10 : incrementalLookbackMinutes;
        if (incrementalLookbackMinutes < 1) {
            throw new IllegalArgumentException("增量回看分钟数必须 >= 1");
        }
    }

    @Override
    public ReadMode mode() {
        return ReadMode.SQL;
    }

    /** 兼容旧构造调用：默认时间窗口策略 + 10 分钟回看。 */
    public SqlReadDefinition(
            String rawSql,
            String baseTable,
            List<String> resultColumns,
            String structureFingerprint,
            List<String> paginationKeys,
            String updatedTimeField) {
        this(rawSql, baseTable, resultColumns, structureFingerprint,
                paginationKeys, updatedTimeField, IncrementalStrategy.TIME_WINDOW, 10);
    }
}
