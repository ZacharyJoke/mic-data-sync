package com.mic.datasync.sink;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 目标库批次回执表（mic_sync_batch_receipt）读写。
 *
 * <p>回执唯一约束为 {@code (source_instance_id, batch_id)}；
 * 业务写入与回执插入必须在同一个目标数据库事务中。</p>
 */
@Component
public class ReceiptRepository {

    /** 回执记录。 */
    public record BatchReceipt(
            String sourceInstanceId,
            String batchId,
            String taskId,
            String runId,
            long batchSequence,
            String payloadHash,
            Instant receivedAt) {
    }

    /** 按批次查询回执（使用调用方连接，保持事务一致性）。 */
    public Optional<BatchReceipt> findByBatch(Connection connection, String sourceInstanceId, String batchId)
            throws SQLException {
        String sql = """
                SELECT source_instance_id, batch_id, task_id, run_id, batch_sequence, payload_hash, received_at
                FROM mic_sync_batch_receipt
                WHERE source_instance_id = ? AND batch_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sourceInstanceId);
            statement.setString(2, batchId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new BatchReceipt(
                        rs.getString("source_instance_id"),
                        rs.getString("batch_id"),
                        rs.getString("task_id"),
                        rs.getString("run_id"),
                        rs.getLong("batch_sequence"),
                        rs.getString("payload_hash"),
                        rs.getTimestamp("received_at").toInstant()));
            }
        }
    }

    /** 插入回执（与业务写入同事务）。 */
    public void insert(Connection connection, BatchReceipt receipt) throws SQLException {
        String sql = """
                INSERT INTO mic_sync_batch_receipt
                    (source_instance_id, batch_id, task_id, run_id, batch_sequence, payload_hash, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, receipt.sourceInstanceId());
            statement.setString(2, receipt.batchId());
            statement.setString(3, receipt.taskId());
            statement.setString(4, receipt.runId());
            statement.setLong(5, receipt.batchSequence());
            statement.setString(6, receipt.payloadHash());
            statement.setTimestamp(7, Timestamp.from(receipt.receivedAt()));
            statement.executeUpdate();
        }
    }

    /** 指定 Run 是否已有回执（用于判断 REPLACE_ALL 的首个真正写入批次）。 */
    public boolean existsByRun(Connection connection, String runId) throws SQLException {
        String sql = "SELECT 1 FROM mic_sync_batch_receipt WHERE run_id = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
