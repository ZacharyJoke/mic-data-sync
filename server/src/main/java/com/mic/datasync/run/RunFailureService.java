package com.mic.datasync.run;

import com.mic.datasync.shared.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 结构化运行失败记录与诊断：只保存安全摘要，不写堆栈或 Payload。
 */
@Service
public class RunFailureService {

    private final JdbcTemplate jdbcTemplate;

    public RunFailureService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 幂等写入：同一 Run 只保留最新失败记录。 */
    public void record(RunFailure failure) {
        jdbcTemplate.update("""
                INSERT INTO run_failure (
                    run_id, stage, error_code, summary, impact, request_id, retryable, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(run_id) DO UPDATE SET
                    stage = excluded.stage,
                    error_code = excluded.error_code,
                    summary = excluded.summary,
                    impact = excluded.impact,
                    request_id = excluded.request_id,
                    retryable = excluded.retryable,
                    occurred_at = excluded.occurred_at
                """,
                failure.runId().toString(),
                failure.stage().name(),
                failure.errorCode(),
                failure.summary(),
                failure.impact(),
                failure.requestId(),
                failure.retryable() ? 1 : 0,
                failure.occurredAt().toString());
    }

    /** 读取指定 Run 的原始失败记录。 */
    public Optional<RunFailure> get(Identifiers.RunId runId) {
        List<RunFailure> rows = jdbcTemplate.query("""
                SELECT run_id, stage, error_code, summary, impact, request_id, retryable, occurred_at
                FROM run_failure
                WHERE run_id = ?
                """, (rs, rowNum) -> new RunFailure(
                        Identifiers.RunId.fromString(rs.getString("run_id")),
                        FailureStage.valueOf(rs.getString("stage")),
                        rs.getString("error_code"),
                        rs.getString("summary"),
                        rs.getString("impact"),
                        rs.getString("request_id"),
                        rs.getInt("retryable") == 1,
                        Instant.parse(rs.getString("occurred_at"))),
                runId.toString());
        return rows.stream().findFirst();
    }

    public Optional<RunDiagnosis> diagnosis(Identifiers.RunId runId) {
        List<RunDiagnosis> rows = jdbcTemplate.query("""
                SELECT run_id, stage, error_code, summary, impact, request_id, retryable, occurred_at
                FROM run_failure
                WHERE run_id = ?
                """, (rs, rowNum) -> toDiagnosis(rs), runId.toString());
        return rows.stream().findFirst();
    }

    private RunDiagnosis toDiagnosis(ResultSet rs) throws SQLException {
        RunFailure failure = new RunFailure(
                Identifiers.RunId.fromString(rs.getString("run_id")),
                FailureStage.valueOf(rs.getString("stage")),
                rs.getString("error_code"),
                rs.getString("summary"),
                rs.getString("impact"),
                rs.getString("request_id"),
                rs.getInt("retryable") == 1,
                Instant.parse(rs.getString("occurred_at")));
        return new RunDiagnosis(
                failure.runId().toString(),
                failure.stage().name(),
                failure.errorCode(),
                failure.summary(),
                failure.impact(),
                failure.retryable(),
                failure.requestId(),
                suggestedActions(failure));
    }

    private List<SuggestedAction> suggestedActions(RunFailure failure) {
        return switch (failure.stage()) {
            case PREFLIGHT -> List.of(
                    action("OPEN_TASK_CONFIG", "检查任务配置"),
                    action("REVALIDATE", "重新校验"));
            case TARGET_WRITE -> List.of(
                    action("OPEN_TASK_CONFIG", "检查字段映射"),
                    action("REVALIDATE", "重新校验"));
            case TRANSPORT, CONFIRMATION -> List.of(
                    action("OPEN_DATA_SOURCE", "检查 Sink 连接"),
                    action("RETRY", "安全重试"));
            case SOURCE_READ -> List.of(
                    action("OPEN_DATA_SOURCE", "检查 Source 连接"),
                    action("REVALIDATE", "重新校验"));
            case INTERNAL -> List.of(
                    action("CONTACT_SUPPORT", "联系支持并提供 requestId"));
        };
    }

    private static SuggestedAction action(String type, String label) {
        return new SuggestedAction(type, label);
    }

    public enum FailureStage {
        PREFLIGHT,
        SOURCE_READ,
        TRANSPORT,
        TARGET_WRITE,
        CONFIRMATION,
        INTERNAL
    }

    public record RunFailure(
            Identifiers.RunId runId,
            FailureStage stage,
            String errorCode,
            String summary,
            String impact,
            String requestId,
            boolean retryable,
            Instant occurredAt) {
    }

    public record RunDiagnosis(
            String runId,
            String stage,
            String code,
            String summary,
            String impact,
            boolean retryable,
            String requestId,
            List<SuggestedAction> suggestedActions) {
    }

    public record SuggestedAction(String type, String label) {
    }
}
