package com.mic.datasync.source.domain;

import java.util.List;

/**
 * 读取定义契约。
 *
 * <p>Table 与 SQL 两种模式都编译为统一的 {@link ReadPlan} 后进入
 * Batch/Spool/HTTP/Receipt/Checkpoint 链路；此处冻结的是两种模式的
 * 原始配置契约。</p>
 */
public sealed interface ReadDefinition permits TableReadDefinition, SqlReadDefinition {

    /** 读取模式。 */
    ReadMode mode();

    /** 组合后稳定且唯一的分页 Key（空表示未配置）。 */
    List<String> paginationKeys();

    /** 增量更新时间字段（可为空，追加型任务可只依赖递增主键）。 */
    String updatedTimeField();

    /** 读取模式。 */
    enum ReadMode {
        TABLE,
        SQL
    }
}
