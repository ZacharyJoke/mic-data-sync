package com.mic.datasync.source;

import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.transport.protocol.BatchPayload;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 批次组装与切分：把读取行按最大行数与最大负载字节切分为多个 BatchPayload。
 */
@Component
public class BatchAssembler {

    /** OFFSET 快照分页游标保留键（REPLACE_ALL 使用）。 */
    public static final String CURSOR_OFFSET_KEY = "_offset";

    /**
     * 切分批次。
     *
     * @param rows            已规范化的行数据
     * @param maxRowsPerBatch 单批最大行数
     * @param maxPayloadBytes 单批最大负载字节（估算）
     */
    public List<BatchPayload> assemble(
            Identifiers.InstanceId sourceInstanceId,
            Identifiers.InstanceId sinkInstanceId,
            String targetDataSourceId,
            Identifiers.TaskId taskId,
            Identifiers.RunId runId,
            BatchPayload.TargetTable target,
            List<String> columns,
            List<List<Object>> rows,
            int maxRowsPerBatch,
            int maxPayloadBytes) {
        return assemble(sourceInstanceId, sinkInstanceId, targetDataSourceId, taskId, runId, target,
                columns, rows, maxRowsPerBatch, maxPayloadBytes, 0, 0);
    }

    /**
     * 切分批次（支持跨页起始序号，保证同一 Run 内 batch_sequence 单调递增）。
     *
     * @param startSequence 起始序号（不含），返回的批次序号从 startSequence+1 开始
     */
    public List<BatchPayload> assemble(
            Identifiers.InstanceId sourceInstanceId,
            Identifiers.InstanceId sinkInstanceId,
            String targetDataSourceId,
            Identifiers.TaskId taskId,
            Identifiers.RunId runId,
            BatchPayload.TargetTable target,
            List<String> columns,
            List<List<Object>> rows,
            int maxRowsPerBatch,
            int maxPayloadBytes,
            long startSequence) {
        return assemble(sourceInstanceId, sinkInstanceId, targetDataSourceId, taskId, runId, target,
                columns, rows, maxRowsPerBatch, maxPayloadBytes, startSequence, 0);
    }

    /**
     * 切分批次（支持跨页起始序号与 OFFSET 绝对行号）。
     *
     * @param startSequence 起始序号（不含），返回的批次序号从 startSequence+1 开始
     * @param pageOffset    本页在表中的绝对起始行号（OFFSET 分页游标；Keyset 传 0）
     */
    public List<BatchPayload> assemble(
            Identifiers.InstanceId sourceInstanceId,
            Identifiers.InstanceId sinkInstanceId,
            String targetDataSourceId,
            Identifiers.TaskId taskId,
            Identifiers.RunId runId,
            BatchPayload.TargetTable target,
            List<String> columns,
            List<List<Object>> rows,
            int maxRowsPerBatch,
            int maxPayloadBytes,
            long startSequence,
            long pageOffset) {
        List<BatchPayload> batches = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return batches;
        }
        List<List<Object>> current = new ArrayList<>();
        long currentBytes = 0;
        long sequence = startSequence;
        long absoluteOffset = pageOffset;
        for (List<Object> row : rows) {
            long rowBytes = estimateRowBytes(row);
            boolean batchFull = current.size() >= maxRowsPerBatch
                    || (currentBytes + rowBytes > maxPayloadBytes && !current.isEmpty());
            if (batchFull) {
                batches.add(createBatch(sourceInstanceId, sinkInstanceId, targetDataSourceId, taskId, runId,
                        target, columns, current, ++sequence, absoluteOffset));
                absoluteOffset += current.size();
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(row);
            currentBytes += rowBytes;
        }
        if (!current.isEmpty()) {
            batches.add(createBatch(sourceInstanceId, sinkInstanceId, targetDataSourceId, taskId, runId,
                    target, columns, current, ++sequence, absoluteOffset));
        }
        return batches;
    }

    private BatchPayload createBatch(Identifiers.InstanceId sourceInstanceId,
                                     Identifiers.InstanceId sinkInstanceId,
                                     String targetDataSourceId,
                                     Identifiers.TaskId taskId,
                                     Identifiers.RunId runId,
                                     BatchPayload.TargetTable target,
                                     List<String> columns,
                                     List<List<Object>> rows,
                                     long sequence,
                                     long absoluteOffset) {
        return new BatchPayload(
                BatchPayload.CURRENT_PROTOCOL_VERSION,
                sourceInstanceId,
                sinkInstanceId,
                targetDataSourceId,
                taskId,
                runId,
                Identifiers.BatchId.generate(),
                sequence,
                target,
                List.copyOf(columns),
                List.copyOf(rows),
                new BatchPayload.CheckpointCandidate(Map.of(CURSOR_OFFSET_KEY, absoluteOffset)));
    }

    /** 估算行 JSON 序列化字节数（值字符串长度 + 分隔符开销）。 */
    public static long estimateRowBytes(List<Object> row) {
        long bytes = 2; // [ ]
        for (Object value : row) {
            bytes += value == null ? 4 : value.toString().length() + 2;
        }
        return bytes;
    }
}
