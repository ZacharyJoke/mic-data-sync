package com.mic.datasync.source.domain;

/**
 * 增量同步策略。
 *
 * <p>TIME_WINDOW：原行为，仅按“已确认时间 − 回看窗口”重扫，适合时间字段
 * 与主键顺序一致的表；DUAL_PHASE：主键推进捕获新增 + 时间窗口补扫更新，
 * 适合时间乱序但更新写当前时间的表。</p>
 */
public enum IncrementalStrategy {
    /** 仅时间窗口重扫（默认，兼容历史行为）。 */
    TIME_WINDOW,
    /** 双阶段：主键 keyset 推进捕获新增，再按时间窗口重扫捕获更新。 */
    DUAL_PHASE
}
