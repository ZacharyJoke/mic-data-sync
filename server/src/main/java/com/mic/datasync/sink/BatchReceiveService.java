package com.mic.datasync.sink;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.database.TargetDatabaseAdapter;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
import com.mic.datasync.instance.InstanceService;
import com.mic.datasync.sink.ReceiptRepository.BatchReceipt;
import com.mic.datasync.transport.protocol.BatchPayload;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 批次接收编排：校验目标契约 → 幂等检查 → 业务 UPSERT 与回执同事务写入。
 *
 * <p>相同 Batch ID + 相同 Hash 返回已成功（不重复写业务表）；相同 Batch ID +
 * 不同 Hash 返回 BATCH_HASH_CONFLICT；任意业务行失败则业务与回执全部回滚。</p>
 */
@Service
public class BatchReceiveService {

    private final DatabaseConfigService configService;
    private final ConnectionFactory connectionFactory;
    private final DatabaseAdapterFactory adapterFactory;
    private final ReceiptRepository receiptRepository;
    private final TargetBatchWriter batchWriter;
    private final SinkReadinessService readinessService;
    private final InstanceService instanceService;

    public BatchReceiveService(DatabaseConfigService configService,
                               ConnectionFactory connectionFactory,
                               DatabaseAdapterFactory adapterFactory,
                               ReceiptRepository receiptRepository,
                               TargetBatchWriter batchWriter,
                               SinkReadinessService readinessService,
                               InstanceService instanceService) {
        this.configService = configService;
        this.connectionFactory = connectionFactory;
        this.adapterFactory = adapterFactory;
        this.receiptRepository = receiptRepository;
        this.batchWriter = batchWriter;
        this.readinessService = readinessService;
        this.instanceService = instanceService;
    }

    /** 接收批次。 */
    public ReceiveResult receive(BatchPayload payload, List<String> uniqueKeys, String payloadHash) {
        // 1. Sink 就绪
        if (!readinessService.readiness().ready()) {
            throw new ReceiveException(503, "SINK_NOT_READY", "Sink 未就绪，不开放数据接收");
        }
        // 2. 期望实例身份匹配
        if (!payload.expectedSinkInstanceId().equals(instanceService.instanceId())) {
            throw new ReceiveException(409, "SINK_INSTANCE_MISMATCH",
                    "expectedSinkInstanceId 与本实例不一致: " + payload.expectedSinkInstanceId());
        }
        DatabaseConfig config = resolveTargetConfig(payload.targetDataSourceId());
        try (Connection connection = connectionFactory.open(config)) {
            TargetDatabaseAdapter adapter = adapterFactory.targetAdapter(config.databaseType());
            return receiveWithConnection(connection, adapter, payload, uniqueKeys, payloadHash);
        } catch (ReceiveException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ReceiveException(400, "VALIDATION_FAILED", "批次接收失败: " + safeMessage(ex));
        }
    }

    private DatabaseConfig resolveTargetConfig(String targetDataSourceId) {
        if (targetDataSourceId != null && !targetDataSourceId.isBlank()) {
            return configService.get(targetDataSourceId)
                    .filter(config -> config.role() == DatabaseRole.SINK)
                    .orElseThrow(() -> new ReceiveException(400, "TARGET_DATASOURCE_NOT_FOUND",
                            "目标数据源不存在: " + targetDataSourceId));
        }
        return configService.getDefault(DatabaseRole.SINK)
                .orElseThrow(() -> new ReceiveException(503, "SINK_NOT_READY", "未配置 Sink 目标数据源"));
    }

    /** 核心接收逻辑（包内可见，供测试用 mock 连接验证编排）。 */
    ReceiveResult receiveWithConnection(Connection connection, TargetDatabaseAdapter adapter,
                                        BatchPayload payload, List<String> uniqueKeys, String payloadHash)
            throws SQLException {
        // 3. 目标契约校验（事务前）
        String schema = payload.target().schema();
        String table = payload.target().table();
        TableMetadata metadata = adapter.readTableMetadata(connection, schema, table);
        validateColumns(metadata, payload.columns());
        if (uniqueKeys != null && !uniqueKeys.isEmpty()
                && !adapter.hasUniqueConstraint(metadata, uniqueKeys)) {
            throw new ReceiveException(409, "TARGET_UNIQUE_CONSTRAINT_MISSING",
                    "目标表不存在与唯一 Key 匹配的唯一约束");
        }

        // 4. 幂等检查
        Optional<BatchReceipt> existing = receiptRepository.findByBatch(
                connection, payload.sourceInstanceId().toString(), payload.batchId().toString());
        if (existing.isPresent()) {
            if (existing.get().payloadHash().equals(payloadHash)) {
                return ReceiveResult.duplicate(payload.batchId().toString());
            }
            throw new ReceiveException(409, "BATCH_HASH_CONFLICT",
                    "相同 Batch ID 但 Hash 不一致: " + payload.batchId());
        }

        // 5. 业务写入与回执同事务
        List<String> safeUniqueKeys = uniqueKeys == null ? List.of() : uniqueKeys;
        connection.setAutoCommit(false);
        try {
            batchWriter.upsert(connection, adapter.databaseType(), schema, table,
                    payload.columns(), safeUniqueKeys, payload.rows(), metadata);
            receiptRepository.insert(connection, new BatchReceipt(
                    payload.sourceInstanceId().toString(),
                    payload.batchId().toString(),
                    payload.taskId().toString(),
                    payload.runId().toString(),
                    payload.batchSequence(),
                    payloadHash,
                    Instant.now()));
            connection.commit();
            return ReceiveResult.success(payload.batchId().toString(), payload.rows() == null ? 0 : payload.rows().size());
        } catch (Exception ex) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                // 回滚失败：保留原始异常
            }
            if (ex instanceof ReceiveException receiveException) {
                throw receiveException;
            }
            throw new ReceiveException(400, "VALIDATION_FAILED", "批次写入失败，已回滚: " + safeMessage(ex));
        }
    }

    private void validateColumns(TableMetadata metadata, List<String> columns) {
        Set<String> names = new HashSet<>();
        metadata.columns().forEach(c -> names.add(c.name().toLowerCase(Locale.ROOT)));
        for (String column : columns) {
            if (!names.contains(column.toLowerCase(Locale.ROOT))) {
                throw new ReceiveException(400, "VALIDATION_FAILED", "目标字段不存在: " + column);
            }
        }
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    /** 接收结果。 */
    public record ReceiveResult(boolean duplicate, String status, String batchId, int rowCount, String message) {

        static ReceiveResult success(String batchId, int rowCount) {
            return new ReceiveResult(false, "SUCCESS", batchId, rowCount, "批次已提交");
        }

        static ReceiveResult duplicate(String batchId) {
            return new ReceiveResult(true, "DUPLICATE", batchId, 0, "批次已成功接收过，不重复写入");
        }
    }

    /** 接收失败（携带错误码与 HTTP 状态）。 */
    public static class ReceiveException extends RuntimeException {
        private final int httpStatus;
        private final String errorCode;

        public ReceiveException(int httpStatus, String errorCode, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
