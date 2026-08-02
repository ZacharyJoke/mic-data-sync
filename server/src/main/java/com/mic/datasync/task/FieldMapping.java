package com.mic.datasync.task;

/**
 * 字段映射：源字段 → 目标字段。
 *
 * <p>同名字段只作为映射建议；名称不同不自动强行映射，由用户显式选择。</p>
 *
 * @param sourceField 源字段名
 * @param targetField 目标字段名
 */
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
