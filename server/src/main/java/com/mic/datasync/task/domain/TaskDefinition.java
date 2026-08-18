package com.mic.datasync.task.domain;

import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.source.domain.ReadDefinition;
import com.mic.datasync.transport.protocol.BatchPayload;

import java.util.List;

/**
 * 同步任务定义（冻结契约）。
 *
 * <p>一个任务读取一张表或一条单表 SQL，写入一个目标表；已启用版本的同步
 * 语义不可原地修改，语义变化必须创建新版本并重新全量。</p>
 *
 * @param taskId            任务 ID
 * @param name              任务名称
 * @param version           版本号（语义变化时递增）
 * @param lifecycleStatus   生命周期状态
 * @param readDefinition    读取定义（Table/SQL 模式）
 * @param target            目标表
 * @param fieldMappings     字段映射（源字段 → 目标字段）
 * @param writeMode         写入模式（UPSERT / INSERT_ONLY）
 * @param uniqueKeys        目标唯一 Key（UPSERT 使用；可空）
 * @param remoteSinkUrl     远程 Sink 地址（可空）
 * @param sinkTokenRef      本地凭据引用（可空，明文不入库）
 */
public record TaskDefinition(
        Identifiers.TaskId taskId,
        String name,
        int version,
        LifecycleStatus lifecycleStatus,
        ReadDefinition readDefinition,
        BatchPayload.TargetTable target,
        List<FieldMapping> fieldMappings,
        WriteMode writeMode,
        List<String> uniqueKeys,
        String remoteSinkUrl,
        String sinkTokenRef
) {

    /** 生命周期状态（对齐领域模型 4.1）。 */
    public enum LifecycleStatus {
        DRAFT,
        ENABLED,
        PAUSED,
        DISABLED,
        BLOCKED,
        DELETING,
        DELETED
    }

    /** 写入模式。 */
    public enum WriteMode {
        /** 按目标唯一 Key 执行 UPSERT（需要唯一约束）。 */
        UPSERT,
        /**
         * 按目标唯一 Key 执行冲突跳过：目标已存在相同 Key 时保留目标行，
         * 仅插入新行（需要唯一约束；openGauss/Vastbase 用无操作更新实现）。
         */
        UPSERT_NO_OVERWRITE,
        /** 仅插入（追加型任务，重复执行会重复插入）。 */
        INSERT_ONLY,
        /**
         * 全量重导：目标表应为空（由人工线下清空），工具校验空表后全量插入；
         * 仅支持全量同步，工具自身不执行清表操作。
         */
        REPLACE_ALL
    }

    /** 字段映射：源字段 → 目标字段。 */
    public record FieldMapping(String sourceField, String targetField) {

        public FieldMapping {
            if (sourceField == null || sourceField.isBlank()) {
                throw new IllegalArgumentException("源字段名不能为空");
            }
            if (targetField == null || targetField.isBlank()) {
                throw new IllegalArgumentException("目标字段名不能为空");
            }
        }
    }

    public TaskDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("任务名称不能为空");
        }
        fieldMappings = fieldMappings == null ? List.of() : List.copyOf(fieldMappings);
        uniqueKeys = uniqueKeys == null ? List.of() : List.copyOf(uniqueKeys);
    }
}
