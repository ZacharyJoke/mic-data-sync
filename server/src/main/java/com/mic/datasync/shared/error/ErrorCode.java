package com.mic.datasync.shared.error;

/**
 * 公开错误码首批冻结集合。
 *
 * <p>每个错误码必须与 {@code docs/help/error-codes.md} 中的条目一一对应，
 * 新增或调整错误码必须同步更新该文档。</p>
 */
public enum ErrorCode {

    /** 参数或配置校验失败。 */
    VALIDATION_FAILED("参数校验失败"),

    /** 未认证或会话已失效。 */
    UNAUTHORIZED("未认证或会话已失效"),

    /** 数据库连接失败（网络、凭据或服务不可达）。 */
    DATABASE_CONNECTION_FAILED("数据库连接失败"),

    /** 数据库能力不满足要求（产品、版本或权限不支持）。 */
    DATABASE_CAPABILITY_BLOCKED("数据库能力不满足要求"),

    /** 已达到 Source 任务数量上限。 */
    TASK_LIMIT_REACHED("已达到任务数量上限"),

    /** 全局活动 Run 并发名额已满。 */
    GLOBAL_CONCURRENCY_LIMIT("全局并发名额已满"),

    /** SQL 模式仅支持单条只读 SELECT。 */
    SQL_NOT_SINGLE_SELECT("仅支持单条只读 SELECT"),

    /** SQL 包含不支持的结构（如多表 JOIN、子查询等）。 */
    SQL_UNSUPPORTED_STRUCTURE("SQL 包含不支持的结构"),

    /** SQL 结果存在重复列名，无法建立稳定字段映射。 */
    SQL_RESULT_COLUMN_DUPLICATED("SQL 结果存在重复列名"),

    /** 分页 Key 组合不唯一，无法保证可靠分页。 */
    PAGINATION_KEY_NOT_UNIQUE("分页 Key 组合不唯一"),

    /** 目标表缺少用于 UPSERT 的稳定唯一约束。 */
    TARGET_UNIQUE_CONSTRAINT_MISSING("目标表缺少唯一约束"),

    /** Sink 尚未就绪，不开放数据接收能力。 */
    SINK_NOT_READY("Sink 尚未就绪"),

    /** 批次 Hash 与已记录不一致，拒绝写入。 */
    BATCH_HASH_CONFLICT("批次 Hash 与已记录不一致"),

    /** 批次事务结果未知，需要查询回执或复用原身份重发。 */
    BATCH_RESULT_UNKNOWN("批次结果未知"),

    /** 本地 Spool 数据损坏，暂停任务等待处理。 */
    SPOOL_CORRUPTED("本地 Spool 数据损坏"),

    /** 同步方向不受支持（如 KingbaseES→KingbaseES）。 */
    UNSUPPORTED_DATABASE_DIRECTION("不支持的同步方向");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    /** 面向用户的中文说明，用于统一错误响应。 */
    public String message() {
        return message;
    }
}
