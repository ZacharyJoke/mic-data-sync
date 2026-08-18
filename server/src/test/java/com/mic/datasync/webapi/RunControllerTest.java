package com.mic.datasync.webapi;

import com.mic.datasync.run.RunFailureService;
import com.mic.datasync.run.RunFailureService.FailureStage;
import com.mic.datasync.run.RunFailureService.RunFailure;
import com.mic.datasync.run.RunService.RunKind;
import com.mic.datasync.run.RunService.RunRecord;
import com.mic.datasync.run.domain.RunStatus;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.source.IncrementalSyncExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RunControllerTest {

    private static final String TABLE_DEFINITION = """
            {"schema":"public","table":"patient","selectedColumns":["id"],"filters":[],
             "paginationKeys":["id"],"updatedTimeField":"updated_at"}
            """;

    private static final String TABLE_DEFINITION_WITHOUT_TIME_FIELD = """
            {"schema":"public","table":"patient","selectedColumns":["id"],"filters":[],
             "paginationKeys":["id"],"updatedTimeField":null}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RunFailureService runFailureService;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private IncrementalSyncExecutor incrementalSyncExecutor;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM checkpoint");
        jdbcTemplate.update("DELETE FROM batch");
        jdbcTemplate.update("DELETE FROM run");
        jdbcTemplate.update("DELETE FROM task");
    }

    @Test
    void runListPaginatesAtDatabaseLevelWithFiltersAndFractionalOrdering() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000001", "patient-alpha",
                "2026-08-01T10:00:00Z");
        insertTask("00000000-0000-0000-0000-000000000002", "orders",
                "2026-08-01T09:00:00Z");
        insertRun("10000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000001", "patient-alpha", "MANUAL", "FAILED",
                "2026-08-01T10:00:00Z", "2026-08-01T10:01:00Z");
        insertRun("10000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000001", "patient-alpha", "INCREMENTAL", "SUCCEEDED",
                "2026-08-01T11:00:00.500Z", "2026-08-01T11:01:00.500Z");
        insertRun("10000000-0000-0000-0000-000000000003",
                "00000000-0000-0000-0000-000000000002", "orders", "INITIAL_FULL", "FAILED",
                "2026-08-01T09:00:00Z", "2026-08-01T09:01:00Z");

        mockMvc.perform(get("/api/v1/runs")
                        .with(user("admin").roles("ADMIN"))
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items[0].runId")
                        .value("10000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("$.items[1].runId")
                        .value("10000000-0000-0000-0000-000000000001"));

        assertSingleFilteredRun("status", "FAILED", 2);
        assertSingleFilteredRun("taskId", "00000000-0000-0000-0000-000000000001", 2);
        assertSingleFilteredRun("kind", "INITIAL_FULL", 1);
        assertSingleFilteredRun("keyword", "ALPHA", 2);

        mockMvc.perform(get("/api/v1/runs")
                        .with(user("admin").roles("ADMIN"))
                        .param("startedFrom", "2026-08-01T10:30:00Z")
                        .param("startedTo", "2026-08-01T11:30:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].runId")
                        .value("10000000-0000-0000-0000-000000000002"));
    }

    @Test
    void taskRunHistoryPaginatesWithinTaskScope() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000001", "patient-alpha",
                "2026-08-01T10:00:00Z");
        insertTask("00000000-0000-0000-0000-000000000002", "orders",
                "2026-08-01T09:00:00Z");
        insertRun("10000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000001", "patient-alpha", "MANUAL", "FAILED",
                "2026-08-01T10:00:00Z", "2026-08-01T10:01:00Z");
        insertRun("10000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000001", "patient-alpha", "INCREMENTAL", "SUCCEEDED",
                "2026-08-01T11:00:00Z", "2026-08-01T11:01:00Z");
        insertRun("10000000-0000-0000-0000-000000000003",
                "00000000-0000-0000-0000-000000000001", "patient-alpha", "MANUAL", "FAILED",
                "2026-08-01T12:00:00Z", "2026-08-01T12:01:00Z");
        insertRun("10000000-0000-0000-0000-000000000004",
                "00000000-0000-0000-0000-000000000002", "orders", "INITIAL_FULL", "FAILED",
                "2026-08-01T09:00:00Z", "2026-08-01T09:01:00Z");

        mockMvc.perform(get("/api/v1/tasks/00000000-0000-0000-0000-000000000001/runs")
                        .with(user("admin").roles("ADMIN"))
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].taskId")
                        .value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.items[0].runId")
                        .value("10000000-0000-0000-0000-000000000001"));
    }

    @Test
    void runDetailUsesDirectQuery() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000001", "patient-alpha",
                "2026-08-01T10:00:00Z");
        insertRun("10000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000001", "patient-alpha", "MANUAL", "FAILED",
                "2026-08-01T10:00:00Z", "2026-08-01T10:01:00Z");

        mockMvc.perform(get("/api/v1/runs/10000000-0000-0000-0000-000000000001")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskName").value("patient-alpha"))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void runBatchesPaginateBySequence() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000001", "patient-alpha",
                "2026-08-01T10:00:00Z");
        insertRun("10000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000001", "patient-alpha", "MANUAL", "SUCCEEDED",
                "2026-08-01T10:00:00Z", "2026-08-01T10:05:00Z");
        insertBatch("20000000-0000-0000-0000-000000000001",
                "10000000-0000-0000-0000-000000000001", 1, 10, "SUCCEEDED");
        insertBatch("20000000-0000-0000-0000-000000000002",
                "10000000-0000-0000-0000-000000000001", 2, 20, "SUCCEEDED");
        insertBatch("20000000-0000-0000-0000-000000000003",
                "10000000-0000-0000-0000-000000000001", 3, 30, "FAILED",
                "2026-08-01T10:04:59Z");

        mockMvc.perform(get("/api/v1/runs/10000000-0000-0000-0000-000000000001/batches")
                        .with(user("admin").roles("ADMIN"))
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].batchSequence").value(3))
                .andExpect(jsonPath("$.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].rowCount").value(30))
                .andExpect(jsonPath("$.items[0].timeWatermark").value("2026-08-01T10:04:59Z"));
    }

    @Test
    void invalidPageSizeAndFiltersReturnFieldSpecificErrors() throws Exception {
        assertInvalidPageRequest("/api/v1/runs", "page", "abc");
        assertInvalidPageRequest("/api/v1/runs", "size", "101");
        assertInvalidFilter("/api/v1/runs", "status", "BROKEN", "status");
        assertInvalidFilter("/api/v1/runs", "taskId", "not-a-uuid", "taskId");
        assertInvalidFilter("/api/v1/runs", "kind", "STREAM", "kind");
        assertInvalidFilter("/api/v1/runs", "startedFrom", "not-a-time", "startedFrom");
        assertInvalidPageRequest(
                "/api/v1/runs/10000000-0000-0000-0000-000000000001/batches",
                "page", "0");
    }

    @Test
    void diagnosisReturnsStructuredFailure() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000001", "patient-alpha",
                "2026-08-01T10:00:00Z");
        insertRun("10000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000001", "patient-alpha", "MANUAL", "FAILED",
                "2026-08-01T10:00:00Z", "2026-08-01T10:05:00Z");
        runFailureService.record(new RunFailure(
                Identifiers.RunId.fromString("10000000-0000-0000-0000-000000000001"),
                FailureStage.TARGET_WRITE,
                "TARGET_COLUMN_TYPE_MISMATCH",
                "目标字段类型不兼容",
                "当前批次未确认，后续批次未执行",
                "req-123",
                true,
                Instant.parse("2026-08-01T10:05:00Z")));

        mockMvc.perform(get("/api/v1/runs/10000000-0000-0000-0000-000000000001/diagnosis")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("TARGET_WRITE"))
                .andExpect(jsonPath("$.code").value("TARGET_COLUMN_TYPE_MISMATCH"))
                .andExpect(jsonPath("$.summary").value("目标字段类型不兼容"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.requestId").value("req-123"))
                .andExpect(jsonPath("$.suggestedActions[0].type").isString());
    }

    @Test
    void actionsExposeCapabilityDrivenRetry() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000001", "patient-alpha",
                "2026-08-01T10:00:00Z");
        insertRun("10000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000001", "patient-alpha", "MANUAL", "FAILED",
                "2026-08-01T10:00:00Z", "2026-08-01T10:05:00Z");
        runFailureService.record(new RunFailure(
                Identifiers.RunId.fromString("10000000-0000-0000-0000-000000000001"),
                FailureStage.TRANSPORT,
                "RECEIPT_UNREACHABLE",
                "回执不可达",
                "无法确认批次结果",
                "req-123",
                true,
                Instant.parse("2026-08-01T10:05:00Z")));

        mockMvc.perform(get("/api/v1/runs/10000000-0000-0000-0000-000000000001/actions")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[*].type", hasItem("RETRY")))
                .andExpect(jsonPath("$.actions[*].type", hasItem("REVALIDATE")))
                .andExpect(jsonPath("$.actions[?(@.type == 'RETRY')].enabled").value(true));
    }

    @Test
    void retryReturnsAcceptedCommandResult() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000001", "patient-alpha",
                "2026-08-01T10:00:00Z");
        insertRun("10000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000001", "patient-alpha", "MANUAL", "FAILED",
                "2026-08-01T10:00:00Z", "2026-08-01T10:05:00Z");
        runFailureService.record(new RunFailure(
                Identifiers.RunId.fromString("10000000-0000-0000-0000-000000000001"),
                FailureStage.TRANSPORT,
                "RECEIPT_UNREACHABLE",
                "回执不可达",
                "无法确认批次结果",
                "req-123",
                true,
                Instant.parse("2026-08-01T10:05:00Z")));

        mockMvc.perform(post("/api/v1/runs/10000000-0000-0000-0000-000000000001/retry")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .header("Idempotency-Key", "retry-001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.resourceId").isString())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void manualIncrementalRejectedWhenTaskHasNoUpdatedTimeField() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000001", "patient-no-time-field",
                "2026-08-01T10:00:00Z", TABLE_DEFINITION_WITHOUT_TIME_FIELD);

        mockMvc.perform(post("/api/v1/tasks/00000000-0000-0000-0000-000000000001/runs/incremental")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message")
                        .value("由于没有配置更新时间字段，不允许执行手动增量操作"));
    }

    @Test
    void manualIncrementalRejectedForInsertOnlyTask() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000001", "patient-insert-only",
                "2026-08-01T10:00:00Z", TABLE_DEFINITION, "INSERT_ONLY", "[]");

        mockMvc.perform(post("/api/v1/tasks/00000000-0000-0000-0000-000000000001/runs/incremental")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message")
                        .value("由于没有配置唯一 Key，不允许执行手动增量操作"));
    }

    @Test
    void manualIncrementalRejectedWhenCheckpointHasNoTime() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000001", "patient-alpha",
                "2026-08-01T10:00:00Z", TABLE_DEFINITION);

        mockMvc.perform(post("/api/v1/tasks/00000000-0000-0000-0000-000000000001/runs/incremental")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message")
                        .value("检查点中批次最后一行的时间为空，不允许执行手动增量操作，请先执行一次全量采集"));
    }

    @Test
    void manualIncrementalAcceptedWhenUpsertTaskHasCheckpointTime() throws Exception {
        String taskId = "00000000-0000-0000-0000-000000000001";
        insertTask(taskId, "patient-alpha", "2026-08-01T10:00:00Z", TABLE_DEFINITION);
        insertCheckpoint(taskId, "{\"id\":100,\"updated_at\":\"2026-08-01T10:00:00Z\"}");
        RunRecord run = new RunRecord(
                Identifiers.RunId.generate(), Identifiers.TaskId.fromString(taskId), "patient-alpha",
                1, RunKind.INCREMENTAL, RunStatus.RUNNING,
                null, Instant.parse("2026-08-01T10:06:00Z"), null, 0, 0, null);
        when(incrementalSyncExecutor.start(any())).thenReturn(run);

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/runs/incremental")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.resourceId").isString());
        verify(incrementalSyncExecutor).start(any());
        // mock 返回预构造 Run，不落库、不异步执行
        Integer runCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM run WHERE task_id = ?", Integer.class, taskId);
        assertThat(runCount).isEqualTo(1);
    }

    @Test
    void manualIncrementalAcceptedForNoOverwriteTaskWithCheckpointTime() throws Exception {
        String taskId = "00000000-0000-0000-0000-000000000002";
        insertTask(taskId, "patient-no-overwrite", "2026-08-01T10:00:00Z",
                TABLE_DEFINITION, "UPSERT_NO_OVERWRITE", "[\"id\"]");
        insertCheckpoint(taskId, "{\"id\":100,\"updated_at\":\"2026-08-01T10:00:00Z\"}");
        RunRecord run = new RunRecord(
                Identifiers.RunId.generate(), Identifiers.TaskId.fromString(taskId), "patient-no-overwrite",
                1, RunKind.INCREMENTAL, RunStatus.RUNNING,
                null, Instant.parse("2026-08-01T10:06:00Z"), null, 0, 0, null);
        when(incrementalSyncExecutor.start(any())).thenReturn(run);

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/runs/incremental")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isAccepted());
        verify(incrementalSyncExecutor).start(any());
    }

    private void assertSingleFilteredRun(String parameter, String value, int total) throws Exception {
        mockMvc.perform(get("/api/v1/runs")
                        .with(user("admin").roles("ADMIN"))
                        .param(parameter, value))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(total))
                .andExpect(jsonPath("$.items.length()").value(total));
    }

    private void assertInvalidPageRequest(String url, String parameter, String value) throws Exception {
        mockMvc.perform(get(url)
                        .with(user("admin").roles("ADMIN"))
                        .param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_REQUEST"))
                .andExpect(jsonPath("$.details.field").value(parameter));
    }

    private void assertInvalidFilter(String url, String parameter, String value, String field)
            throws Exception {
        mockMvc.perform(get(url)
                        .with(user("admin").roles("ADMIN"))
                        .param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILTER_VALUE"))
                .andExpect(jsonPath("$.details.field").value(field));
    }

    private void insertTask(String taskId, String name, String updatedAt) {
        insertTask(taskId, name, updatedAt, TABLE_DEFINITION);
    }

    private void insertTask(String taskId, String name, String updatedAt, String readDefinition) {
        insertTask(taskId, name, updatedAt, readDefinition, "UPSERT", "[\"id\"]");
    }

    private void insertTask(String taskId, String name, String updatedAt, String readDefinition,
                            String writeMode, String uniqueKeys) {
        jdbcTemplate.update("""
                INSERT INTO task (
                    task_id, name, version, lifecycle_status, read_mode, read_definition,
                    target_schema, target_table, write_mode, unique_keys, field_mappings,
                    remote_sink_url, sink_token_ref, expected_sink_instance_id, created_at, updated_at)
                VALUES (?, ?, 1, 'ENABLED', 'TABLE', ?, 'public', 'patient_copy', ?, ?,
                    ?, 'http://sink:19090', 'sink-token', NULL, ?, ?)
                """,
                taskId, name, readDefinition, writeMode, uniqueKeys,
                "[{\"sourceField\":\"id\",\"targetField\":\"id\"}]", updatedAt, updatedAt);
    }

    private void insertRun(String runId, String taskId, String taskName, String kind, String status,
                           String startedAt, String endedAt) {
        jdbcTemplate.update("""
                INSERT INTO run (
                    run_id, task_id, task_name_snapshot, task_version, kind, status,
                    pause_reason, started_at, ended_at, source_row_count, confirmed_row_count, created_at)
                VALUES (?, ?, ?, 1, ?, ?, NULL, ?, ?, 0, 0, ?)
                """, runId, taskId, taskName, kind, status, startedAt, endedAt, startedAt);
    }

    private void insertBatch(String batchId, String runId, long sequence, long rowCount, String status) {
        insertBatch(batchId, runId, sequence, rowCount, status, null);
    }

    private void insertBatch(
            String batchId, String runId, long sequence, long rowCount, String status,
            String timeWatermark) {
        String createdAt = "2026-08-01T10:01:00Z";
        jdbcTemplate.update("""
                INSERT INTO batch (
                    batch_id, run_id, batch_sequence, source_instance_id, expected_sink_instance_id,
                    payload_hash, payload_size, content_encoding, row_count, time_watermark,
                    status, attempt_count, created_at, updated_at)
                VALUES (?, ?, ?, 'source-instance', 'sink-instance', 'payload-hash', 100,
                    'IDENTITY', ?, ?, ?, 1, ?, ?)
                """, batchId, runId, sequence, rowCount, timeWatermark, status, createdAt, createdAt);
    }

    private void insertCheckpoint(String taskId, String cursorJson) {
        String batchId = "30000000-0000-0000-0000-000000000001";
        String runId = "10000000-0000-0000-0000-000000000001";
        insertRun(runId, taskId, "patient-alpha", "INITIAL_FULL", "SUCCEEDED",
                "2026-08-01T10:00:00Z", "2026-08-01T10:05:00Z");
        insertBatch(batchId, runId, 1, 1, "SUCCEEDED");
        jdbcTemplate.update("""
                INSERT INTO checkpoint (task_id, task_version, cursor_values, confirmed_batch_id, confirmed_at, updated_at)
                VALUES (?, 1, ?, ?, ?, ?)
                """, taskId, cursorJson, batchId, "2026-08-01T10:05:00Z", "2026-08-01T10:05:00Z");
    }
}
