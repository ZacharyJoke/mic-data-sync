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
import com.mic.datasync.source.domain.IncrementalStrategy;
import com.mic.datasync.source.domain.ReadPlan;
import com.mic.datasync.source.domain.ReadPlan.PaginationStrategy;
import com.mic.datasync.source.domain.SqlReadDefinition;
import com.mic.datasync.source.domain.TableReadDefinition;
import com.mic.datasync.source.sql.SqlMetadataInspector;
import com.mic.datasync.source.sql.SqlReadPlanCompiler;
import com.mic.datasync.source.sql.SqlSafetyValidator;
import com.mic.datasync.storage.spool.BatchSpoolStore;
import com.mic.datasync.storage.spool.BatchSpoolStore.StoredBatch;
import com.mic.datasync.task.TaskService.TaskRecord;
import com.mic.datasync.task.domain.TaskDefinition;
import com.mic.datasync.transport.SinkResponseClassifier.Outcome;
import com.mic.datasync.transport.SinkTransport;
import com.mic.datasync.transport.protocol.BatchPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
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
import java.time.ZoneId;
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
import java.util.zip.GZIPInputStream;

import static com.mic.datasync.source.BatchAssembler.CURSOR_OFFSET_KEY;

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
    /** 批次网络失败退避间隔（秒）：索引 0 为第 1 次重试前等待，共 3 次重试。 */
    private static final long[] DEFAULT_RETRY_DELAY_SECONDS = {30L, 120L, 600L};

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
    /** 可重试退避间隔（测试可覆盖为 0 以跳过等待）。 */
    private volatile long[] retryDelaySeconds = DEFAULT_RETRY_DELAY_SECONDS;

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

    /** 测试专用：覆盖退避间隔（毫秒等待），0 表示不等待。 */
    void setRetryDelaySecondsForTest(long[] delays) {
        this.retryDelaySeconds = delays;
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
            // 等待重试期间被管理员暂停：保持 PAUSED 状态，不覆盖，也不记录失败诊断
            if ("RUN_PAUSED".equals(ex.errorCode())) {
                log.warn("run 因等待重试期间被暂停而停止 taskId={} taskName={} runId={} target={}.{} message={}",
                        task.taskId(), task.name(), run.runId(), task.targetSchema(), task.targetTable(),
                        ex.getMessage());
                return;
            }
            String requestId = UUID.randomUUID().toString();
            runFailureService.record(new RunFailure(
                    run.runId(), ex.stage(), ex.errorCode(), ex.getMessage(), ex.impact(),
                    requestId, ex.retryable(), Instant.now()));
            RunStatus status = statusAfterFailure(ex);
            String reason = status == RunStatus.UNKNOWN ? "批次保持 UNKNOWN，等待人工继续" : ex.getMessage();
            runService.updateStatus(run.runId(), status, reason, sourceRows, confirmedRows);
            log.error("run 执行失败 taskId={} taskName={} runId={} target={}.{} requestId={} "
                            + "stage={} code={} message={}",
                    task.taskId(), task.name(), run.runId(), task.targetSchema(), task.targetTable(),
                    requestId, ex.stage(), ex.errorCode(), ex.getMessage(), ex);
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

    /** 网络/确认类可重试失败耗尽后保持批次 UNKNOWN，确定性错误 FAILED。 */
    static RunStatus statusAfterFailure(RunExecutionException ex) {
        boolean networkUnknown = ex.retryable()
                && (ex.stage() == FailureStage.CONFIRMATION
                        || ex.stage() == FailureStage.TRANSPORT);
        return networkUnknown ? RunStatus.UNKNOWN : RunStatus.FAILED;
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
        Map<String, Object> checkpointCursor = checkpointRepository.get(task.taskId())
                .map(Checkpoint::cursorValues).orElse(null);
        // keyset 游标：全量/追赶从 checkpoint 续采；手动增量首批按策略决定
        Map<String, Object> cursor = initialKeysetCursor(run.kind(), plan, checkpointCursor);
        // maxSeen：本 Run 已见最大 updated_time（checkpoint 时间基准，窗口单调前移）
        Instant maxSeen = parseCursorTime(checkpointCursor, plan.updatedTimeField()).orElse(null);
        // 持久化到 checkpoint 的游标：DUAL_PHASE 阶段二保持阶段一主键，仅推进时间
        Map<String, Object> persistCursor = cursor == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(cursor);
        // 增量阶段：DUAL_PHASE 先按主键捕获新增，再按时间窗口补扫更新；
        // TIME_WINDOW 策略与全量均为 null（单阶段既有行为）
        IncrementalPhase incrementalPhase = run.kind() == RunKind.INCREMENTAL
                && plan.incrementalStrategy() == IncrementalStrategy.DUAL_PHASE
                ? IncrementalPhase.NEW_ROWS : null;
        // 全量：先取 T0 作为快照边界
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
        // 继续/恢复：先核对并恢复该 Run 的 UNKNOWN 批次（回执确认或复用原身份重发）
        recoverUnknownBatches(plan, task, run);

        while (true) {
            // 暂停检查：暂停后不读取新批次
            RunStatus currentStatus = runService.get(run.runId()).map(RunRecord::status).orElse(RunStatus.RUNNING);
            if (currentStatus == RunStatus.PAUSED || currentStatus == RunStatus.UNKNOWN) {
                break;
            }
            String timeCondition = null;
            Object timeValue = null;
            String timeUpperCondition = null;
            Object timeUpperValue = null;
            if (plan.updatedTimeField() != null && !plan.updatedTimeField().isBlank()) {
                if (run.kind() == RunKind.INITIAL_FULL && !catchUpPhase) {
                    timeCondition = "<";
                    timeValue = Timestamp.from(t0);
                } else if (run.kind() == RunKind.INITIAL_FULL) {
                    timeCondition = ">=";
                    timeValue = Timestamp.from(t0);
                } else if (incrementalPhase == IncrementalPhase.NEW_ROWS) {
                    // 阶段一（新增捕获）：主键 keyset 推进，不设时间过滤，
                    // 避免"id 新但时间旧"的新行被时间下界过滤漏同步
                    timeCondition = null;
                    timeValue = null;
                } else {
                    // 阶段二（更新捕获）与 TIME_WINDOW 策略：
                    // 下界 = 已见最大时间 − 回看窗口。
                    // 不设上界：时间字段（如 operate_time）可能是未来时间，
                    // 若按 Run 开始时刻截断会把未来时间的数据永久过滤掉。
                    Instant base = incrementalBaseTime(maxSeen, checkpointCursor, plan)
                            .orElseThrow(() -> new RunExecutionException(
                                    FailureStage.SOURCE_READ, "INCREMENTAL_BASELINE_MISSING",
                                    "检查点中批次最后一行的时间为空",
                                    "不允许执行手动增量操作，请先执行一次全量采集", false));
                    timeCondition = ">=";
                    timeValue = Timestamp.from(base);
                }
            }
            // 阶段二从时间窗口起点重扫（无 keyset）；其余沿用当前 keyset
            Map<String, Object> queryCursor = incrementalPhase == IncrementalPhase.TIME_WINDOW
                    ? null : cursor;
            QuerySpec query = buildQuery(plan, queryCursor, timeCondition, timeValue,
                    timeUpperCondition, timeUpperValue, batchSize);
            PageResult page = executeQuery(connection, query, plan,
                    roleProperties.sink().maxRowsPerBatch(),
                    roleProperties.sink().maxPayloadBytes());
            List<RowWithTypes> rows = page.rows();
            if (rows.isEmpty()) {
                if (run.kind() == RunKind.INITIAL_FULL && !catchUpPhase) {
                    catchUpPhase = true; // 全量结束，进入追赶
                    continue;
                }
                if (incrementalPhase == IncrementalPhase.NEW_ROWS) {
                    incrementalPhase = IncrementalPhase.TIME_WINDOW; // 新增扫完，补扫更新
                    continue;
                }
                break;
            }
            long pageOffset = plan.pagination() == PaginationStrategy.OFFSET ? offsetOf(cursor) : 0;
            List<BatchPayload> batches = assembleBatches(plan, task, run, rows, batchSequenceCounter, pageOffset);
            batchSequenceCounter += batches.size();
            for (BatchPayload batch : batches) {
                processBatch(plan, task, run, batch);
                lastConfirmedBatch = batch.batchId();
                confirmedRows += batch.rows().size();
            }
            sourceRows += rows.size();
            Map<String, Object> lastCursor = lastRowCursor(plan, rows, pageOffset);
            // maxSeen 推进：取本页已见最大时间（而非最后一行），窗口单调前移
            Optional<Instant> pageMax = maxRowTime(rows, plan);
            if (pageMax.isPresent() && (maxSeen == null || pageMax.get().isAfter(maxSeen))) {
                maxSeen = pageMax.get();
            }
            if (plan.updatedTimeField() != null && !plan.updatedTimeField().isBlank()
                    && maxSeen != null) {
                lastCursor.put(plan.updatedTimeField(), maxSeen.toEpochMilli());
            }
            cursor = lastCursor;
            if (incrementalPhase == IncrementalPhase.TIME_WINDOW) {
                // 阶段二不推进 checkpoint 主键（新增游标由阶段一推进），仅推进时间基准
                if (plan.updatedTimeField() != null && !plan.updatedTimeField().isBlank()
                        && maxSeen != null) {
                    persistCursor.put(plan.updatedTimeField(), maxSeen.toEpochMilli());
                }
            } else {
                persistCursor = new LinkedHashMap<>(lastCursor);
            }
            if (lastConfirmedBatch != null) {
                checkpointRepository.upsert(task.taskId(), task.version(),
                        persistCursor, lastConfirmedBatch);
            }
            if (!page.truncated()) {
                if (run.kind() == RunKind.INITIAL_FULL && !catchUpPhase) {
                    catchUpPhase = true;
                    continue;
                }
                if (incrementalPhase == IncrementalPhase.NEW_ROWS) {
                    incrementalPhase = IncrementalPhase.TIME_WINDOW;
                    continue;
                }
                break;
            }
        }
        return new RunStats(sourceRows, confirmedRows);
    }

    /** 读取页结果：truncated=true 表示因达到字节上限提前截断，仍有剩余行待续读。 */
    record PageResult(List<RowWithTypes> rows, boolean truncated) {

        PageResult {
            rows = rows == null ? List.of() : rows;
        }
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

    /** 从 checkpoint 游标解析已确认批次最后一行的时间（供增量基准使用）。 */
    public static Optional<Instant> parseCursorTime(Map<String, Object> cursor, String updatedTimeField) {
        if (cursor == null || updatedTimeField == null || updatedTimeField.isBlank()) {
            return Optional.empty();
        }
        Object value = cursor.get(updatedTimeField);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Number number) {
            return Optional.of(Instant.ofEpochMilli(number.longValue()));
        }
        // 带时区 ISO-8601（如 2026-08-12T03:28:11.772294Z）
        try {
            return Optional.of(Instant.parse(String.valueOf(value)));
        } catch (Exception ignored) {
            // 兼容无时区时间：timestamp without time zone 保留本地原样
            // （如 2026-08-14T11:08:15.541806），按应用时区转 Instant，
            // 与 JDBC 参数往返口径一致，避免增量基准被误判为空或偏移。
        }
        try {
            String normalized = String.valueOf(value).trim().replace(' ', 'T');
            return Optional.of(LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** 增量基准时间：已确认检查点时间减去回看窗口；不可用时返回 empty（由调用方拒绝执行）。 */
    static Optional<Instant> cursorBaseTime(Map<String, Object> cursor, ReadPlan plan) {
        return parseCursorTime(cursor, plan.updatedTimeField())
                .map(base -> base.minusSeconds(plan.incrementalLookbackMinutes() * 60));
    }

    /** 增量基准时间：优先用本 Run 已见最大时间，否则回退检查点时间，均减去回看窗口。 */
    static Optional<Instant> incrementalBaseTime(Instant maxSeen,
                                                 Map<String, Object> checkpointCursor,
                                                 ReadPlan plan) {
        return (maxSeen != null ? Optional.of(maxSeen)
                : parseCursorTime(checkpointCursor, plan.updatedTimeField()))
                .map(base -> base.minusSeconds(plan.incrementalLookbackMinutes() * 60));
    }

    /** 增量 Run 首批从时间窗口起点重扫，不携带 checkpoint 的 keyset（避免窗口内旧 key 行被跳过）。 */
    static Map<String, Object> initialKeysetCursor(RunKind kind, Map<String, Object> checkpointCursor) {
        return initialKeysetCursor(kind, null, checkpointCursor);
    }

    /**
     * 计算 Run 起始游标：
     * OFFSET 策略（REPLACE_ALL）每次新 Run 从头全量，忽略历史 checkpoint；
     * DUAL_PHASE 增量从 checkpoint 主键续采（阶段一新增捕获）；
     * TIME_WINDOW 增量从时间窗口起点重扫；其余沿用已确认 checkpoint 续采。
     */
    static Map<String, Object> initialKeysetCursor(RunKind kind, ReadPlan plan,
                                                   Map<String, Object> checkpointCursor) {
        if (plan == null) {
            // 兼容无 ReadPlan 的旧调用：增量从时间窗口起点重扫，其余沿用 checkpoint
            return kind == RunKind.INCREMENTAL ? null : checkpointCursor;
        }
        if (plan.pagination() == PaginationStrategy.OFFSET) {
            return Map.of();
        }
        if (kind == RunKind.INCREMENTAL) {
            return plan.incrementalStrategy() == IncrementalStrategy.DUAL_PHASE
                    ? checkpointCursor : null;
        }
        return checkpointCursor;
    }

    /** 增量阶段（DUAL_PHASE）：先扫新增，再按时间窗口补扫更新。 */
    private enum IncrementalPhase {
        NEW_ROWS,
        TIME_WINDOW
    }

    /** 取行集合中 updated_time 的最大值（NULL 时间行忽略；无时间字段返回 empty）。 */
    static Optional<Instant> maxRowTime(List<RowWithTypes> rows, ReadPlan plan) {
        if (rows == null || rows.isEmpty() || plan.updatedTimeField() == null
                || plan.updatedTimeField().isBlank()) {
            return Optional.empty();
        }
        Instant max = null;
        for (RowWithTypes row : rows) {
            Object value = columnValue(row, plan.updatedTimeField());
            if (value == null) {
                continue;
            }
            Optional<Instant> parsed = parseCursorTime(
                    Map.of(plan.updatedTimeField(), value), plan.updatedTimeField());
            if (parsed.isPresent() && (max == null || parsed.get().isAfter(max))) {
                max = parsed.get();
            }
        }
        return Optional.ofNullable(max);
    }

    /** 编译读取计划。 */
    private ReadPlan compilePlan(Connection connection, SourceDatabaseAdapter adapter, TaskRecord task)
            throws SQLException {
        if ("TABLE".equals(task.readMode()) && task.readDefinition() instanceof TableReadDefinition tableDefinition) {
            TableMetadata metadata = adapter.readTableMetadata(connection, tableDefinition.schema(), tableDefinition.table());
            PaginationStrategy strategy = task.writeMode() == TaskDefinition.WriteMode.REPLACE_ALL
                    ? PaginationStrategy.OFFSET : PaginationStrategy.KEYSET;
            // softUniqueAccepted=true：唯一性由启用校验层把关，运行时不再重复校验
            return tableCompiler.compile(tableDefinition, metadata, strategy, true);
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
            PaginationStrategy strategy = task.writeMode() == TaskDefinition.WriteMode.REPLACE_ALL
                    ? PaginationStrategy.OFFSET : PaginationStrategy.KEYSET;
            return sqlCompiler.compile(sqlDefinition, inspection.columns(), strategy);
        }
        throw new RunExecutionException(
                FailureStage.SOURCE_READ, "READ_DEFINITION_MISMATCH",
                "读取定义与模式不匹配", "无法生成读取计划", false);
    }

    QuerySpec buildQuery(ReadPlan plan, Map<String, Object> cursor,
                         String timeCondition, Object timeValue,
                         String timeUpperCondition, Object timeUpperValue,
                         int batchSize) {
        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        for (FilterCondition filter : plan.filters()) {
            conditions.add(dialect.quoteIdentifier(filter.column()) + " " + filter.operator() + " ?");
            params.add(filter.value());
        }
        if (plan.pagination() == PaginationStrategy.OFFSET) {
            // OFFSET 快照分页（REPLACE_ALL）：不依赖唯一键，要求同步期间源表静止
            addTimeConditions(conditions, params, plan, timeCondition, timeValue,
                    timeUpperCondition, timeUpperValue);
            String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
            String sql = selectClause(plan) + where + " LIMIT ? OFFSET ?";
            params.add(batchSize);
            params.add(offsetOf(cursor));
            return new QuerySpec(sql, params);
        }
        // 全量与手动增量统一使用 Keyset 分页避免重复读；
        // 手动增量首批由 initialKeysetCursor 传入 null（从时间窗口起点重扫）
        if (cursor != null && !cursor.isEmpty()) {
            KeysetPredicateBuilder.KeysetPredicate predicate =
                    KeysetPredicateBuilder.buildPredicate(plan.paginationKeys(), cursor, dialect);
            if (predicate != null) {
                conditions.add("(" + predicate.sql() + ")");
                params.addAll(predicate.parameters());
            }
        }
        addTimeConditions(conditions, params, plan, timeCondition, timeValue,
                timeUpperCondition, timeUpperValue);
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        String orderBy = plan.paginationKeys().stream()
                .map(dialect::quoteIdentifier)
                .reduce((a, b) -> a + ", " + b)
                .orElse("1");
        String limit = " LIMIT " + batchSize;
        return new QuerySpec(selectClause(plan) + where + " ORDER BY " + orderBy + limit, params);
    }

    /** SELECT 子句（TABLE/SQL 模式统一）。 */
    private String selectClause(ReadPlan plan) {
        if ("TABLE".equals(plan.mode())) {
            String columns = plan.columns().stream()
                    .map(dialect::quoteIdentifier)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("*");
            String table = (plan.schema() == null || plan.schema().isBlank())
                    ? dialect.quoteIdentifier(plan.table())
                    : dialect.quoteIdentifier(plan.schema()) + "." + dialect.quoteIdentifier(plan.table());
            return "SELECT " + columns + " FROM " + table;
        }
        return "SELECT * FROM (" + plan.previewSql() + ") mic_sync_sub";
    }

    /** 追加时间条件（全量快照 < / 追赶与增量 >=；NULL 时间行纳入全量快照阶段）。 */
    private void addTimeConditions(List<String> conditions, List<Object> params, ReadPlan plan,
                                   String timeCondition, Object timeValue,
                                   String timeUpperCondition, Object timeUpperValue) {
        if (timeCondition != null && plan.updatedTimeField() != null && !plan.updatedTimeField().isBlank()) {
            String quotedTime = dialect.quoteIdentifier(plan.updatedTimeField());
            if ("<".equals(timeCondition)) {
                // 全量快照阶段：时间字段为 NULL 的行（如 operate_time 未写入）也必须纳入，
                // 否则 SQL 三值逻辑会把这些行永久过滤，导致首次全量缺数据。
                conditions.add("(" + quotedTime + " < ? OR " + quotedTime + " IS NULL)");
            } else {
                conditions.add(quotedTime + " " + timeCondition + " ?");
            }
            params.add(timeValue);
        }
        if (timeUpperCondition != null && plan.updatedTimeField() != null && !plan.updatedTimeField().isBlank()) {
            conditions.add(dialect.quoteIdentifier(plan.updatedTimeField()) + " " + timeUpperCondition + " ?");
            params.add(timeUpperValue);
        }
    }

    /** 读取 OFFSET 游标值（不存在时从头开始）。 */
    private static long offsetOf(Map<String, Object> cursor) {
        if (cursor == null) {
            return 0;
        }
        Object value = cursor.get(CURSOR_OFFSET_KEY);
        return value instanceof Number number ? number.longValue() : 0;
    }

    PageResult executeQuery(Connection connection, QuerySpec query, ReadPlan plan,
                            int maxRows, long maxPayloadBytes) throws SQLException {
        List<RowWithTypes> rows = new ArrayList<>();
        long accumulatedBytes = 0;
        boolean truncated = false;
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
                    long rowBytes = BatchAssembler.estimateRowBytes(normalized);
                    // 页截断（字节预检）：若再加入这一行会超过负载上限，本页提前停止，
                    // 该行留给下一轮 keyset/OFFSET 续读。避免页内切批时"顶破上限的那一行"
                    // 单独成为 1 行尾批（每页只产出一个完整批次）。单行本身即超限的
                    // 巨行仍会单独成页，属正常行为。
                    if (!rows.isEmpty() && accumulatedBytes + rowBytes > maxPayloadBytes) {
                        truncated = true;
                        break;
                    }
                    rows.add(new RowWithTypes(normalized, columnNames));
                    accumulatedBytes += rowBytes;
                    if (rows.size() >= maxRows) {
                        // 达到单批最大行数即停止本页；取满 maxRows 也标记截断，
                        // 避免恰好取满时误判读取完成导致漏数据
                        truncated = true;
                        break;
                    }
                }
            }
        }
        return new PageResult(rows, truncated);
    }

    private List<BatchPayload> assembleBatches(ReadPlan plan, TaskRecord task, RunRecord run,
                                                List<RowWithTypes> rows,
                                                long startSequence, long pageOffset) {
        List<String> columns = rows.isEmpty() ? plan.columns() : rows.get(0).columnNames();
        List<List<Object>> values = rows.stream().map(RowWithTypes::values).toList();
        return batchAssembler.assemble(
                Identifiers.InstanceId.generate(), // 本实例 ID 由 Sink 校验，MVP 用占位（真实 instanceId 由调用方传入）
                task.expectedSinkInstanceId() == null ? null : task.expectedSinkInstanceId(),
                task.targetDataSourceId(),
                task.taskId(),
                run.runId(),
                new BatchPayload.TargetTable(task.targetSchema(), task.targetTable()),
                columns, values,
                roleProperties.sink().maxRowsPerBatch(),
                roleProperties.sink().maxPayloadBytes(),
                startSequence,
                pageOffset);
    }

    void processBatch(ReadPlan plan, TaskRecord task, RunRecord run, BatchPayload batch) throws Exception {
        byte[] serialized = objectMapper.writeValueAsBytes(Map.of(
                "uniqueKeys", task.uniqueKeys(),
                "writeMode", task.writeMode().name(),
                "payload", batch));
        boolean gzip = serialized.length > GZIP_THRESHOLD;
        byte[] transportBytes = gzip ? gzip(serialized) : serialized;
        String encoding = gzip ? "GZIP" : "IDENTITY";
        String hash = sha256(transportBytes);
        StoredBatch stored = spoolStore.write(task.taskId(), run.runId(),
                batch.batchSequence(), batch.batchId(), transportBytes, encoding);
        // 时间水位：该批次最后一行 updatedTimeField 的值（无时间字段或行为空时为空）
        String timeWatermark = lastRowTimeWatermark(plan, batch);
        // 持久化批次记录（PENDING）
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO batch (batch_id, run_id, batch_sequence, source_instance_id,
                    expected_sink_instance_id, payload_hash, payload_size, spool_file_size,
                    content_encoding, row_count, time_watermark, status, attempt_count,
                    spool_path, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 1, ?, ?, ?)
                """,
                batch.batchId().toString(), run.runId().toString(), batch.batchSequence(),
                batch.sourceInstanceId().toString(), batch.expectedSinkInstanceId().toString(),
                hash, transportBytes.length, stored.payloadSize(), encoding,
                batch.rows() == null ? 0 : batch.rows().size(), timeWatermark,
                stored.path().toString(), now, now);

        // 发送 + 确认：短暂网络故障按指数退避自动重试，确定性错误不重试。
        long[] delays = retryDelaySeconds;
        for (int attempt = 0; attempt <= delays.length; attempt++) {
            try {
                sendAndConfirm(task, run, batch, hash, encoding, transportBytes);
                return;
            } catch (RunExecutionException ex) {
                if (attempt >= delays.length) {
                    throw new RunExecutionException(
                            ex.stage(), "RETRY_EXHAUSTED",
                            ex.getMessage() + "（已自动重试 " + delays.length + " 次仍失败）",
                            ex.impact(), true);
                }
                if (!ex.retryable()) {
                    throw ex;
                }
                long delay = delays[attempt];
                log.warn("batch 发送失败待重试 attempt={}/{} taskId={} runId={} batchId={} "
                                + "delaySeconds={} code={} message={}",
                        attempt + 1, delays.length, task.taskId(), run.runId(), batch.batchId(),
                        delay, ex.errorCode(), ex.getMessage());
                if (delay > 0) {
                    runService.updateStatusOnly(run.runId(), RunStatus.WAITING_RETRY,
                            "网络故障等待重试");
                    Thread.sleep(delay * 1000);
                    // 等待期间可能被管理员暂停：暂停则不再恢复 RUNNING，批次保持 UNKNOWN
                    RunStatus current = runService.get(run.runId())
                            .map(RunRecord::status).orElse(RunStatus.RUNNING);
                    if (current == RunStatus.PAUSED) {
                        throw new RunExecutionException(
                                ex.stage(), "RUN_PAUSED",
                                "运行已被暂停，批次保持 UNKNOWN: " + batch.batchId(),
                                ex.impact(), true);
                    }
                    runService.updateStatusOnly(run.runId(), RunStatus.RUNNING, null);
                }
            }
        }
        throw new IllegalStateException("批次重试循环异常退出");
    }

    /** 发送批次并确认结果；可安全重试的失败（回执不可达/结果未知）抛 retryable 异常。 */
    private void sendAndConfirm(TaskRecord task, RunRecord run, BatchPayload batch,
                                String hash, String encoding, byte[] transportBytes) {
        SinkTransport.SendRequest request = new SinkTransport.SendRequest(
                task.remoteSinkUrl(), sinkTokenResolver.resolveForEndpoint(task.sinkEndpointId()),
                batch, task.uniqueKeys(), task.writeMode().name(), hash, encoding, transportBytes);
        SinkTransport.SendResult result = transport.send(request);
        Outcome outcome = result.outcome();
        if (outcome == Outcome.CONFIRMED) {
            markBatch(batch.batchId().toString(), "SUCCEEDED");
            return;
        }
        if (outcome == Outcome.BUSINESS_ERROR) {
            markBatch(batch.batchId().toString(), "FAILED");
            String reason = result.message() == null || result.message().isBlank()
                    ? "Sink 拒绝批次: " + batch.batchId()
                    : "Sink 拒绝批次: " + batch.batchId() + "，原因: " + result.message()
                            + (result.errorCode() == null || result.errorCode().isBlank()
                                    ? "" : "（" + result.errorCode() + "）");
            log.warn("batch 被 Sink 拒绝 taskId={} taskName={} runId={} batchId={} sequence={} "
                            + "target={}.{} rows={} httpStatus={} errorCode={} message={}",
                    task.taskId(), task.name(), run.runId(), batch.batchId(), batch.batchSequence(),
                    task.targetSchema(), task.targetTable(), batch.rows() == null ? 0 : batch.rows().size(),
                    result.httpStatus(), result.errorCode(), result.message());
            throw new RunExecutionException(
                    FailureStage.TARGET_WRITE, "SINK_BUSINESS_ERROR",
                    reason,
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
    }

    /** 更新批次状态。 */
    private void markBatch(String batchId, String status) {
        jdbcTemplate.update("UPDATE batch SET status = ?, updated_at = ? WHERE batch_id = ?",
                status, Instant.now().toString(), batchId);
    }

    /**
     * 恢复 Run 的 UNKNOWN 批次：查回执确认是否已提交，未提交则复用原 batchId/hash/Spool
     * 重发；确认成功后推进 Checkpoint。网络仍不可达时抛出可重试异常，由上层保持 UNKNOWN。
     */
    void recoverUnknownBatches(ReadPlan plan, TaskRecord task, RunRecord run) throws Exception {
        List<UnknownBatch> unknownBatches = jdbcTemplate.query("""
                SELECT batch_id, batch_sequence, payload_hash, content_encoding
                FROM batch
                WHERE run_id = ? AND status = 'UNKNOWN'
                ORDER BY batch_sequence
                """, (rs, rowNum) -> new UnknownBatch(
                        Identifiers.BatchId.fromString(rs.getString("batch_id")),
                        rs.getLong("batch_sequence"),
                        rs.getString("payload_hash"),
                        rs.getString("content_encoding")),
                run.runId().toString());
        if (unknownBatches.isEmpty()) {
            return;
        }
        log.warn("恢复 UNKNOWN 批次 taskId={} runId={} count={}",
                task.taskId(), run.runId(), unknownBatches.size());
        for (UnknownBatch unknown : unknownBatches) {
            byte[] transportBytes = spoolStore.read(task.taskId(), run.runId(),
                    unknown.sequence(), unknown.batchId());
            BatchPayload payload = decodePayload(transportBytes, unknown.contentEncoding());
            sendAndConfirm(task, run, payload, unknown.payloadHash(), unknown.contentEncoding(), transportBytes);
            // 确认成功后推进 Checkpoint 到该批次末尾（批次已标记 SUCCEEDED）
            Map<String, Object> cursor = cursorFromPayload(plan, payload);
            checkpointRepository.upsert(task.taskId(), task.version(), cursor, unknown.batchId());
        }
    }

    /** 从 Spool 传输字节反序列化批次负载（支持 GZIP）。 */
    private BatchPayload decodePayload(byte[] transportBytes, String contentEncoding) throws Exception {
        byte[] bytes = "GZIP".equals(contentEncoding) ? gunzip(transportBytes) : transportBytes;
        Map<?, ?> map = objectMapper.readValue(bytes, Map.class);
        return objectMapper.convertValue(map.get("payload"), BatchPayload.class);
    }

    /** 从批次负载的最后一行计算 Checkpoint 游标。 */
    private Map<String, Object> cursorFromPayload(ReadPlan plan, BatchPayload batch) {
        if (plan == null || batch.rows() == null || batch.rows().isEmpty()) {
            return Map.of();
        }
        if (plan.pagination() == PaginationStrategy.OFFSET) {
            Object start = batch.checkpointCandidate() == null ? null
                    : batch.checkpointCandidate().cursorValues().get(CURSOR_OFFSET_KEY);
            if (start instanceof Number number) {
                return Map.of(CURSOR_OFFSET_KEY, number.longValue() + batch.rows().size());
            }
            return Map.of();
        }
        RowWithTypes last = new RowWithTypes(batch.rows().get(batch.rows().size() - 1), batch.columns());
        return lastRowCursor(plan, List.of(last));
    }

    private byte[] gunzip(byte[] bytes) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    /** UNKNOWN 批次存储行。 */
    record UnknownBatch(Identifiers.BatchId batchId, long sequence,
                        String payloadHash, String contentEncoding) {
    }

    /** 提取批次最后一行的时间水位（无时间字段或最后一行值为空时返回 null）。 */
    static String lastRowTimeWatermark(ReadPlan plan, BatchPayload batch) {
        if (plan == null || plan.updatedTimeField() == null || plan.updatedTimeField().isBlank()
                || batch.rows() == null || batch.rows().isEmpty()) {
            return null;
        }
        int index = -1;
        for (int i = 0; i < batch.columns().size(); i++) {
            if (batch.columns().get(i).equalsIgnoreCase(plan.updatedTimeField())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return null;
        }
        Object value = batch.rows().get(batch.rows().size() - 1).get(index);
        return value == null ? null : String.valueOf(value);
    }

    Map<String, Object> lastRowCursor(ReadPlan plan, List<RowWithTypes> rows) {
        return lastRowCursor(plan, rows, 0);
    }

    Map<String, Object> lastRowCursor(ReadPlan plan, List<RowWithTypes> rows, long pageOffset) {
        if (plan.pagination() == PaginationStrategy.OFFSET) {
            return Map.of(CURSOR_OFFSET_KEY, pageOffset + (rows == null ? 0 : rows.size()));
        }
        Map<String, Object> cursor = new LinkedHashMap<>();
        RowWithTypes last = rows.get(rows.size() - 1);
        for (String key : plan.paginationKeys()) {
            cursor.put(key, columnValue(last, key));
        }
        // 已确认批次最后一行的时间一并写入游标，作为后续手动增量的时间基准
        if (plan.updatedTimeField() != null && !plan.updatedTimeField().isBlank()) {
            Object timeValue = columnValue(last, plan.updatedTimeField());
            if (timeValue == null) {
                // 全量已纳入 NULL 时间行：最后一行时间可能为 NULL。
                // 回退到该批最后一个非 NULL 时间，避免 checkpoint 时间为空导致手动增量被拒绝。
                for (int r = rows.size() - 1; r >= 0 && timeValue == null; r--) {
                    timeValue = columnValue(rows.get(r), plan.updatedTimeField());
                }
            }
            if (timeValue != null) {
                cursor.put(plan.updatedTimeField(), timeValue);
            }
        }
        return cursor;
    }

    private static Object columnValue(RowWithTypes row, String column) {
        for (int i = 0; i < row.columnNames().size(); i++) {
            if (row.columnNames().get(i).equalsIgnoreCase(column)) {
                return row.values().get(i);
            }
        }
        return null;
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
