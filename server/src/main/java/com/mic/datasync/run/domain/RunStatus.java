package com.mic.datasync.run.domain;

/**
 * Run 状态机（对齐领域模型 4.2）。
 *
 * <p>{@link #RUNNING}、{@link #WAITING_RETRY}、{@link #UNKNOWN}、{@link #PAUSED}
 * 都是占用原全局名额的活动状态；只有终态释放名额。</p>
 */
public enum RunStatus {
    /** 运行中。 */
    RUNNING,
    /** 等待可重试窗口（Source 临时故障）。 */
    WAITING_RETRY,
    /** 无法判断 Sink 事务结果。 */
    UNKNOWN,
    /** 人工暂停或重试耗尽。 */
    PAUSED,
    /** 全部批次确认成功。 */
    SUCCEEDED,
    /** 确定的不可恢复失败。 */
    FAILED,
    /** 显式取消（释放名额）。 */
    CANCELLED;

    /** 是否占用全局并发名额。 */
    public boolean isActive() {
        return this == RUNNING || this == WAITING_RETRY || this == UNKNOWN || this == PAUSED;
    }

    /** 是否终态（释放名额）。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
