package com.mic.datasync.webapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerTest {

    private static final String TABLE_DEFINITION = """
            {"schema":"public","table":"patient","selectedColumns":["id"],"filters":[],
             "paginationKeys":["id"],"updatedTimeField":"updated_at"}
            """;
    private static final String SQL_DEFINITION = """
            {"rawSql":"SELECT id FROM public.patient","baseTable":"public.patient",
             "resultColumns":["id"],"structureFingerprint":"patient-v1",
             "paginationKeys":["id"],"updatedTimeField":"updated_at"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearTasksAndRuns() {
        jdbcTemplate.update("DELETE FROM run");
        jdbcTemplate.update("DELETE FROM task");
    }

    @Test
    void listReturnsDatabasePageWithLatestRunAndStableOrdering() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000002", "patient-b", "ENABLED", "TABLE",
                "2026-08-01T10:00:00Z");
        insertTask("00000000-0000-0000-0000-000000000001", "patient-a", "ENABLED", "TABLE",
                "2026-08-01T10:00:00Z");
        insertTask("00000000-0000-0000-0000-000000000003", "archive", "DRAFT", "SQL",
                "2026-08-01T09:00:00Z");
        insertRun("10000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000001", "MANUAL", "SUCCEEDED",
                "2026-08-01T10:01:00Z", "2026-08-01T10:02:00Z");
        insertRun("10000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000001", "INCREMENTAL", "FAILED",
                "2026-08-01T10:03:00Z", "2026-08-01T10:04:00Z");

        mockMvc.perform(get("/api/v1/tasks")
                        .with(user("admin").roles("ADMIN"))
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items[0].taskId")
                        .value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.items[0].latestRun.runId")
                        .value("10000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("$.items[0].latestRun.kind").value("INCREMENTAL"))
                .andExpect(jsonPath("$.items[0].latestRun.status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].latestRun.startedAt").value("2026-08-01T10:03:00Z"))
                .andExpect(jsonPath("$.items[0].latestRun.endedAt").value("2026-08-01T10:04:00Z"))
                .andExpect(jsonPath("$.items[1].taskId")
                        .value("00000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("$.items[1].latestRun").doesNotExist());
    }

    @Test
    void latestRunUsesActualInstantAcrossFractionalSecondPrecision() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000004", "fractional-run", "ENABLED", "TABLE",
                "2026-08-01T10:00:00Z");
        insertRun("10000000-0000-0000-0000-000000000004",
                "00000000-0000-0000-0000-000000000004", "MANUAL", "SUCCEEDED",
                "2026-08-01T10:01:00Z", "2026-08-01T10:02:00Z");
        insertRun("10000000-0000-0000-0000-000000000005",
                "00000000-0000-0000-0000-000000000004", "INCREMENTAL", "FAILED",
                "2026-08-01T10:01:00.123Z", "2026-08-01T10:02:00.123Z");

        mockMvc.perform(get("/api/v1/tasks")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].latestRun.runId")
                        .value("10000000-0000-0000-0000-000000000005"))
                .andExpect(jsonPath("$.items[0].latestRun.startedAt")
                        .value("2026-08-01T10:01:00.123Z"));
    }

    @Test
    void latestRunUsesRunIdAsStableTieBreakerForSameStartedAt() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000005", "tied-run", "ENABLED", "TABLE",
                "2026-08-01T10:00:00Z");
        insertRun("10000000-0000-0000-0000-000000000006",
                "00000000-0000-0000-0000-000000000005", "MANUAL", "SUCCEEDED",
                "2026-08-01T10:01:00Z", "2026-08-01T10:02:00Z");
        insertRun("10000000-0000-0000-0000-000000000007",
                "00000000-0000-0000-0000-000000000005", "INCREMENTAL", "FAILED",
                "2026-08-01T10:01:00Z", "2026-08-01T10:02:00Z");

        mockMvc.perform(get("/api/v1/tasks")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].latestRun.runId")
                        .value("10000000-0000-0000-0000-000000000007"));
    }

    @Test
    void latestRunStatusDoesNotMatchOlderRunAcrossFractionalSecondPrecision() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000006", "latest-status", "ENABLED", "TABLE",
                "2026-08-01T10:00:00Z");
        insertRun("10000000-0000-0000-0000-000000000008",
                "00000000-0000-0000-0000-000000000006", "MANUAL", "SUCCEEDED",
                "2026-08-01T10:01:00Z", "2026-08-01T10:02:00Z");
        insertRun("10000000-0000-0000-0000-000000000009",
                "00000000-0000-0000-0000-000000000006", "INCREMENTAL", "FAILED",
                "2026-08-01T10:01:00.123Z", "2026-08-01T10:02:00.123Z");

        mockMvc.perform(get("/api/v1/tasks")
                        .with(user("admin").roles("ADMIN"))
                        .param("latestRunStatus", "SUCCEEDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));

        assertSingleFilteredTask("latestRunStatus", "FAILED",
                "00000000-0000-0000-0000-000000000006");
    }

    @Test
    void listFiltersByKeywordLifecycleReadModeAndLatestRunStatus() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000011", "Patient Daily", "ENABLED", "TABLE",
                "2026-08-01T11:00:00Z");
        insertTask("00000000-0000-0000-0000-000000000012", "patient draft", "DRAFT", "TABLE",
                "2026-08-01T10:00:00Z");
        insertTask("00000000-0000-0000-0000-000000000013", "Patient SQL", "ENABLED", "SQL",
                "2026-08-01T09:00:00Z");
        insertTask("00000000-0000-0000-0000-000000000014", "orders", "ENABLED", "TABLE",
                "2026-08-01T08:00:00Z");
        insertRun("10000000-0000-0000-0000-000000000011",
                "00000000-0000-0000-0000-000000000011", "MANUAL", "FAILED",
                "2026-08-01T11:01:00Z", "2026-08-01T11:02:00Z");
        insertRun("10000000-0000-0000-0000-000000000012",
                "00000000-0000-0000-0000-000000000012", "MANUAL", "FAILED",
                "2026-08-01T10:01:00Z", "2026-08-01T10:02:00Z");
        insertRun("10000000-0000-0000-0000-000000000013",
                "00000000-0000-0000-0000-000000000013", "MANUAL", "SUCCEEDED",
                "2026-08-01T09:01:00Z", "2026-08-01T09:02:00Z");

        assertSingleFilteredTask("keyword", "  ORDERS  ",
                "00000000-0000-0000-0000-000000000014");
        assertSingleFilteredTask("lifecycleStatus", "DRAFT",
                "00000000-0000-0000-0000-000000000012");
        assertSingleFilteredTask("readMode", "SQL",
                "00000000-0000-0000-0000-000000000013");
        assertSingleFilteredTask("latestRunStatus", "SUCCEEDED",
                "00000000-0000-0000-0000-000000000013");

        mockMvc.perform(get("/api/v1/tasks")
                        .with(user("admin").roles("ADMIN"))
                        .param("keyword", "  PATIENT  ")
                        .param("lifecycleStatus", "ENABLED")
                        .param("readMode", "TABLE")
                        .param("latestRunStatus", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].taskId")
                        .value("00000000-0000-0000-0000-000000000011"));
    }

    @Test
    void detailResponseKeepsLatestRunSerializableAsNull() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000015", "patient-detail", "DRAFT", "TABLE",
                "2026-08-01T12:00:00Z");

        mockMvc.perform(get("/api/v1/tasks/00000000-0000-0000-0000-000000000015")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId")
                        .value("00000000-0000-0000-0000-000000000015"))
                .andExpect(jsonPath("$.latestRun").doesNotExist());
    }

    @Test
    void outOfRangePageKeepsFilteredTotal() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000021", "patient-one", "ENABLED", "TABLE",
                "2026-08-01T11:00:00Z");
        insertTask("00000000-0000-0000-0000-000000000022", "patient-two", "ENABLED", "TABLE",
                "2026-08-01T10:00:00Z");
        insertTask("00000000-0000-0000-0000-000000000023", "orders", "ENABLED", "TABLE",
                "2026-08-01T09:00:00Z");

        mockMvc.perform(get("/api/v1/tasks")
                        .with(user("admin").roles("ADMIN"))
                        .param("page", "9")
                        .param("size", "1")
                        .param("keyword", "patient"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(9))
                .andExpect(jsonPath("$.size").value(1));
    }

    @Test
    void invalidPageAndSizeReturnFieldSpecificErrors() throws Exception {
        assertInvalidPageRequest("page", "0", "page");
        assertInvalidPageRequest("page", "abc", "page");
        assertInvalidPageRequest("size", "0", "size");
        assertInvalidPageRequest("size", "101", "size");
        assertInvalidPageRequest("size", "abc", "size");
    }

    @Test
    void invalidFiltersReturnFieldSpecificErrors() throws Exception {
        assertInvalidFilter("lifecycleStatus", "UNKNOWN_STATUS", "lifecycleStatus");
        assertInvalidFilter("readMode", "STREAM", "readMode");
        assertInvalidFilter("latestRunStatus", "BROKEN", "latestRunStatus");
    }

    @Test
    void preflightReturnsValidationReportWithoutPersisting() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "preflight-task",
                "readMode", "TABLE",
                "readDefinition", Map.of(
                        "schema", "public",
                        "table", "patient",
                        "selectedColumns", List.of("id"),
                        "filters", List.of(),
                        "paginationKeys", List.of("id"),
                        "updatedTimeField", "updated_at"),
                "targetSchema", "public",
                "targetTable", "patient_copy",
                "writeMode", "UPSERT",
                "uniqueKeys", List.of("id"),
                "fieldMappings", List.of(Map.of("sourceField", "id", "targetField", "id")),
                "remoteSinkUrl", "http://sink:19090");

        mockMvc.perform(post("/api/v1/tasks/preflight")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").isBoolean())
                .andExpect(jsonPath("$.issues").isArray());

        Long taskCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task", Long.class);
        assertThat(taskCount).isZero();
    }

    @Test
    void taskLifecyclePauseResumeDisableEndpoints() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000031", "lifecycle", "ENABLED", "TABLE",
                "2026-08-01T10:00:00Z");

        mockMvc.perform(post("/api/v1/tasks/00000000-0000-0000-0000-000000000031/pause")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("PAUSED"));

        mockMvc.perform(post("/api/v1/tasks/00000000-0000-0000-0000-000000000031/resume")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("ENABLED"));

        mockMvc.perform(post("/api/v1/tasks/00000000-0000-0000-0000-000000000031/disable")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("DISABLED"));
    }

    @Test
    void invalidLifecycleTransitionsReturnConflict() throws Exception {
        insertTask("00000000-0000-0000-0000-000000000032", "draft-lifecycle", "DRAFT", "TABLE",
                "2026-08-01T10:00:00Z");

        mockMvc.perform(post("/api/v1/tasks/00000000-0000-0000-0000-000000000032/pause")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/tasks/00000000-0000-0000-0000-000000000032/resume")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/tasks/00000000-0000-0000-0000-000000000032/disable")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    private void assertInvalidPageRequest(String parameter, String value, String field) throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .with(user("admin").roles("ADMIN"))
                        .param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_REQUEST"))
                .andExpect(jsonPath("$.details.field").value(field));
    }

    private void assertSingleFilteredTask(String parameter, String value, String taskId) throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .with(user("admin").roles("ADMIN"))
                        .param(parameter, value))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].taskId").value(taskId));
    }

    private void assertInvalidFilter(String parameter, String value, String field) throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .with(user("admin").roles("ADMIN"))
                        .param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILTER_VALUE"))
                .andExpect(jsonPath("$.details.field").value(field));
    }

    private void insertTask(String taskId, String name, String lifecycleStatus, String readMode, String updatedAt) {
        String readDefinition = "SQL".equals(readMode) ? SQL_DEFINITION : TABLE_DEFINITION;
        jdbcTemplate.update("""
                INSERT INTO task (
                    task_id, name, version, lifecycle_status, read_mode, read_definition,
                    target_schema, target_table, write_mode, unique_keys, field_mappings,
                    remote_sink_url, sink_token_ref, expected_sink_instance_id, created_at, updated_at)
                VALUES (?, ?, 1, ?, ?, ?, 'public', 'patient_copy', 'UPSERT', ?, ?,
                    'http://sink:19090', 'sink-token', NULL, ?, ?)
                """,
                taskId, name, lifecycleStatus, readMode, readDefinition,
                "[\"id\"]", "[{\"sourceField\":\"id\",\"targetField\":\"id\"}]",
                updatedAt, updatedAt);
    }

    private void insertRun(String runId, String taskId, String kind, String status,
                           String startedAt, String endedAt) {
        jdbcTemplate.update("""
                INSERT INTO run (
                    run_id, task_id, task_name_snapshot, task_version, kind, status,
                    pause_reason, started_at, ended_at, source_row_count, confirmed_row_count, created_at)
                VALUES (?, ?, 'snapshot', 1, ?, ?, NULL, ?, ?, 0, 0, ?)
                """, runId, taskId, kind, status, startedAt, endedAt, startedAt);
    }
}
