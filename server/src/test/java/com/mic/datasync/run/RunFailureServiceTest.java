package com.mic.datasync.run;

import com.mic.datasync.run.RunFailureService.FailureStage;
import com.mic.datasync.run.RunFailureService.RunDiagnosis;
import com.mic.datasync.run.RunFailureService.RunFailure;
import com.mic.datasync.shared.id.Identifiers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RunFailureServiceTest {

    private static final Identifiers.RunId RUN_ID =
            Identifiers.RunId.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T10:05:00Z");

    @Autowired
    private RunFailureService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM run_failure");
        jdbcTemplate.update("DELETE FROM run_retry_request");
        jdbcTemplate.update("DELETE FROM batch");
        jdbcTemplate.update("DELETE FROM run");
        jdbcTemplate.update("DELETE FROM task");
        insertTask();
        insertRun();
    }

    @Test
    void recordsSafeStructuredFailureWithoutStackOrPayload() {
        service.record(new RunFailure(
                RUN_ID, FailureStage.TARGET_WRITE, "TARGET_COLUMN_TYPE_MISMATCH",
                "目标字段类型不兼容", "当前批次未确认，后续批次未执行",
                "req-123", true, NOW));

        RunDiagnosis diagnosis = service.diagnosis(RUN_ID).orElseThrow();

        assertThat(diagnosis.summary()).isEqualTo("目标字段类型不兼容");
        assertThat(diagnosis.impact()).contains("当前批次未确认");
        assertThat(diagnosis.requestId()).isEqualTo("req-123");
        assertThat(diagnosis.stage()).isEqualTo("TARGET_WRITE");
        assertThat(diagnosis.retryable()).isTrue();
        assertThat(diagnosis.suggestedActions())
                .extracting("type")
                .containsExactly("OPEN_TASK_CONFIG", "REVALIDATE");
    }

    @Test
    void recordIsIdempotentPerRun() {
        service.record(new RunFailure(
                RUN_ID, FailureStage.SOURCE_READ, "SOURCE_READ_FAILED",
                "读取失败", "未读取批次", "req-1", true, NOW));
        service.record(new RunFailure(
                RUN_ID, FailureStage.TARGET_WRITE, "TARGET_WRITE_FAILED",
                "写入失败", "批次未确认", "req-2", false, NOW));

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM run_failure WHERE run_id = ?", Long.class, RUN_ID.toString());
        assertThat(count).isEqualTo(1);
        RunDiagnosis diagnosis = service.diagnosis(RUN_ID).orElseThrow();
        assertThat(diagnosis.code()).isEqualTo("TARGET_WRITE_FAILED");
    }

    private void insertTask() {
        jdbcTemplate.update("""
                INSERT INTO task (
                    task_id, name, version, lifecycle_status, read_mode, read_definition,
                    target_schema, target_table, write_mode, unique_keys, field_mappings,
                    remote_sink_url, sink_token_ref, expected_sink_instance_id, created_at, updated_at)
                VALUES ('00000000-0000-0000-0000-000000000001', 'patient-sync', 1, 'ENABLED',
                    'TABLE', '{"schema":"public","table":"patient"}', 'public', 'patient_copy',
                    'UPSERT', '["id"]', '[]', 'http://sink:19090', 'sink-token', NULL,
                    '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z')
                """);
    }

    private void insertRun() {
        jdbcTemplate.update("""
                INSERT INTO run (
                    run_id, task_id, task_name_snapshot, task_version, kind, status,
                    pause_reason, started_at, ended_at, source_row_count, confirmed_row_count, created_at)
                VALUES (?, '00000000-0000-0000-0000-000000000001', 'patient-sync', 1,
                    'MANUAL', 'FAILED', NULL, ?, ?, 0, 0, ?)
                """, RUN_ID.toString(), NOW.toString(), NOW.toString(), NOW.toString());
    }
}
