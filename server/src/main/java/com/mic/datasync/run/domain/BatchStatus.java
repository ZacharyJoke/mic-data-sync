package com.mic.datasync.run.domain;

/**
 * Batch 状态机（对齐领域模型 4.2）。
 */
public enum BatchStatus {
    /** 已落盘并持久化发送意图，尚未发送。 */
    PENDING,
    /** 发送中（attemptCount 已递增）。 */
    PROCESSING,
    /** 事务结果未知，需要查询回执或复用原身份重发。 */
    UNKNOWN,
    /** Sink 确认成功。 */
    SUCCEEDED,
    /** 数据或事务明确失败。 */
    FAILED,
    /** 损坏且确认从未发送，或旧 Epoch 被正式替换。 */
    SUPERSEDED;
}
