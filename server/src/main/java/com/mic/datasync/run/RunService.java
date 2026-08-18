package com.mic.datasync.run;

import com.mic.datasync.run.domain.RunStatus;
import com.mic.datasync.shared.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 运行记录服务：创建、状态更新与查询。
 */
@Service
public class RunService {

    private static final RowMapper<RunRecord> RUN_ROW_MAPPER = (rs, rowNum) -> new RunRecord(
            Identifiers.RunId.fromString(rs.getString("run_id")),
            Identifiers.TaskId.fromString(rs.getString("task_id")),
            rs.getString("task_name_snapshot"),
            rs.getInt("task_version"),
            RunKind.valueOf(rs.getString("kind")),
            RunStatus.valueOf(rs.getString("status")),
            rs.getString("pause_reason"),
            Instant.parse(rs.getString("started_at")),
            rs.getString("ended_at") == null ? null : Instant.parse(rs.getString("ended_at")),
            rs.getLong("source_row_count"),
            rs.getLong("confirmed_row_count"),
            rs.getString("previous_run_id") == null
                    ? null : Identifiers.RunId.fromString(rs.getString("previous_run_id")));

    private final JdbcTemplate jdbcTemplate;

    public RunService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 创建 Run（已占用名额；调用方需先通过 RunSlotGuard 检查）。 */
    @Transactional
    public RunRecord create(Identifiers.TaskId taskId, String taskNameSnapshot, int taskVersion, RunKind kind) {
        return create(taskId, taskNameSnapshot, taskVersion, kind, null);
    }

    /** 创建 Run，可携带 previousRunId（安全重试产生的新 Run）。 */
    @Transactional
    public RunRecord create(Identifiers.TaskId taskId, String taskNameSnapshot, int taskVersion,
                            RunKind kind, Identifiers.RunId previousRunId) {
        Identifiers.RunId runId = Identifiers.RunId.generate();
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO run (run_id, task_id, task_name_snapshot, task_version, kind, status,
                    started_at, source_row_count, confirmed_row_count, created_at, previous_run_id)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, 0, 0, ?, ?)
                """, runId.toString(), taskId.toString(), taskNameSnapshot, taskVersion, kind.name(), now, now,
                previousRunId == null ? null : previousRunId.toString());
        return get(runId).orElseThrow();
    }

    /** 更新运行状态与统计。 */
    @Transactional
    public void updateStatus(Identifiers.RunId runId, RunStatus status, String pauseReason,
                             long sourceRowCount, long confirmedRowCount) {
        jdbcTemplate.update("""
                UPDATE run SET status = ?, pause_reason = ?, source_row_count = ?,
                    confirmed_row_count = ?, ended_at = CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELLED') THEN ? ELSE ended_at END
                WHERE run_id = ?
                """, status.name(), pauseReason, sourceRowCount, confirmedRowCount,
                status.name(), Instant.now().toString(), runId.toString());
    }

    /** 仅更新状态与暂停原因，不修改统计（用于重试等待等中间状态切换）。 */
    @Transactional
    public void updateStatusOnly(Identifiers.RunId runId, RunStatus status, String pauseReason) {
        jdbcTemplate.update("""
                UPDATE run SET status = ?, pause_reason = ? WHERE run_id = ?
                """, status.name(), pauseReason, runId.toString());
    }

    /** 查询运行。 */
    public Optional<RunRecord> get(Identifiers.RunId runId) {
        return queryOne("""
                SELECT run_id, task_id, task_name_snapshot, task_version, kind, status,
                    pause_reason, started_at, ended_at, source_row_count, confirmed_row_count,
                    previous_run_id
                FROM run WHERE run_id = ?
                """, runId.toString());
    }

    /** 任务最近的运行。 */
    public Optional<RunRecord> latestByTask(Identifiers.TaskId taskId) {
        return queryOne("""
                SELECT run_id, task_id, task_name_snapshot, task_version, kind, status,
                    pause_reason, started_at, ended_at, source_row_count, confirmed_row_count,
                    previous_run_id
                FROM run WHERE task_id = ?
                ORDER BY julianday(started_at) DESC, run_id DESC
                LIMIT 1
                """, taskId.toString());
    }

    /** 全部运行（新→旧）。 */
    public List<RunRecord> list() {
        return jdbcTemplate.query("""
                SELECT run_id, task_id, task_name_snapshot, task_version, kind, status,
                    pause_reason, started_at, ended_at, source_row_count, confirmed_row_count,
                    previous_run_id
                FROM run ORDER BY started_at DESC
                """, RUN_ROW_MAPPER);
    }

    private Optional<RunRecord> queryOne(String sql, String id) {
        List<RunRecord> rows = jdbcTemplate.query(sql, RUN_ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 运行记录。 */
    public record RunRecord(
            Identifiers.RunId runId,
            Identifiers.TaskId taskId,
            String taskNameSnapshot,
            int taskVersion,
            RunKind kind,
            RunStatus status,
            String pauseReason,
            Instant startedAt,
            Instant endedAt,
            long sourceRowCount,
            long confirmedRowCount,
            Identifiers.RunId previousRunId) {
    }

    /** 运行类型。 */
    public enum RunKind {
        INITIAL_FULL,
        CATCH_UP,
        INCREMENTAL,
        MANUAL
    }
}
