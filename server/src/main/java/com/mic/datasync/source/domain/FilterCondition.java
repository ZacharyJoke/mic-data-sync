package com.mic.datasync.source.domain;

/**
 * 简单过滤条件（AND 语义）。
 *
 * <p>MVP 阶段条件树由后续任务实现，此处冻结最小字段契约。</p>
 *
 * @param column   字段名
 * @param operator 运算符（如 =、!=、>、<、IN、LIKE）
 * @param value    比较值（可为单个值；IN 场景使用 {@link java.util.List}）
 */
public record FilterCondition(String column, String operator, Object value) {

    public FilterCondition {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("过滤字段不能为空");
        }
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("过滤运算符不能为空");
        }
    }
}
