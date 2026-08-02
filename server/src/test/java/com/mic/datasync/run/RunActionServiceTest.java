package com.mic.datasync.run;

import com.mic.datasync.run.RunActionService.RunActions;
import com.mic.datasync.run.RunFailureService.FailureStage;
import com.mic.datasync.run.RunFailureService.RunFailure;
import com.mic.datasync.run.RunService.RunRecord;
import com.mic.datasync.shared.command.CommandResult;
import com.mic.datasync.shared.id.Identifiers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RunActionServiceTest {

    private static final Identifiers.TaskId TASK_ID =
            Identifiers.TaskId.fromString("00000000-0000-0000-0000-000000000001");
    private static final Identifiers.RunId ORIGINAL_RUN_ID =
            Identifiers.RunId.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T10:05:00Z");

    @Autowired
    private RunActionService service;

    @Autowired
    private RunService runService;

    @Autowired
    private RunFailureService failureService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM run_retry_request");
        jdbcTemplate.update("DELETE FROM run_failure");
        jdbcTemplate.update("DELETE FROM batch");
        jdbcTemplate.update("DELETE FROM run");
        jdbcTemplate.update("DELETE FROM task");
        insertTask();
        insertRun(ORIGINAL_RUN_ID, "FAILED", null);
    }

    @Test
    void failedRetryableRunOffersRetryAndRevalidate() {
        failureService.record(failure(true));

        RunActions actions = service.actions(ORIGINAL_RUN_ID);

        assertThat(actions.actions()).extracting(RunActionService.RunAction::type)
                .containsExactlyInAnyOrder("PAUSE", "RESUME", "REVALIDATE", "RETRY");
        assertThat(actions.actions()).filteredOn(action -> action.type().equals("RETRY"))
                .singleElement()
                .satisfies(action -> assertThat(action.enabled()).isTrue());
    }

    @Test
    void nonRetryableFailureDoesNotOfferRetry() {
        failureService.record(failure(false));

        RunActions actions = service.actions(ORIGINAL_RUN_ID);

        assertThat(actions.actions()).filteredOn(action -> action.type().equals("RETRY"))
                .singleElement()
                .satisfies(action -> assertThat(action.enabled()).isFalse());
    }

    @Test
    void sameActorRunAndIdempotencyKeyReturnsSameNewRun() {
        failureService.record(failure(true));

        CommandResult first = service.retry(ORIGINAL_RUN_ID, "admin", "retry-001");
        CommandResult second = service.retry(ORIGINAL_RUN_ID, "admin", "retry-001");

        assertThat(second.resourceId()).isEqualTo(first.resourceId());
        assertThat(runCountForPrevious(ORIGINAL_RUN_ID)).isEqualTo(1);
    }

    @Test
    void retryCreatesNewRunAndKeepsOriginalImmutable() {
        failureService.record(failure(true));

        RunRecord originalBefore = runService.get(ORIGINAL_RUN_ID).orElseThrow();
        CommandResult result = service.retry(ORIGINAL_RUN_ID, "admin", "retry-002");
        RunRecord retried = runService.get(
                Identifiers.RunId.fromString(result.resourceId())).orElseThrow();

        assertThat(retried.previousRunId()).isEqualTo(ORIGINAL_RUN_ID);
        assertThat(runService.get(ORIGINAL_RUN_ID)).contains(originalBefore);
        assertThat(runCountForPrevious(ORIGINAL_RUN_ID)).isEqualTo(1);
    }

    private RunFailure failure(boolean retryable) {
        return new RunFailure(
                ORIGINAL_RUN_ID,
                FailureStage.TRANSPORT,
                "RECEIPT_UNREACHABLE",
                "回执不可达",
                "无法确认批次结果",
                "req-123",
                retryable,
                NOW);
    }

    private long runCountForPrevious(Identifiers.RunId previousRunId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM run WHERE previous_run_id = ?",
                Long.class,
                previousRunId.toString());
        return count == null ? 0 : count;
    }

    private void insertTask() {
        jdbcTemplate.update("""
                INSERT INTO task (
                    task_id, name, version, lifecycle_status, read_mode, read_definition,
                    target_schema, target_table, write_mode, unique_keys, field_mappings,
                    remote_sink_url, sink_token_ref, expected_sink_instance_id, created_at, updated_at)
                VALUES (?, 'patient-sync', 1, 'ENABLED', 'TABLE',
                    '{"schema":"public","table":"patient"}', 'public', 'patient_copy',
                    'UPSERT', '["id"]', '[]', 'http://sink:19090', 'sink-token', NULL,
                    '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z')
                """, TASK_ID.toString());
    }

    private void insertRun(Identifiers.RunId runId, String status, Identifiers.RunId previousRunId) {
        jdbcTemplate.update("""
                INSERT INTO run (
                    run_id, task_id, task_name_snapshot, task_version, kind, status,
                    pause_reason, started_at, ended_at, source_row_count, confirmed_row_count,
                    created_at, previous_run_id)
                VALUES (?, ?, 'patient-sync', 1, 'INCREMENTAL', ?, NULL, ?, ?, 0, 0, ?, ?)
                """,
                runId.toString(), TASK_ID.toString(), status, NOW.toString(),
                "FAILED".equals(status) ? NOW.toString() : null,
                NOW.toString(),
                previousRunId == null ? null : previousRunId.toString());
    }
}
