package com.mic.datasync.transport.protocol;

import com.mic.datasync.shared.id.Identifiers;

import java.util.List;
import java.util.Map;

/**
 * 批次传输协议（Payload 部分）。
 *
 * <p>Payload 落盘后不可修改；{@code payloadHash} 基于解密后的实际传输字节，
 * 位于传输元数据中，不在本 Payload 结构内。</p>
 *
 * @param protocolVersion       协议版本
 * @param sourceInstanceId      发送方实例 ID
 * @param expectedSinkInstanceId 接收方期望的实例 ID（有状态请求必须匹配）
 * @param targetDataSourceId    接收方目标数据源档案 ID（为空时 Sink 使用默认档案）
 * @param taskId                任务 ID
 * @param runId                 运行 ID
 * @param batchId               批次 ID（重试必须复用）
 * @param batchSequence         批次序号（同 Run 内唯一递增）
 * @param target                目标表
 * @param columns               列名（顺序与 rows 一致）
 * @param rows                  行数据（行内顺序与 columns 一致）
 * @param checkpointCandidate   候选检查点（Sink 确认成功后推进）
 */
public record BatchPayload(
        int protocolVersion,
        Identifiers.InstanceId sourceInstanceId,
        Identifiers.InstanceId expectedSinkInstanceId,
        String targetDataSourceId,
        Identifiers.TaskId taskId,
        Identifiers.RunId runId,
        Identifiers.BatchId batchId,
        long batchSequence,
        TargetTable target,
        List<String> columns,
        List<List<Object>> rows,
        CheckpointCandidate checkpointCandidate
) {

    /** 当前协议版本。 */
    public static final int CURRENT_PROTOCOL_VERSION = 1;

    /** 目标表（Sink 写入契约）。 */
    public record TargetTable(String schema, String table) {

        public TargetTable {
            if (table == null || table.isBlank()) {
                throw new IllegalArgumentException("目标表名不能为空");
            }
        }

        @Override
        public String toString() {
            return schema == null || schema.isBlank() ? table : schema + "." + table;
        }
    }

    /** 候选检查点：Sink 确认成功后由 Source 持久化推进。 */
    public record CheckpointCandidate(Map<String, Object> cursorValues) {

        public CheckpointCandidate {
            cursorValues = cursorValues == null ? Map.of() : Map.copyOf(cursorValues);
        }
    }
}
