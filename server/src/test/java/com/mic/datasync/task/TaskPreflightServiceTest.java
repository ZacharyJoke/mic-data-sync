package com.mic.datasync.task;

import com.mic.datasync.source.domain.TableReadDefinition;
import com.mic.datasync.task.TaskService.CreateTaskCommand;
import com.mic.datasync.task.TaskValidator.ValidationReport;
import com.mic.datasync.task.domain.TaskDefinition.WriteMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TaskPreflightServiceTest {

    @Autowired
    private TaskPreflightService preflightService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM batch");
        jdbcTemplate.update("DELETE FROM run");
        jdbcTemplate.update("DELETE FROM task");
    }

    @Test
    void preflightDoesNotPersistTaskOrRun() {
        long tasksBefore = count("task");
        long runsBefore = count("run");

        ValidationReport report = preflightService.preflight(command());

        assertThat(count("task")).isEqualTo(tasksBefore);
        assertThat(count("run")).isEqualTo(runsBefore);
        assertThat(report.issues()).isNotEmpty();
    }

    @Test
    void preflightIssuesCarrySeverityStageAndSuggestedAction() {
        ValidationReport report = preflightService.preflight(command());

        assertThat(report.issues()).anySatisfy(issue -> {
            assertThat(issue.severity()).isNotNull();
            assertThat(issue.stage()).isNotNull();
            assertThat(issue.suggestedAction()).isNotBlank();
        });
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private CreateTaskCommand command() {
        TableReadDefinition readDefinition = new TableReadDefinition(
                "public", "patient", List.of("id"), List.of(), List.of("id"), "updated_at");
        return new CreateTaskCommand(
                "patient-sync",
                "TABLE",
                readDefinition,
                "public",
                "patient_copy",
                WriteMode.UPSERT,
                List.of("id"),
                List.of(new FieldMapping("id", "id")),
                "http://sink:19090",
                "sink-token",
                null,
                null, null, null, null);
    }
}
