package com.mic.datasync.run;

import com.mic.datasync.run.RunService.RunKind;
import com.mic.datasync.run.RunService.RunRecord;
import com.mic.datasync.run.domain.RunStatus;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.shared.page.PageResult;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 运行与批次分页查询：筛选、排序和分页全部下推到 SQLite。
 */
@Service
public class RunQueryService {

    private static final String RUN_SELECT = """
            SELECT run_id, task_id, task_name_snapshot, task_version, kind, status,
                   pause_reason, started_at, ended_at, source_row_count, confirmed_row_count,
                   previous_run_id
            FROM run
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RunQueryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PageResult<RunRecord> search(RunQuery query) {
        SqlParts sql = buildRunSql(query);
        long total = jdbcTemplate.queryForObject(sql.countSql(), sql.parameters(), Long.class);
        List<RunRecord> items = total == 0
                ? List.of()
                : jdbcTemplate.query(sql.pageSql(), sql.parameters(), this::toRunRecord);
        return new PageResult<>(items, total, query.page(), query.size());
    }

    public PageResult<RunRecord> searchByTask(Identifiers.TaskId taskId, RunQuery query) {
        return search(new RunQuery(
                query.page(),
                query.size(),
                query.status(),
                taskId,
                query.kind(),
                query.startedFrom(),
                query.startedTo(),
                query.keyword()));
    }

    public PageResult<BatchRecord> batches(Identifiers.RunId runId, int page, int size) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("runId", runId.toString())
                .addValue("size", size)
                .addValue("offset", (long) (page - 1) * size);
        long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM batch WHERE run_id = :runId
                """, parameters, Long.class);
        List<BatchRecord> items = total == 0 ? List.of() : jdbcTemplate.query("""
                SELECT batch_id, run_id, batch_sequence, payload_hash, row_count,
                       time_watermark, status, attempt_count, created_at
                FROM batch
                WHERE run_id = :runId
                ORDER BY batch_sequence
                LIMIT :size OFFSET :offset
                """, parameters, this::toBatchRecord);
        return new PageResult<>(items, total, page, size);
    }

    private SqlParts buildRunSql(RunQuery query) {
        List<String> conditions = new ArrayList<>();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("size", query.size())
                .addValue("offset", (long) (query.page() - 1) * query.size());
        if (query.status() != null) {
            conditions.add("status = :status");
            parameters.addValue("status", query.status().name());
        }
        if (query.taskId() != null) {
            conditions.add("task_id = :taskId");
            parameters.addValue("taskId", query.taskId().toString());
        }
        if (query.kind() != null) {
            conditions.add("kind = :kind");
            parameters.addValue("kind", query.kind().name());
        }
        if (query.startedFrom() != null) {
            conditions.add("julianday(started_at) >= julianday(:startedFrom)");
            parameters.addValue("startedFrom", query.startedFrom().toString());
        }
        if (query.startedTo() != null) {
            conditions.add("julianday(started_at) <= julianday(:startedTo)");
            parameters.addValue("startedTo", query.startedTo().toString());
        }
        String keyword = normalizeKeyword(query.keyword());
        if (keyword != null) {
            conditions.add("LOWER(task_name_snapshot) LIKE :keyword");
            parameters.addValue("keyword", keyword);
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        String countSql = "SELECT COUNT(*) FROM run" + where;
        String pageSql = RUN_SELECT + where + "\n" + """
                ORDER BY julianday(started_at) DESC, run_id DESC
                LIMIT :size OFFSET :offset
                """;
        return new SqlParts(countSql, pageSql, parameters);
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private RunRecord toRunRecord(ResultSet rs, int rowNum) throws SQLException {
        return new RunRecord(
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
    }

    private BatchRecord toBatchRecord(ResultSet rs, int rowNum) throws SQLException {
        return new BatchRecord(
                rs.getString("batch_id"),
                rs.getString("run_id"),
                rs.getLong("batch_sequence"),
                rs.getString("payload_hash"),
                rs.getLong("row_count"),
                rs.getString("time_watermark"),
                rs.getString("status"),
                rs.getInt("attempt_count"),
                rs.getString("created_at"));
    }

    public record RunQuery(
            int page,
            int size,
            RunStatus status,
            Identifiers.TaskId taskId,
            RunKind kind,
            Instant startedFrom,
            Instant startedTo,
            String keyword) {
    }

    /** 批次记录。 */
    public record BatchRecord(
            String batchId,
            String runId,
            long batchSequence,
            String payloadHash,
            long rowCount,
            String timeWatermark,
            String status,
            int attemptCount,
            String createdAt) {
    }

    private record SqlParts(String countSql, String pageSql, MapSqlParameterSource parameters) {
    }
}
