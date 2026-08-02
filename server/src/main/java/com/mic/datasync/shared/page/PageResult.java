package com.mic.datasync.shared.page;

import java.util.List;
import java.util.function.Function;

/**
 * 通用分页结果。
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    public PageResult {
        items = List.copyOf(items);
        if (page < 1) {
            throw new IllegalArgumentException("page 必须大于等于 1");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size 必须在 1 到 100 之间");
        }
        if (total < 0) {
            throw new IllegalArgumentException("total 不能小于 0");
        }
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), total, page, size);
    }
}
