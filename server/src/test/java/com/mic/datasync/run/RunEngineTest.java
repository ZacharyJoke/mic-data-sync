package com.mic.datasync.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.instance.RoleProperties;
import com.mic.datasync.run.RunFailureService.FailureStage;
import com.mic.datasync.run.RunService.RunRecord;
import com.mic.datasync.run.domain.RunStatus;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.source.domain.SqlReadDefinition;
import com.mic.datasync.source.domain.ReadPlan;
import com.mic.datasync.source.domain.ReadPlan.PaginationStrategy;
import com.mic.datasync.source.domain.IncrementalStrategy;
import com.mic.datasync.source.RowNormalizer;
import com.mic.datasync.storage.spool.BatchSpoolStore;
import com.mic.datasync.task.TaskService.TaskRecord;
import com.mic.datasync.task.domain.TaskDefinition.LifecycleStatus;
import com.mic.datasync.task.domain.TaskDefinition.WriteMode;
import com.mic.datasync.transport.SinkTransport;
import com.mic.datasync.transport.protocol.BatchPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Run 引擎批次处理测试：确认、业务错误、UNKNOWN 回执分支。
 */
@ExtendWith(MockitoExtension.class)
class RunEngineTest {

    @Mock
    private BatchSpoolStore spoolStore;
    @Mock
    private SinkTransport transport;
    @Mock
    private RunService runService;
    @Mock
    private RunFailureService runFailureService;

    private RunEngine engine;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement statement;
    @Mock
    private ResultSet resultSet;
    @Mock
    private ResultSetMetaData metaData;

    private static final Identifiers.TaskId TASK_ID = Identifiers.TaskId.generate();
    private static final Identifiers.RunId RUN_ID = Identifiers.RunId.generate();
    private static final Identifiers.BatchId BATCH_ID = Identifiers.BatchId.generate();

    @BeforeEach
    void setUp() {
        engine = newEngine(() -> "test-token");
        // 测试统一跳过退避等待（业务配置为 30s/2min/10min，由生产代码默认）
        engine.setRetryDelaySecondsForTest(new long[]{0, 0, 0});
    }

    private RunEngine newEngine(com.mic.datasync.sink.SinkTokenResolver resolver) {
        return newEngine(resolver, null);
    }

    private RunEngine newEngine(com.mic.datasync.sink.SinkTokenResolver resolver,
                                CheckpointRepository checkpointRepository) {
        RoleProperties properties = new RoleProperties("source,sink", "/tmp/x", "/tmp/x/drivers",
                new RoleProperties.Source(10, 1),
                new RoleProperties.Sink(1000, 16 * 1024 * 1024, false));
        return new RunEngine(null, null, null, null, null, null, null, new RowNormalizer(), null,
                spoolStore, transport, checkpointRepository, runService, runFailureService,
                properties, new ObjectMapper(), jdbcTemplate, resolver);
    }

    private TaskRecord task() {
        return new TaskRecord(
                TASK_ID, "patient-sync", 1, LifecycleStatus.ENABLED, "SQL",
                new SqlReadDefinition("SELECT id FROM patient", "patient", List.of("id"), "fp",
                        List.of("id"), null),
                "public", "patient", WriteMode.UPSERT,
                List.of("id"), List.of(), "http://sink:19090", null,
                Identifiers.InstanceId.generate(),
                "self-source", "self-sink", null, "sink-default",
                Instant.now(), Instant.now());
    }

    private RunRecord run() {
        return new RunRecord(RUN_ID, TASK_ID, "patient-sync", 1, RunService.RunKind.INITIAL_FULL,
                RunStatus.RUNNING, null, Instant.now(), null, 0, 0, null);
    }

    private BatchPayload batch() {
        return new BatchPayload(
                1, Identifiers.InstanceId.generate(), Identifiers.InstanceId.generate(), null,
                TASK_ID, RUN_ID, BATCH_ID, 1L,
                new BatchPayload.TargetTable("public", "patient"),
                List.of("id"), List.of(List.of(1L)),
                new BatchPayload.CheckpointCandidate(Map.of("id", 1L)));
    }

    @Test
    void parsesOpenGaussDatabaseTimeFormat() {
        // openGauss CURRENT_TIMESTAMP 返回 "yyyy-MM-dd HH:mm:ss.SSSSSS+08"
        Instant parsed = engine.parseDatabaseTime("2026-08-01 11:35:39.450707+08");
        assertThat(parsed).isEqualTo(Instant.parse("2026-08-01T03:35:39.450707Z"));
    }

    @Test
    void parsesIsoDatabaseTimeFormat() {
        Instant parsed = engine.parseDatabaseTime("2026-08-01T03:35:39.450707Z");
        assertThat(parsed).isEqualTo(Instant.parse("2026-08-01T03:35:39.450707Z"));
    }

    @Test
    void confirmedOutcomeWritesSpoolAndSends() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        when(transport.send(any())).thenReturn(new SinkTransport.SendResult(
                com.mic.datasync.transport.SinkResponseClassifier.Outcome.CONFIRMED, 200, null, null, 5));

        engine.processBatch(null, task(), run(), batch());

        verify(spoolStore).write(any(), any(), anyLong(), any(), any(), anyString());
        verify(transport).send(any());
    }

    @Test
    void businessErrorThrows() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        when(transport.send(any())).thenReturn(new SinkTransport.SendResult(
                com.mic.datasync.transport.SinkResponseClassifier.Outcome.BUSINESS_ERROR, 409, null, null, 5));

        assertThatThrownBy(() -> engine.processBatch(null, task(), run(), batch()))
                .isInstanceOf(RunEngine.RunExecutionException.class)
                .hasMessageContaining("Sink 拒绝批次");
    }

    @Test
    void businessErrorCarriesSinkReasonIntoException() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        when(transport.send(any())).thenReturn(new SinkTransport.SendResult(
                com.mic.datasync.transport.SinkResponseClassifier.Outcome.BUSINESS_ERROR,
                400, "TARGET_COLUMN_TYPE_MISMATCH", "目标字段类型不兼容", 5));

        assertThatThrownBy(() -> engine.processBatch(null, task(), run(), batch()))
                .isInstanceOf(RunEngine.RunExecutionException.class)
                .hasMessageContaining("Sink 拒绝批次")
                .hasMessageContaining("目标字段类型不兼容")
                .hasMessageContaining("TARGET_COLUMN_TYPE_MISMATCH");
    }

    @Test
    void transientNetworkFailureRetriesWithBackoffThenSucceeds() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        // 初始 + 前 2 次重试网络不可达（回执不可达），第 4 次尝试确认成功
        when(transport.send(any())).thenReturn(
                new SinkTransport.SendResult(com.mic.datasync.transport.SinkResponseClassifier.Outcome.UNKNOWN,
                        null, null, null, 5),
                new SinkTransport.SendResult(com.mic.datasync.transport.SinkResponseClassifier.Outcome.UNKNOWN,
                        null, null, null, 5),
                new SinkTransport.SendResult(com.mic.datasync.transport.SinkResponseClassifier.Outcome.UNKNOWN,
                        null, null, null, 5),
                new SinkTransport.SendResult(com.mic.datasync.transport.SinkResponseClassifier.Outcome.CONFIRMED,
                        200, null, null, 5));
        when(transport.queryReceipt(any())).thenReturn(Optional.empty());

        engine.processBatch(null, task(), run(), batch());

        verify(transport, times(4)).send(any());
        verify(transport, times(3)).queryReceipt(any());
    }

    @Test
    void networkFailureExhaustedRetriesKeepsBatchUnknown() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        when(transport.send(any())).thenReturn(new SinkTransport.SendResult(
                com.mic.datasync.transport.SinkResponseClassifier.Outcome.UNKNOWN, null, null, null, 5));
        when(transport.queryReceipt(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> engine.processBatch(null, task(), run(), batch()))
                .isInstanceOf(RunEngine.RunExecutionException.class)
                .hasMessageContaining("已自动重试 3 次仍失败")
                .satisfies(ex -> {
                    RunEngine.RunExecutionException rex =
                            (RunEngine.RunExecutionException) ex;
                    assertThat(rex.errorCode()).isEqualTo("RETRY_EXHAUSTED");
                    assertThat(rex.retryable()).isTrue();
                });

        verify(transport, times(4)).send(any());
    }

    @Test
    void businessErrorIsNotRetried() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        when(transport.send(any())).thenReturn(new SinkTransport.SendResult(
                com.mic.datasync.transport.SinkResponseClassifier.Outcome.BUSINESS_ERROR, 409, null, null, 5));

        assertThatThrownBy(() -> engine.processBatch(null, task(), run(), batch()))
                .isInstanceOf(RunEngine.RunExecutionException.class)
                .hasMessageContaining("Sink 拒绝批次");

        verify(transport, times(1)).send(any());
    }

    @Test
    void confirmationRetryExhaustionMapsToUnknownStatus() {
        RunEngine.RunExecutionException network = new RunEngine.RunExecutionException(
                FailureStage.CONFIRMATION, "RETRY_EXHAUSTED", "msg", "impact", true);
        assertThat(RunEngine.statusAfterFailure(network)).isEqualTo(RunStatus.UNKNOWN);

        RunEngine.RunExecutionException business = new RunEngine.RunExecutionException(
                FailureStage.TARGET_WRITE, "SINK_BUSINESS_ERROR", "msg", "impact", false);
        assertThat(RunEngine.statusAfterFailure(business)).isEqualTo(RunStatus.FAILED);

        // Source 侧可重试错误不进入 UNKNOWN（本次范围外的自动重试），保持 FAILED
        RunEngine.RunExecutionException source = new RunEngine.RunExecutionException(
                FailureStage.SOURCE_READ, "SOURCE_UNAVAILABLE", "msg", "impact", true);
        assertThat(RunEngine.statusAfterFailure(source)).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void resumeRecoversUnknownBatchAndAdvancesCheckpoint() throws Exception {
        CheckpointRepository repository = org.mockito.Mockito.mock(CheckpointRepository.class);
        engine = newEngine(() -> "test-token", repository);

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        byte[] transportBytes = mapper.writeValueAsBytes(
                Map.of("uniqueKeys", List.of("id"), "payload", batch()));

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of(new RunEngine.UnknownBatch(BATCH_ID, 1L, "hash-1", "IDENTITY")));
        when(spoolStore.read(any(), any(), anyLong(), any())).thenReturn(transportBytes);
        when(transport.send(any())).thenReturn(new SinkTransport.SendResult(
                com.mic.datasync.transport.SinkResponseClassifier.Outcome.CONFIRMED, 200, null, null, 5));

        engine.recoverUnknownBatches(null, task(), run());

        verify(transport).send(any());
        verify(repository).upsert(eq(TASK_ID), eq(1), anyMap(), eq(BATCH_ID));
    }

    @Test
    void unknownWithMatchingReceiptConfirms() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        // 捕获实际传输 Hash，回执返回相同 Hash
        final String[] capturedHash = {null};
        when(transport.send(any())).thenAnswer(invocation -> {
            SinkTransport.SendRequest request = invocation.getArgument(0);
            capturedHash[0] = request.payloadHash();
            return new SinkTransport.SendResult(
                    com.mic.datasync.transport.SinkResponseClassifier.Outcome.UNKNOWN, null, null, null, 5);
        });
        when(transport.queryReceipt(any())).thenAnswer(invocation ->
                Optional.of(new SinkTransport.ReceiptQueryResult(true, capturedHash[0])));

        engine.processBatch(null, task(), run(), batch());
        verify(transport).queryReceipt(any());
    }

    @Test
    void unknownWithNoReceiptRetriesSameBatch() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        // 第一次 UNKNOWN，重试 CONFIRMED
        when(transport.send(any())).thenReturn(new SinkTransport.SendResult(
                        com.mic.datasync.transport.SinkResponseClassifier.Outcome.UNKNOWN, null, null, null, 5),
                new SinkTransport.SendResult(
                        com.mic.datasync.transport.SinkResponseClassifier.Outcome.CONFIRMED, 200, null, null, 5));
        when(transport.queryReceipt(any())).thenReturn(Optional.of(
                new SinkTransport.ReceiptQueryResult(false, null)));

        engine.processBatch(null, task(), run(), batch());

        verify(transport, times(2)).send(any());
    }

    @Test
    void unknownWithUnreachableReceiptThrows() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        when(transport.send(any())).thenReturn(new SinkTransport.SendResult(
                com.mic.datasync.transport.SinkResponseClassifier.Outcome.UNKNOWN, null, null, null, 5));
        when(transport.queryReceipt(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> engine.processBatch(null, task(), run(), batch()))
                .isInstanceOf(RunEngine.RunExecutionException.class)
                .hasMessageContaining("回执不可达");
    }

    @Test
    void usesResolverTokenForSinkRequests() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-token"), "hash", 10, "IDENTITY"));
        final String[] capturedToken = {null};
        when(transport.send(any())).thenAnswer(invocation -> {
            SinkTransport.SendRequest request = invocation.getArgument(0);
            capturedToken[0] = request.token();
            return new SinkTransport.SendResult(
                    com.mic.datasync.transport.SinkResponseClassifier.Outcome.CONFIRMED, 200, null, null, 5);
        });

        newEngine(() -> "resolved-token").processBatch(null, task(), run(), batch());

        assertThat(capturedToken[0]).isEqualTo("resolved-token");
    }

    @Test
    void internalFailureRecordsStructuredDiagnosisWithoutStackOrPayload() {
        when(runService.create(any(), anyString(), anyInt(), any(), any())).thenReturn(run());

        engine.execute(task(), RunService.RunKind.INITIAL_FULL);

        verify(runFailureService).record(argThat(failure ->
                failure.stage() == FailureStage.INTERNAL
                        && failure.errorCode().equals("INTERNAL_ERROR")
                        && !failure.summary().contains("Exception")
                        && !failure.impact().isBlank()));
        verify(runService).updateStatus(
                eq(RUN_ID), eq(RunStatus.FAILED), anyString(), anyLong(), anyLong());
    }

    private ReadPlan timePlan() {
        return new ReadPlan("TABLE", "public", "patient", List.of("id", "updated_at"),
                List.of(), List.of("id"), "updated_at",
                "SELECT id, updated_at FROM patient", "fp");
    }

    private ReadPlan dualPhasePlan() {
        return new ReadPlan("TABLE", "public", "patient", List.of("id", "updated_at"),
                List.of(), List.of("id"), "updated_at",
                IncrementalStrategy.DUAL_PHASE, 1440,
                "SELECT id, updated_at FROM patient", "fp");
    }

    private ReadPlan keyOnlyPlan() {
        return new ReadPlan("TABLE", "public", "patient", List.of("id"),
                List.of(), List.of("id"), null,
                "SELECT id FROM patient", "fp");
    }

    private ReadPlan offsetPlan() {
        return new ReadPlan("TABLE", "public", "patient", List.of("id", "name"),
                List.of(), List.of(), null,
                "SELECT id, name FROM patient", "fp", PaginationStrategy.OFFSET);
    }

    @Test
    void cursorBaseTimeUsesCheckpointTimeMinusLookback() {
        Map<String, Object> cursor = Map.of("id", 1L, "updated_at", "2026-08-01T10:00:00Z");

        assertThat(RunEngine.cursorBaseTime(cursor, timePlan()))
                .contains(Instant.parse("2026-08-01T09:50:00Z"));
    }

    @Test
    void cursorBaseTimeAcceptsLocalDateTimeWithoutOffset() {
        // RowNormalizer 对 timestamp without time zone 保留本地原样输出（无 Z）
        String local = "2026-08-14T11:08:15.541806";
        Map<String, Object> cursor = Map.of("id", 70003L, "updated_time", local);

        Optional<Instant> parsed = RunEngine.parseCursorTime(cursor, "updated_time");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().atZone(ZoneId.systemDefault()).toLocalDateTime())
                .isEqualTo(LocalDateTime.parse(local));
    }

    @Test
    void cursorBaseTimeTreatsNumericTimeAsEpochMillis() {
        Map<String, Object> cursor = Map.of("id", 1L, "updated_at", 1754024400000L);

        assertThat(RunEngine.cursorBaseTime(cursor, timePlan()))
                .contains(Instant.ofEpochMilli(1754024400000L).minusSeconds(600));
    }

    @Test
    void cursorBaseTimeEmptyWhenTimeMissingNullOrUnparsable() {
        assertThat(RunEngine.cursorBaseTime(Map.of("id", 1L), timePlan())).isEmpty();
        assertThat(RunEngine.cursorBaseTime(Map.of("id", 1L, "updated_at", "not-a-time"), timePlan())).isEmpty();
        assertThat(RunEngine.cursorBaseTime(null, timePlan())).isEmpty();
    }

    @Test
    void incrementalRunStartsKeysetFromWindowStart() {
        Map<String, Object> checkpoint = Map.of("id", 1L, "updated_at", "2026-08-01T10:00:00Z");

        assertThat(RunEngine.initialKeysetCursor(RunService.RunKind.INCREMENTAL, checkpoint)).isNull();
        assertThat(RunEngine.initialKeysetCursor(RunService.RunKind.INITIAL_FULL, checkpoint)).isEqualTo(checkpoint);
    }

    @Test
    void dualPhaseIncrementalStartsKeysetFromCheckpoint() {
        Map<String, Object> checkpoint = Map.of("id", 100L, "updated_at", "2026-08-01T10:00:00Z");

        // DUAL_PHASE 阶段一从 checkpoint 主键续采，不丢"id 新时间旧"的新行
        assertThat(RunEngine.initialKeysetCursor(
                RunService.RunKind.INCREMENTAL, dualPhasePlan(), checkpoint))
                .isEqualTo(checkpoint);
        // TIME_WINDOW 策略仍从时间窗口起点重扫（历史行为）
        assertThat(RunEngine.initialKeysetCursor(
                RunService.RunKind.INCREMENTAL, timePlan(), checkpoint)).isNull();
    }

    @Test
    void cursorBaseTimeUsesConfiguredLookbackMinutes() {
        Map<String, Object> cursor = Map.of("id", 1L, "updated_at", "2026-08-01T10:00:00Z");

        // dualPhasePlan lookback=1440：窗口起点 = 检查点时间 − 1 天
        assertThat(RunEngine.cursorBaseTime(cursor, dualPhasePlan()))
                .contains(Instant.parse("2026-07-31T10:00:00Z"));
    }

    @Test
    void incrementalBaseTimePrefersMaxSeenOverCheckpoint() {
        Map<String, Object> checkpoint = Map.of("id", 1L, "updated_at", "2026-08-01T10:00:00Z");

        assertThat(RunEngine.incrementalBaseTime(
                Instant.parse("2026-08-05T12:00:00Z"), checkpoint, dualPhasePlan()))
                .contains(Instant.parse("2026-08-04T12:00:00Z"));
        // maxSeen 为空时回退检查点时间
        assertThat(RunEngine.incrementalBaseTime(null, checkpoint, dualPhasePlan()))
                .contains(Instant.parse("2026-07-31T10:00:00Z"));
    }

    @Test
    void maxRowTimeReturnsMaximumOfPageIgnoringNulls() {
        RunEngine.RowWithTypes rowOld = new RunEngine.RowWithTypes(
                List.of(1L, "2026-08-01T10:00:00Z"), List.of("id", "updated_at"));
        RunEngine.RowWithTypes rowNull = new RunEngine.RowWithTypes(
                java.util.Arrays.asList(2L, null), List.of("id", "updated_at"));
        RunEngine.RowWithTypes rowNew = new RunEngine.RowWithTypes(
                List.of(3L, "2026-08-03T10:00:00Z"), List.of("id", "updated_at"));

        assertThat(RunEngine.maxRowTime(List.of(rowOld, rowNull, rowNew), timePlan()))
                .contains(Instant.parse("2026-08-03T10:00:00Z"));
        assertThat(RunEngine.maxRowTime(List.of(rowNull), timePlan())).isEmpty();
        assertThat(RunEngine.maxRowTime(List.of(), timePlan())).isEmpty();
    }

    @Test
    void offsetRunAlwaysStartsFromScratchIgnoringCheckpoint() {
        Map<String, Object> checkpoint = Map.of("_offset", 500L);

        assertThat(RunEngine.initialKeysetCursor(RunService.RunKind.INITIAL_FULL, offsetPlan(), checkpoint))
                .isEmpty();
    }

    @Test
    void offsetQueryUsesLimitAndOffsetWithoutOrderBy() {
        RunEngine.QuerySpec first = engine.buildQuery(offsetPlan(), Map.of(), null, null, null, null, 100);
        assertThat(first.sql()).contains("LIMIT ? OFFSET ?");
        assertThat(first.sql()).doesNotContain("ORDER BY");
        assertThat(first.parameters()).containsExactly(100, 0L);

        RunEngine.QuerySpec later = engine.buildQuery(
                offsetPlan(), Map.of("_offset", 200L), null, null, null, null, 100);
        assertThat(later.parameters()).containsExactly(100, 200L);
    }

    @Test
    void offsetLastRowCursorAdvancesByPageOffset() {
        RunEngine.RowWithTypes row = new RunEngine.RowWithTypes(
                List.of(1L, "张三"), List.of("id", "name"));

        assertThat(engine.lastRowCursor(offsetPlan(), List.of(row, row, row), 100))
                .containsExactly(Map.entry("_offset", 103L));
    }

    @Test
    void lastRowCursorIncludesUpdatedTimeField() {
        RunEngine.RowWithTypes row = new RunEngine.RowWithTypes(
                List.of(1L, "2026-08-01T10:00:00Z"), List.of("id", "updated_at"));

        assertThat(engine.lastRowCursor(timePlan(), List.of(row)))
                .containsEntry("id", 1L)
                .containsEntry("updated_at", "2026-08-01T10:00:00Z");
    }

    @Test
    void lastRowCursorWithoutTimeFieldKeepsPaginationKeysOnly() {
        RunEngine.RowWithTypes row = new RunEngine.RowWithTypes(List.of(1L), List.of("id"));

        assertThat(engine.lastRowCursor(keyOnlyPlan(), List.of(row))).containsOnlyKeys("id");
    }

    @Test
    void incrementalFirstBatchQueryUsesLowerBoundWithoutUpperBound() {
        Instant lower = Instant.parse("2026-08-01T09:50:00Z");

        RunEngine.QuerySpec query = engine.buildQuery(timePlan(), null,
                ">=", Timestamp.from(lower), null, null, 100);

        assertThat(query.sql())
                .contains("\"updated_at\" >= ?")
                .contains("ORDER BY \"id\" LIMIT 100");
        // 不设上界：时间字段可能是未来时间，按当前时间截断会漏数据
        assertThat(query.sql()).doesNotContain("\"updated_at\" <= ?");
        assertThat(query.parameters()).hasSize(1);
    }

    @Test
    void incrementalLaterBatchQueryAddsKeysetPredicate() {
        Instant lower = Instant.parse("2026-08-01T09:50:00Z");

        RunEngine.QuerySpec query = engine.buildQuery(timePlan(), Map.of("id", 5L),
                ">=", Timestamp.from(lower), null, null, 100);

        assertThat(query.sql()).contains("(\"id\" > ?)");
        assertThat(query.parameters()).hasSize(2);
        assertThat(query.parameters().get(0)).isEqualTo(5L);
    }

    @Test
    void fullSyncQueryHasNoTimeUpperBound() {
        Instant t0 = Instant.parse("2026-08-01T10:00:00Z");

        RunEngine.QuerySpec query = engine.buildQuery(timePlan(), null,
                "<", Timestamp.from(t0), null, null, 100);

        // 全量快照阶段必须把时间字段为 NULL 的行一并纳入，否则首次全量会缺数据
        assertThat(query.sql()).contains("(\"updated_at\" < ? OR \"updated_at\" IS NULL)");
        assertThat(query.sql()).doesNotContain("\"updated_at\" <= ?");
        assertThat(query.parameters()).hasSize(1);
    }

    @Test
    void lastRowCursorFallsBackToLastNonNullTimeWhenLastRowIsNull() {
        RunEngine.RowWithTypes last = new RunEngine.RowWithTypes(
                java.util.Arrays.asList(3L, null), List.of("id", "updated_at"));
        RunEngine.RowWithTypes middle = new RunEngine.RowWithTypes(
                List.of(2L, "2026-08-01T09:00:00Z"), List.of("id", "updated_at"));
        RunEngine.RowWithTypes first = new RunEngine.RowWithTypes(
                List.of(1L, "2026-08-01T08:00:00Z"), List.of("id", "updated_at"));

        assertThat(engine.lastRowCursor(timePlan(), List.of(first, middle, last)))
                .containsEntry("id", 3L)
                .containsEntry("updated_at", "2026-08-01T09:00:00Z");
    }

    @Test
    void lastRowCursorOmitsTimeWhenAllRowsTimeIsNull() {
        RunEngine.RowWithTypes row = new RunEngine.RowWithTypes(
                java.util.Arrays.asList(1L, null), List.of("id", "updated_at"));

        assertThat(engine.lastRowCursor(timePlan(), List.of(row))).containsOnlyKeys("id");
    }

    @Test
    void timeWatermarkExtractsLastRowUpdatedTimeValue() {
        BatchPayload batch = new BatchPayload(
                1, Identifiers.InstanceId.generate(), Identifiers.InstanceId.generate(), null,
                TASK_ID, RUN_ID, BATCH_ID, 1L,
                new BatchPayload.TargetTable("public", "patient"),
                List.of("id", "updated_at"),
                List.of(
                        List.of(1L, "2026-10-01T09:00:00Z"),
                        List.of(2L, "2026-10-01T10:00:00Z")),
                new BatchPayload.CheckpointCandidate(Map.of("id", 2L)));

        assertThat(RunEngine.lastRowTimeWatermark(timePlan(), batch))
                .isEqualTo("2026-10-01T10:00:00Z");
    }

    @Test
    void timeWatermarkNullWhenNoTimeFieldOrLastValueNull() {
        BatchPayload batch = new BatchPayload(
                1, Identifiers.InstanceId.generate(), Identifiers.InstanceId.generate(), null,
                TASK_ID, RUN_ID, BATCH_ID, 1L,
                new BatchPayload.TargetTable("public", "patient"),
                List.of("id"),
                List.of(List.of(1L)),
                new BatchPayload.CheckpointCandidate(Map.of("id", 1L)));

        // 未配置时间字段：返回 null
        assertThat(RunEngine.lastRowTimeWatermark(keyOnlyPlan(), batch)).isNull();

        // 最后一行时间值为 null：返回 null
        BatchPayload nullTime = new BatchPayload(
                1, Identifiers.InstanceId.generate(), Identifiers.InstanceId.generate(), null,
                TASK_ID, RUN_ID, BATCH_ID, 1L,
                new BatchPayload.TargetTable("public", "patient"),
                List.of("id", "updated_at"),
                List.of(java.util.Arrays.asList(1L, null)),
                new BatchPayload.CheckpointCandidate(Map.of("id", 1L)));
        assertThat(RunEngine.lastRowTimeWatermark(timePlan(), nullTime)).isNull();
    }

    @Test
    void timeWatermarkNullWhenRowsEmpty() {
        BatchPayload batch = new BatchPayload(
                1, Identifiers.InstanceId.generate(), Identifiers.InstanceId.generate(), null,
                TASK_ID, RUN_ID, BATCH_ID, 1L,
                new BatchPayload.TargetTable("public", "patient"),
                List.of("id", "updated_at"),
                List.of(),
                new BatchPayload.CheckpointCandidate(Map.of()));

        assertThat(RunEngine.lastRowTimeWatermark(timePlan(), batch)).isNull();
    }

    @Test
    void executeQueryStopsAtPayloadByteLimit() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnLabel(1)).thenReturn("name");
        when(metaData.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(resultSet.next()).thenReturn(true, true, true, true, true, false);
        when(resultSet.getObject(1)).thenReturn("x".repeat(1024));

        RunEngine.PageResult page = engine.executeQuery(connection,
                new RunEngine.QuerySpec("SELECT \"name\" FROM t", List.of()),
                keyOnlyPlan(), 100, 2048);

        // 每行约 1KB：达到 2048 字节上限时在第 2 行截断，truncated=true
        assertThat(page.truncated()).isTrue();
        assertThat(page.rows()).hasSize(2);
    }

    @Test
    void executeQueryCompletesPageWhenUnderByteLimit() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnLabel(1)).thenReturn("name");
        when(metaData.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getObject(1)).thenReturn("a");

        RunEngine.PageResult page = engine.executeQuery(connection,
                new RunEngine.QuerySpec("SELECT \"name\" FROM t", List.of()),
                keyOnlyPlan(), 100, 2048);

        assertThat(page.truncated()).isFalse();
        assertThat(page.rows()).hasSize(2);
    }

    @Test
    void executeQueryStopsAtMaxRowsWhenPageExactlyFull() throws Exception {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnLabel(1)).thenReturn("name");
        when(metaData.getColumnType(1)).thenReturn(Types.VARCHAR);
        // 源表仍有更多行：取满 maxRows=2 后必须标记 truncated，剩余行由下一轮续读
        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getObject(1)).thenReturn("a");

        RunEngine.PageResult page = engine.executeQuery(connection,
                new RunEngine.QuerySpec("SELECT \"name\" FROM t", List.of()),
                keyOnlyPlan(), 2, 16 * 1024 * 1024);

        assertThat(page.truncated()).isTrue();
        assertThat(page.rows()).hasSize(2);
    }
}
