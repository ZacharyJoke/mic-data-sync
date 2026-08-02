package com.mic.datasync.database.dialect;

/**
 * Source 侧方言：标识符引用、分页与时间函数。
 *
 * <p>KingbaseES 与 openGauss 均为 PostgreSQL 系，MVP 使用同一套 PG 兼容方言；
 * 未来存在差异时可拆分为各自实现。</p>
 */
public interface SourceDialect {

    /** 引用标识符（防注入与保留字冲突）。 */
    default String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** 追加分页子句（PG 系：LIMIT ? OFFSET ?）。 */
    default String paginationSuffix(int limit, int offset) {
        return " LIMIT " + limit + " OFFSET " + offset;
    }

    /** 当前数据库时间表达式。 */
    default String currentTimestampExpression() {
        return "CURRENT_TIMESTAMP";
    }
}
