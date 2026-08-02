package com.mic.datasync.dashboard;

import com.mic.datasync.dashboard.DashboardService.DashboardSummary;
import com.mic.datasync.dashboard.DashboardService.FailureAlert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DashboardServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T05:00:00Z");

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM batch");
        jdbcTemplate.update("DELETE FROM run");
        jdbcTemplate.update("DELETE FROM task");
    }

    @Test
    void calculatesTodaySuccessRateUsingTerminalSuccessAndFailureOnly() {
        insertTask("00000000-0000-0000-0000-000000000001", "task-a", "ENABLED");
        insertTask("00000000-0000-0000-0000-000000000002", "task-b", "ENABLED");
        insertTask("00000000-0000-0000-0000-000000000003", "task-c", "ENABLED");
        insertTask("00000000-0000-0000-0000-000000000004", "task-d", "ENABLED");
        insertRun("10000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000001", "SUCCEEDED",
                "2026-08-01T01:00:00Z", null);
        insertRun("10000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000002", "FAILED",
                "2026-08-01T02:00:00Z", "oops");
        insertRun("10000000-0000-0000-0000-000000000003",
                "00000000-0000-0000-0000-000000000003", "RUNNING",
                "2026-08-01T03:00:00Z", null);
        insertRun("10000000-0000-0000-0000-000000000004",
                "00000000-0000-0000-0000-000000000004", "CANCELLED",
                "2026-08-01T04:00:00Z", null);

        DashboardSummary summary = dashboardService.summary(NOW);

        assertThat(summary.todaySuccessRate()).isEqualByComparingTo("0.5");
    }

    @Test
    void latestSuccessfulRunRemovesTaskFromUnresolvedFailures() {
        insertTask("00000000-0000-0000-0000-000000000001", "task-a", "ENABLED");
        insertRun("10000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000001", "FAILED",
                "2026-08-01T01:00:00Z", "oops");
        insertRun("10000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000001", "SUCCEEDED",
                "2026-08-01T02:00:00Z", null);

        assertThat(dashboardService.summary(NOW).unresolvedFailureCount()).isZero();
    }

    @Test
    void latestFailedRunAppearsAsAlert() {
        insertTask("00000000-0000-0000-0000-000000000001", "task-a", "ENABLED");
        insertTask("00000000-0000-0000-0000-000000000002", "task-b", "ENABLED");
        insertRun("10000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000001", "FAILED",
                "2026-08-01T01:00:00Z", "目标字段类型不兼容");
        insertRun("10000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000001", "SUCCEEDED",
                "2026-08-01T02:00:00Z", null);
        insertRun("10000000-0000-0000-0000-000000000003",
                "00000000-0000-0000-0000-000000000002", "FAILED",
                "2026-08-01T03:00:00Z", null);

        DashboardSummary summary = dashboardService.summary(NOW);

        assertThat(summary.unresolvedFailureCount()).isEqualTo(1);
        assertThat(summary.alerts()).hasSize(1);
        FailureAlert alert = summary.alerts().get(0);
        assertThat(alert.runId()).isEqualTo("10000000-0000-0000-0000-000000000003");
        assertThat(alert.summary()).isEqualTo("运行失败");
        assertThat(alert.severity()).isEqualTo("ERROR");
        assertThat(alert.stage()).isEqualTo("UNKNOWN");
    }

    @Test
    void summaryCountsEnabledTasksActiveRunsAndRecentRuns() {
        insertTask("00000000-0000-0000-0000-000000000001", "task-a", "ENABLED");
        insertTask("00000000-0000-0000-0000-000000000002", "task-draft", "DRAFT");
        insertRun("10000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000001", "RUNNING",
                "2026-08-01T01:00:00Z", null);
        insertRun("10000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000001", "PAUSED",
                "2026-08-01T02:00:00Z", null);
        insertRun("10000000-0000-0000-0000-000000000003",
                "00000000-0000-0000-0000-000000000001", "SUCCEEDED",
                "2026-08-01T03:00:00Z", null);

        DashboardSummary summary = dashboardService.summary(NOW);

        assertThat(summary.enabledTaskCount()).isEqualTo(1);
        assertThat(summary.activeRunCount()).isEqualTo(2);
        assertThat(summary.recentRuns()).extracting("runId")
                .containsExactly(
                        "10000000-0000-0000-0000-000000000003",
                        "10000000-0000-0000-0000-000000000002",
                        "10000000-0000-0000-0000-000000000001");
        assertThat(summary.statisticsFrom()).isNotNull();
        assertThat(summary.statisticsTo()).isNotNull();
    }

    @Test
    void emptyDatabaseHasNullSuccessRateAndZeroCounts() {
        DashboardSummary summary = dashboardService.summary(NOW);

        assertThat(summary.todaySuccessRate()).isNull();
        assertThat(summary.enabledTaskCount()).isZero();
        assertThat(summary.activeRunCount()).isZero();
        assertThat(summary.unresolvedFailureCount()).isZero();
        assertThat(summary.source().configured()).isFalse();
        assertThat(summary.sink().configured()).isFalse();
    }

    private void insertTask(String taskId, String name, String lifecycleStatus) {
        String definition = """
                {"schema":"public","table":"patient","selectedColumns":["id"],"filters":[],
                 "paginationKeys":["id"],"updatedTimeField":"updated_at"}
                """;
        jdbcTemplate.update("""
                INSERT INTO task (
                    task_id, name, version, lifecycle_status, read_mode, read_definition,
                    target_schema, target_table, write_mode, unique_keys, field_mappings,
                    remote_sink_url, sink_token_ref, expected_sink_instance_id, created_at, updated_at)
                VALUES (?, ?, 1, ?, 'TABLE', ?, 'public', 'patient_copy', 'UPSERT', ?,
                    ?, 'http://sink:19090', 'sink-token', NULL, ?, ?)
                """,
                taskId, name, lifecycleStatus, definition, "[\"id\"]",
                "[{\"sourceField\":\"id\",\"targetField\":\"id\"}]",
                "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z");
    }

    private void insertRun(String runId, String taskId, String status, String startedAt,
                           String pauseReason) {
        jdbcTemplate.update("""
                INSERT INTO run (
                    run_id, task_id, task_name_snapshot, task_version, kind, status,
                    pause_reason, started_at, ended_at, source_row_count, confirmed_row_count, created_at)
                VALUES (?, ?, 'snapshot', 1, 'MANUAL', ?, ?, ?, ?, 0, 0, ?)
                """, runId, taskId, status, pauseReason, startedAt,
                "SUCCEEDED".equals(status) ? startedAt : null, startedAt);
    }
}
