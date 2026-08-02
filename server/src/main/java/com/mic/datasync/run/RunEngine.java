package com.mic.datasync.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.database.SourceDatabaseAdapter;
import com.mic.datasync.database.dialect.SourceDialect;
import com.mic.datasync.database.metadata.TableMetadata;
import com.mic.datasync.instance.RoleProperties;
import com.mic.datasync.run.CheckpointRepository.Checkpoint;
import com.mic.datasync.run.RunFailureService.FailureStage;
import com.mic.datasync.run.RunFailureService.RunFailure;
import com.mic.datasync.run.RunService.RunKind;
import com.mic.datasync.run.RunService.RunRecord;
import com.mic.datasync.run.domain.RunStatus;
import com.mic.datasync.sink.SinkTokenResolver;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.source.BatchAssembler;
import com.mic.datasync.source.KeysetPredicateBuilder;
import com.mic.datasync.source.LogicalTypeMapper;
import com.mic.datasync.source.RowNormalizer;
import com.mic.datasync.source.TableReadPlanCompiler;
import com.mic.datasync.source.domain.FilterCondition;
import com.mic.datasync.source.domain.ReadPlan;
import com.mic.datasync.source.domain.SqlReadDefinition;
import com.mic.datasync.source.domain.TableReadDefinition;
import com.mic.datasync.source.sql.SqlMetadataInspector;
import com.mic.datasync.source.sql.SqlReadPlanCompiler;
import com.mic.datasync.source.sql.SqlSafetyValidator;
import com.mic.datasync.storage.spool.BatchSpoolStore;
import com.mic.datasync.storage.spool.BatchSpoolStore.StoredBatch;
import com.mic.datasync.task.TaskService.TaskRecord;
import com.mic.datasync.transport.SinkResponseClassifier.Outcome;
import com.mic.datasync.transport.SinkTransport;
import com.mic.datasync.transport.protocol.BatchPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Run 引擎：执行全量+追赶（INITIAL_FULL）或手动增量（INCREMENTAL）。
 *
 * <p>统一 ReadPlan + Keyset 分页读取；每批 Spool 落盘后发送，Sink 确认成功才
 * 在同一 SQLite 事务推进 Checkpoint 与 Run 统计；结果未知时先查询回执，
 * 确认未提交才复用原 Batch ID/Hash/字节重发。</p>
 */
@Service
public class RunEngine {

    private static final Logger log = LoggerFactory.getLogger(RunEngine.class);
    private static final int GZIP_THRESHOLD = 64 * 1024;
    private static final long INCREMENTAL_LOOKBACK_MINUTES = 10;

    private final DatabaseConfigService configService;
    private final ConnectionFactory connectionFactory;
    private final DatabaseAdapterFactory adapterFactory;
    private final TableReadPlanCompiler tableCompiler;
    private final SqlReadPlanCompiler sqlCompiler;
    private final SqlMetadataInspector sqlInspector;
    private final SqlSafetyValidator sqlSafetyValidator;
    private final RowNormalizer rowNormalizer;
    private final BatchAssembler batchAssembler;
    private final BatchSpoolStore spoolStore;
    private final SinkTransport transport;
    private final CheckpointRepository checkpointRepository;
    private final RunService runService;
    private final RunFailureService runFailureService;
    private final RoleProperties roleProperties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SourceDialect dialect = new SourceDialect() {
    };
    private final SinkTokenResolver sinkTokenResolver;

    public RunEngine(DatabaseConfigService configService,
                     ConnectionFactory connectionFactory,
                     DatabaseAdapterFactory adapterFactory,
                     TableReadPlanCompiler tableCompiler,
                     SqlReadPlanCompiler sqlCompiler,
                     SqlMetadataInspector sqlInspector,
                     SqlSafetyValidator sqlSafetyValidator,
                     RowNormalizer rowNormalizer,
                     BatchAssembler batchAssembler,
                     BatchSpoolStore spoolStore,
                     SinkTransport transport,
                     CheckpointRepository checkpointRepository,
                     RunService runService,
                     RunFailureService runFailureService,
                     RoleProperties roleProperties,
                     ObjectMapper objectMapper,
                     JdbcTemplate jdbcTemplate,
                     SinkTokenResolver sinkTokenResolver) {
        this.configService = configService;
        this.connectionFactory = connectionFactory;
        this.adapterFactory = adapterFactory;
        this.tableCompiler = tableCompiler;
        this.sqlCompiler = sqlCompiler;
        this.sqlInspector = sqlInspector;
        this.sqlSafetyValidator = sqlSafetyValidator;
        this.rowNormalizer = rowNormalizer;
        this.batchAssembler = batchAssembler;
        this.spoolStore = spoolStore;
        this.transport = transport;
        this.checkpointRepository = checkpointRepository;
        this.runService = runService;
        this.runFailureService = runFailureService;
        this.roleProperties = roleProperties;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.sinkTokenResolver = sinkTokenResolver;
    }

    /** 执行运行（创建新 Run）。 */
    public void execute(TaskRecord task, RunKind kind) {
        RunRecord run = createRun(task, kind, null);
        executeCreated(task, run);
    }

    /** 先创建 Run，立即返回；调用方自行调度执行。 */
    public RunRecord createRun(TaskRecord task, RunKind kind, Identifiers.RunId previousRunId) {
        return runService.create(task.taskId(), task.name(), task.version(), kind, previousRunId);
    }

    /** 执行已创建的 Run。 */
    public void executeCreated(TaskRecord task, RunRecord run) {
        runLoop(task, run);
    }

    /** 继续原 Run（复用 runId，从 Checkpoint 继续；先恢复 PENDING/UNKNOWN Spool 由调用方处理）。 */
    public void resume(TaskRecord task, RunRecord existingRun) {
        runLoop(task, existingRun);
    }

    private void runLoop(TaskRecord task, RunRecord run) {
        long sourceRows = 0;
        long confirmedRows = 0;
        try {
            DatabaseConfig config = resolveSourceConfig(task)
                    .orElseThrow(() -> new RunExecutionException(
                            FailureStage.PREFLIGHT, "SOURCE_NOT_CONFIGURED",
                            "未配置 Source 数据库", "无法开始读取，任务未运行", true));
            try (Connection connection = connectionFactory.open(config)) {
                SourceDatabaseAdapter adapter = adapterFactory.sourceAdapter(config.databaseType());
                ReadPlan plan = compilePlan(connection, adapter, task);
                RunStats stats = runWithCursor(connection, adapter, plan, task, run);
                sourceRows = stats.sourceRows();
                confirmedRows = stats.confirmedRows();
            }
            // 暂停状态保持（resume 后继续执行；若期间被暂停则不标记成功）
            RunStatus current = runService.get(run.runId()).map(RunRecord::status).orElse(RunStatus.SUCCEEDED);
            if (current == RunStatus.PAUSED) {
                return;
            }
            runService.updateStatus(run.runId(), RunStatus.SUCCEEDED, null,
                    sourceRows, confirmedRows);
        } catch (RunExecutionException ex) {
            String requestId = UUID.randomUUID().toString();
            runFailureService.record(new RunFailure(
                    run.runId(), ex.stage(), ex.errorCode(), ex.getMessage(), ex.impact(),
                    requestId, ex.retryable(), Instant.now()));
            runService.updateStatus(run.runId(), RunStatus.FAILED, ex.getMessage(), sourceRows, confirmedRows);
            log.warn("run 执行失败 taskId={} runId={} requestId={} code={}",
                    task.taskId(), run.runId(), requestId, ex.errorCode());
        } catch (Exception ex) {
            String requestId = UUID.randomUUID().toString();
            runFailureService.record(new RunFailure(
                    run.runId(), FailureStage.INTERNAL, "INTERNAL_ERROR",
                    "运行发生内部错误", "当前运行已停止，未确认批次不会推进 Checkpoint",
                    requestId, false, Instant.now()));
            runService.updateStatus(run.runId(), RunStatus.FAILED, "运行发生内部错误", sourceRows, confirmedRows);
            log.error("run 执行异常 taskId={} runId={} requestId={}",
                    task.taskId(), run.runId(), requestId, ex);
        }
    }

    private Optional<DatabaseConfig> resolveSourceConfig(TaskRecord task) {
        if (task.sourceDataSourceId() != null && !task.sourceDataSourceId().isBlank()) {
            Optional<DatabaseConfig> byId = configService.get(task.sourceDataSourceId());
            if (byId.isPresent()) {
                return byId;
            }
        }
        return configService.getDefault(DatabaseRole.SOURCE);
    }

    /** 游标循环：读取 → 批次 → 确认推进 Checkpoint。 */
    private RunStats runWithCursor(Connection connection, SourceDatabaseAdapter adapter,
                                   ReadPlan plan, TaskRecord task, RunRecord run) throws Exception {
        int batchSize = roleProperties.sink().maxRowsPerBatch();
        Map<String, Object> cursor = checkpointRepository.get(task.taskId())
                .map(Checkpoint::cursorValues).orElse(null);
        // 全量：先取 T0
        Instant t0 = run.kind() == RunKind.INITIAL_FULL ? parseDatabaseTime(adapter.currentDatabaseTime(connection)) : null;
        boolean catchUpPhase = false;
        Identifiers.BatchId lastConfirmedBatch = null;
        long sourceRows = 0;
        long confirmedRows = 0;
        // 批次序号从该 Run 已存在的最大序号继续（暂停/继续复用原 runId 时不冲突）
        Long maxSequence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(batch_sequence), 0) FROM batch WHERE run_id = ?",
                Long.class, run.runId().toString());
        long batchSequenceCounter = maxSequence == null ? 0 : maxSequence;

        while (true) {
            // 暂停检查：暂停后不读取新批次
            RunStatus currentStatus = runService.get(run.runId()).map(RunRecord::status).orElse(RunStatus.RUNNING);
            if (currentStatus == RunStatus.PAUSED || currentStatus == RunStatus.UNKNOWN) {
                break;
            }
            String timeCondition = null;
            Object timeValue = null;
            if (plan.updatedTimeField() != null && !plan.updatedTimeField().isBlank()) {
                if (run.kind() == RunKind.INITIAL_FULL && !catchUpPhase) {
                    timeCondition = "<";
                    timeValue = Timestamp.from(t0);
                } else if (run.kind() == RunKind.INITIAL_FULL) {
                    timeCondition = ">=";
                    timeValue = Timestamp.from(t0);
                } else {
                    // 手动增量：checkpoint 减回看窗口
                    Instant base = cursorBaseTime(cursor, plan);
                    timeCondition = ">=";
                    timeValue = Timestamp.from(base);
                }
            }
            QuerySpec query = buildQuery(plan, cursor, timeCondition, timeValue, batchSize,
                    run.kind() == RunKind.INCREMENTAL);
            List<RowWithTypes> rows = executeQuery(connection, query, plan);
            if (rows.isEmpty()) {
                if (run.kind() == RunKind.INITIAL_FULL && !catchUpPhase) {
                    catchUpPhase = true; // 全量结束，进入追赶
                    continue;
                }
                break;
            }
            List<BatchPayload> batches = assembleBatches(plan, task, rows, batchSequenceCounter);
            batchSequenceCounter += batches.size();
            for (BatchPayload batch : batches) {
                processBatch(plan, task, run, batch);
                lastConfirmedBatch = batch.batchId();
                confirmedRows += batch.rows().size();
            }
            sourceRows += rows.size();
            Map<String, Object> lastCursor = lastRowCursor(plan, rows);
            cursor = lastCursor;
            if (lastConfirmedBatch != null) {
                checkpointRepository.upsert(task.taskId(), task.version(), cursor, lastConfirmedBatch);
            }
            if (rows.size() < batchSize) {
                if (run.kind() == RunKind.INITIAL_FULL && !catchUpPhase) {
                    catchUpPhase = true;
                    continue;
                }
                break;
            }
        }
        return new RunStats(sourceRows, confirmedRows);
    }

    /**
     * 解析数据库返回的时间字符串（兼容 ISO-8601 与 openGauss/PG 的
     * {@code yyyy-MM-dd HH:mm:ss.SSSSSS+08} 格式）。
     */
    Instant parseDatabaseTime(String text) {
        if (text == null || text.isBlank()) {
            throw new RunExecutionException(
                    FailureStage.SOURCE_READ, "DATABASE_TIME_EMPTY",
                    "数据库时间为空", "无法确定快照时间，当前批次未执行", true);
        }
        String normalized = text.trim().replace(' ', 'T');
        // 时区偏移 "+08" / "-08" 补为 "+08:00" / "-08:00"（在小时后追加分钟）
        if (normalized.matches(".*[+-]\\d{2}$")) {
            normalized = normalized + ":00";
        }
        try {
            return Instant.parse(normalized);
        } catch (Exception ignored) {
            // 继续尝试带偏移解析
        }
        try {
            return OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (Exception ignored) {
            // 无时区信息时按 UTC 解析
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .toInstant(ZoneOffset.UTC);
        } catch (Exception ex) {
            throw new RunExecutionException(
                    FailureStage.SOURCE_READ, "DATABASE_TIME_PARSE_FAILED",
                    "无法解析数据库时间: " + text, "无法确定快照时间，当前批次未执行", true);
        }
    }

    private Instant cursorBaseTime(Map<String, Object> cursor, ReadPlan plan) {
        if (cursor != null && plan.updatedTimeField() != null && cursor.containsKey(plan.updatedTimeField())) {
            Object value = cursor.get(plan.updatedTimeField());
            if (value instanceof Number number) {
                return Instant.ofEpochMilli(number.longValue());
            }
            try {
                return Instant.parse(String.valueOf(value));
            } catch (Exception ignored) {
                // 回退
            }
        }
        return Instant.now().minusSeconds(INCREMENTAL_LOOKBACK_MINUTES * 60);
    }

    /** 编译读取计划。 */
    private ReadPlan compilePlan(Connection connection, SourceDatabaseAdapter adapter, TaskRecord task)
            throws SQLException {
        if ("TABLE".equals(task.readMode()) && task.readDefinition() instanceof TableReadDefinition tableDefinition) {
            TableMetadata metadata = adapter.readTableMetadata(connection, tableDefinition.schema(), tableDefinition.table());
            return tableCompiler.compile(tableDefinition, metadata);
        }
        if ("SQL".equals(task.readMode()) && task.readDefinition() instanceof SqlReadDefinition sqlDefinition) {
            SqlSafetyValidator.ValidationResult validation = sqlSafetyValidator.validate(sqlDefinition.rawSql());
            if (!validation.valid()) {
                throw new RunExecutionException(
                        FailureStage.SOURCE_READ, "UNSAFE_SQL",
                        "SQL 校验未通过: " + validation.message(),
                        "SQL 未通过安全校验，未开始读取", false);
            }
            SqlMetadataInspector.InspectionResult inspection = sqlInspector.inspect(connection, sqlDefinition.rawSql());
            return sqlCompiler.compile(sqlDefinition, inspection.columns());
        }
        throw new RunExecutionException(
                FailureStage.SOURCE_READ, "READ_DEFINITION_MISMATCH",
                "读取定义与模式不匹配", "无法生成读取计划", false);
    }

    private QuerySpec buildQuery(ReadPlan plan, Map<String, Object> cursor,
                                 String timeCondition, Object timeValue, int batchSize,
                                 boolean incrementalMode) {
        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        for (FilterCondition filter : plan.filters()) {
            conditions.add(dialect.quoteIdentifier(filter.column()) + " " + filter.operator() + " ?");
            params.add(filter.value());
        }
        // 全量/追赶使用 Keyset 分页避免重复读；
        // 手动增量只按更新时间窗口读取（历史行更新也会被覆盖，UPSERT 幂等），不使用 Keyset 下限
        if (cursor != null && !cursor.isEmpty() && !incrementalMode) {
            KeysetPredicateBuilder.KeysetPredicate predicate =
                    KeysetPredicateBuilder.buildPredicate(plan.paginationKeys(), cursor, dialect);
            if (predicate != null) {
                conditions.add("(" + predicate.sql() + ")");
                params.addAll(predicate.parameters());
            }
        }
        if (timeCondition != null && plan.updatedTimeField() != null && !plan.updatedTimeField().isBlank()) {
            conditions.add(dialect.quoteIdentifier(plan.updatedTimeField()) + " " + timeCondition + " ?");
            params.add(timeValue);
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        String orderBy = plan.paginationKeys().stream()
                .map(dialect::quoteIdentifier)
                .reduce((a, b) -> a + ", " + b)
                .orElse("1");
        String limit = " LIMIT " + batchSize;
        String sql;
        if ("TABLE".equals(plan.mode())) {
            String columns = plan.columns().stream()
                    .map(dialect::quoteIdentifier)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("*");
            String table = (plan.schema() == null || plan.schema().isBlank())
                    ? dialect.quoteIdentifier(plan.table())
                    : dialect.quoteIdentifier(plan.schema()) + "." + dialect.quoteIdentifier(plan.table());
            sql = "SELECT " + columns + " FROM " + table + where + " ORDER BY " + orderBy + limit;
        } else {
            sql = "SELECT * FROM (" + plan.previewSql() + ") mic_sync_sub" + where + " ORDER BY " + orderBy + limit;
        }
        return new QuerySpec(sql, params);
    }

    private List<RowWithTypes> executeQuery(Connection connection, QuerySpec query, ReadPlan plan) throws SQLException {
        List<RowWithTypes> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            for (int i = 0; i < query.parameters().size(); i++) {
                statement.setObject(i + 1, query.parameters().get(i));
            }
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> columnNames = new ArrayList<>();
                List<String> logicalTypes = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    columnNames.add(meta.getColumnLabel(i));
                    logicalTypes.add(LogicalTypeMapper.fromJdbcType(meta.getColumnType(i)));
                }
                while (rs.next()) {
                    List<Object> normalized = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        Object raw = rs.getObject(i);
                        normalized.add(rowNormalizer.normalize(raw, logicalTypes.get(i - 1)));
                    }
                    rows.add(new RowWithTypes(normalized, columnNames));
                }
            }
        }
        return rows;
    }

    private List<BatchPayload> assembleBatches(ReadPlan plan, TaskRecord task, List<RowWithTypes> rows,
                                                long startSequence) {
        List<String> columns = rows.isEmpty() ? plan.columns() : rows.get(0).columnNames();
        List<List<Object>> values = rows.stream().map(RowWithTypes::values).toList();
        return batchAssembler.assemble(
                Identifiers.InstanceId.generate(), // 本实例 ID 由 Sink 校验，MVP 用占位（真实 instanceId 由调用方传入）
                task.expectedSinkInstanceId() == null ? null : task.expectedSinkInstanceId(),
                task.targetDataSourceId(),
                task.taskId(),
                Identifiers.RunId.generate(),
                new BatchPayload.TargetTable(task.targetSchema(), task.targetTable()),
                columns, values,
                roleProperties.sink().maxRowsPerBatch(),
                roleProperties.sink().maxPayloadBytes(),
                startSequence);
    }

    void processBatch(ReadPlan plan, TaskRecord task, RunRecord run, BatchPayload batch) throws Exception {
        byte[] serialized = objectMapper.writeValueAsBytes(Map.of(
                "uniqueKeys", task.uniqueKeys(),
                "payload", batch));
        boolean gzip = serialized.length > GZIP_THRESHOLD;
        byte[] transportBytes = gzip ? gzip(serialized) : serialized;
        String encoding = gzip ? "GZIP" : "IDENTITY";
        String hash = sha256(transportBytes);
        StoredBatch stored = spoolStore.write(task.taskId(), run.runId(),
                batch.batchSequence(), batch.batchId(), transportBytes, encoding);
        // 持久化批次记录（PENDING）
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO batch (batch_id, run_id, batch_sequence, source_instance_id,
                    expected_sink_instance_id, payload_hash, payload_size, spool_file_size,
                    content_encoding, row_count, status, attempt_count, spool_path, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 1, ?, ?, ?)
                """,
                batch.batchId().toString(), run.runId().toString(), batch.batchSequence(),
                batch.sourceInstanceId().toString(), batch.expectedSinkInstanceId().toString(),
                hash, transportBytes.length, stored.payloadSize(), encoding,
                batch.rows() == null ? 0 : batch.rows().size(), stored.path().toString(), now, now);
        try {
            SinkTransport.SendRequest request = new SinkTransport.SendRequest(
                    task.remoteSinkUrl(), sinkTokenResolver.resolveForEndpoint(task.sinkEndpointId()),
                    batch, task.uniqueKeys(), hash, encoding);
            Outcome outcome = transport.send(request).outcome();
            if (outcome == Outcome.CONFIRMED) {
                markBatch(batch.batchId().toString(), "SUCCEEDED");
                return;
            }
            if (outcome == Outcome.BUSINESS_ERROR) {
                markBatch(batch.batchId().toString(), "FAILED");
                throw new RunExecutionException(
                        FailureStage.TARGET_WRITE, "SINK_BUSINESS_ERROR",
                        "Sink 拒绝批次: " + batch.batchId(),
                        "当前批次未确认，后续批次未执行", false);
            }
            // UNKNOWN：查询回执
            Optional<SinkTransport.ReceiptQueryResult> receipt = transport.queryReceipt(
                    new SinkTransport.ReceiptQueryRequest(task.remoteSinkUrl(),
                            sinkTokenResolver.resolveForEndpoint(task.sinkEndpointId()),
                            batch.sourceInstanceId(), batch.batchId(), task.targetDataSourceId()));
            if (receipt.isPresent() && receipt.get().found()) {
                if (hash.equals(receipt.get().payloadHash())) {
                    markBatch(batch.batchId().toString(), "SUCCEEDED"); // 已提交成功
                    return;
                }
                markBatch(batch.batchId().toString(), "FAILED");
                throw new RunExecutionException(
                        FailureStage.CONFIRMATION, "BATCH_HASH_CONFLICT",
                        "BATCH_HASH_CONFLICT: " + batch.batchId(),
                        "回执哈希与发送批次不一致，Checkpoint 未推进", false);
            }
            if (receipt.isPresent()) {
                // 确认未提交：复用原 Spool 重发（保持原 Batch ID/Hash/字节）
                Outcome retry = transport.send(request).outcome();
                if (retry == Outcome.CONFIRMED) {
                    markBatch(batch.batchId().toString(), "SUCCEEDED");
                    return;
                }
                markBatch(batch.batchId().toString(), "UNKNOWN");
                throw new RunExecutionException(
                        FailureStage.CONFIRMATION, "BATCH_OUTCOME_UNKNOWN",
                        "重发仍未知，批次保持 UNKNOWN: " + batch.batchId(),
                        "批次结果未知，Checkpoint 未推进", true);
            }
            markBatch(batch.batchId().toString(), "UNKNOWN");
            throw new RunExecutionException(
                    FailureStage.CONFIRMATION, "RECEIPT_UNREACHABLE",
                    "回执不可达，批次保持 UNKNOWN: " + batch.batchId(),
                    "无法确认批次结果，Checkpoint 未推进", true);
        } catch (RunExecutionException ex) {
            // 状态已在异常前标记；确保记录持久化
            throw ex;
        }
    }

    /** 更新批次状态。 */
    private void markBatch(String batchId, String status) {
        jdbcTemplate.update("UPDATE batch SET status = ?, updated_at = ? WHERE batch_id = ?",
                status, Instant.now().toString(), batchId);
    }

    private Map<String, Object> lastRowCursor(ReadPlan plan, List<RowWithTypes> rows) {
        Map<String, Object> cursor = new LinkedHashMap<>();
        RowWithTypes last = rows.get(rows.size() - 1);
        for (String key : plan.paginationKeys()) {
            for (int i = 0; i < last.columnNames().size(); i++) {
                if (last.columnNames().get(i).equalsIgnoreCase(key)) {
                    cursor.put(key, last.values().get(i));
                    break;
                }
            }
        }
        return cursor;
    }

    private byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 查询说明。 */
    record QuerySpec(String sql, List<Object> parameters) {
    }

    /** 运行统计。 */
    record RunStats(long sourceRows, long confirmedRows) {
    }

    /** 行数据与列名。 */
    record RowWithTypes(List<Object> values, List<String> columnNames) {
    }

    /** 运行执行失败（暂停/终止 Run）。 */
    public static class RunExecutionException extends RuntimeException {
        private final FailureStage stage;
        private final String errorCode;
        private final String impact;
        private final boolean retryable;

        public RunExecutionException(FailureStage stage, String errorCode, String message,
                                     String impact, boolean retryable) {
            super(message);
            this.stage = stage;
            this.errorCode = errorCode;
            this.impact = impact;
            this.retryable = retryable;
        }

        public FailureStage stage() {
            return stage;
        }

        public String errorCode() {
            return errorCode;
        }

        public String impact() {
            return impact;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
