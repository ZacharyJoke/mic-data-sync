package com.mic.datasync.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.run.domain.RunStatus;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.shared.page.PageResult;
import com.mic.datasync.source.domain.ReadDefinition;
import com.mic.datasync.source.domain.SqlReadDefinition;
import com.mic.datasync.source.domain.TableReadDefinition;
import com.mic.datasync.task.TaskService.TaskRecord;
import com.mic.datasync.task.domain.TaskDefinition.LifecycleStatus;
import com.mic.datasync.task.domain.TaskDefinition.WriteMode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 任务列表查询服务：在 SQLite 中完成筛选、最近运行关联与分页。
 */
@Service
public class TaskQueryService {

    private static final String LATEST_RUN_CTE = """
            WITH latest_run AS (
                SELECT run_id, task_id, kind, status, started_at, ended_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY task_id
                           ORDER BY julianday(started_at) DESC, run_id DESC
                       ) AS rn
                FROM run
            )
            """;

    private static final String FILTERS = """
            WHERE (:keyword IS NULL OR LOWER(t.name) LIKE :keyword)
              AND (:lifecycleStatus IS NULL OR t.lifecycle_status = :lifecycleStatus)
              AND (:readMode IS NULL OR t.read_mode = :readMode)
              AND (:latestRunStatus IS NULL OR lr.status = :latestRunStatus)
            """;

    private static final String COUNT_SQL = LATEST_RUN_CTE + """
            SELECT COUNT(*)
            FROM task t
            LEFT JOIN latest_run lr ON lr.task_id = t.task_id AND lr.rn = 1
            """ + FILTERS;

    private static final String PAGE_SQL = LATEST_RUN_CTE + """
            SELECT t.task_id, t.name, t.version, t.lifecycle_status, t.read_mode, t.read_definition,
                   t.target_schema, t.target_table, t.write_mode, t.unique_keys, t.field_mappings,
                   t.remote_sink_url, t.sink_token_ref, t.expected_sink_instance_id,
                   t.source_endpoint_id, t.sink_endpoint_id, t.source_data_source_id, t.target_data_source_id,
                   t.created_at, t.updated_at,
                   lr.run_id AS latest_run_id, lr.kind AS latest_run_kind,
                   lr.status AS latest_run_status, lr.started_at AS latest_run_started_at,
                   lr.ended_at AS latest_run_ended_at
            FROM task t
            LEFT JOIN latest_run lr ON lr.task_id = t.task_id AND lr.rn = 1
            """ + FILTERS + """
            ORDER BY t.updated_at DESC, t.task_id ASC
            LIMIT :size OFFSET :offset
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TaskQueryService(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public PageResult<TaskListItem> search(TaskQuery query) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("keyword", normalizeKeyword(query.keyword()))
                .addValue("lifecycleStatus",
                        query.lifecycleStatus() == null ? null : query.lifecycleStatus().name())
                .addValue("readMode", query.readMode())
                .addValue("latestRunStatus",
                        query.latestRunStatus() == null ? null : query.latestRunStatus().name())
                .addValue("size", query.size())
                .addValue("offset", (long) (query.page() - 1) * query.size());

        Long count = jdbcTemplate.queryForObject(COUNT_SQL, parameters, Long.class);
        long total = count == null ? 0 : count;
        List<TaskListItem> items = total == 0
                ? List.of()
                : jdbcTemplate.query(PAGE_SQL, parameters, this::toListItem);
        return new PageResult<>(items, total, query.page(), query.size());
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private TaskListItem toListItem(ResultSet rs, int rowNum) throws SQLException {
        TaskRecord task = new TaskRecord(
                Identifiers.TaskId.fromString(rs.getString("task_id")),
                rs.getString("name"),
                rs.getInt("version"),
                LifecycleStatus.valueOf(rs.getString("lifecycle_status")),
                rs.getString("read_mode"),
                readDefinition(rs.getString("read_mode"), rs.getString("read_definition")),
                rs.getString("target_schema"),
                rs.getString("target_table"),
                WriteMode.valueOf(rs.getString("write_mode")),
                readList(rs.getString("unique_keys"), new TypeReference<List<String>>() {
                }),
                readList(rs.getString("field_mappings"), new TypeReference<List<FieldMapping>>() {
                }),
                rs.getString("remote_sink_url"),
                rs.getString("sink_token_ref"),
                rs.getString("expected_sink_instance_id") == null
                        ? null : Identifiers.InstanceId.fromString(rs.getString("expected_sink_instance_id")),
                rs.getString("source_endpoint_id"),
                rs.getString("sink_endpoint_id"),
                rs.getString("source_data_source_id"),
                rs.getString("target_data_source_id"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));

        String latestRunId = rs.getString("latest_run_id");
        LatestRunSummary latestRun = latestRunId == null ? null : new LatestRunSummary(
                latestRunId,
                rs.getString("latest_run_kind"),
                rs.getString("latest_run_status"),
                rs.getString("latest_run_started_at"),
                rs.getString("latest_run_ended_at"));
        return new TaskListItem(task, latestRun);
    }

    private ReadDefinition readDefinition(String readMode, String json) {
        try {
            return "SQL".equals(readMode)
                    ? objectMapper.readValue(json, SqlReadDefinition.class)
                    : objectMapper.readValue(json, TableReadDefinition.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("读取定义反序列化失败", ex);
        }
    }

    private <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON 反序列化失败", ex);
        }
    }

    public record TaskQuery(
            int page,
            int size,
            String keyword,
            LifecycleStatus lifecycleStatus,
            String readMode,
            RunStatus latestRunStatus) {
    }

    public record LatestRunSummary(
            String runId,
            String kind,
            String status,
            String startedAt,
            String endedAt) {
    }

    public record TaskListItem(TaskRecord task, LatestRunSummary latestRun) {
    }
}
