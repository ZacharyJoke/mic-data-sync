package com.mic.datasync.task;

import com.mic.datasync.source.domain.TableReadDefinition;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.task.TaskService.CreateTaskCommand;
import com.mic.datasync.task.TaskService.TaskRecord;
import com.mic.datasync.task.TaskService.UpdateTaskCommand;
import com.mic.datasync.task.domain.TaskDefinition.WriteMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TaskServiceTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTasks() {
        jdbcTemplate.update("DELETE FROM run_retry_request");
        jdbcTemplate.update("DELETE FROM run_failure");
        jdbcTemplate.update("DELETE FROM batch");
        jdbcTemplate.update("DELETE FROM run");
        jdbcTemplate.update("DELETE FROM task");
    }

    @Test
    void lifecyclePauseResumeDisableTransitions() {
        TaskRecord created = taskService.create(command());
        jdbcTemplate.update("UPDATE task SET lifecycle_status = 'ENABLED' WHERE task_id = ?",
                created.taskId().toString());

        TaskRecord paused = taskService.pause(created.taskId());
        assertThat(paused.lifecycleStatus().name()).isEqualTo("PAUSED");

        TaskRecord resumed = taskService.resume(created.taskId());
        assertThat(resumed.lifecycleStatus().name()).isEqualTo("ENABLED");

        TaskRecord disabled = taskService.disable(created.taskId());
        assertThat(disabled.lifecycleStatus().name()).isEqualTo("DISABLED");
    }

    @Test
    void pauseRequiresEnabledAndDisableRequiresNoActiveRun() {
        TaskRecord created = taskService.create(command());

        assertThatThrownBy(() -> taskService.pause(created.taskId()))
                .isInstanceOf(IllegalStateException.class);

        jdbcTemplate.update("UPDATE task SET lifecycle_status = 'ENABLED' WHERE task_id = ?",
                created.taskId().toString());
        insertRun(created.taskId());
        assertThatThrownBy(() -> taskService.disable(created.taskId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void partialDraftUpdateKeepsExistingStructuredFields() {
        TableReadDefinition readDefinition = new TableReadDefinition(
                "public", "patient", List.of("id", "name"), List.of(), List.of("id"), "updated_at");
        TaskRecord created = taskService.create(new CreateTaskCommand(
                "patient-sync",
                "TABLE",
                readDefinition,
                "public",
                "patient_copy",
                WriteMode.UPSERT,
                List.of("id"),
                List.of(new FieldMapping("id", "id"), new FieldMapping("name", "name")),
                "http://old-sink:19090",
                "sink-token",
                null,
                null, null, null, null));

        TaskRecord updated = taskService.update(created.taskId(), new UpdateTaskCommand(
                "patient-sync-renamed",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "http://new-sink:19090",
                null,
                null,
                null, null, null, null));

        assertThat(updated.readDefinition()).isEqualTo(readDefinition);
        assertThat(updated.uniqueKeys()).containsExactly("id");
        assertThat(updated.fieldMappings()).containsExactly(
                new FieldMapping("id", "id"),
                new FieldMapping("name", "name"));
    }

    @Test
    void enabledOrPausedTaskRejectsSemanticFieldEdit() {
        TaskRecord created = taskService.create(command());
        jdbcTemplate.update("UPDATE task SET lifecycle_status = 'ENABLED' WHERE task_id = ?",
                created.taskId().toString());

        UpdateTaskCommand semanticEdit = new UpdateTaskCommand(
                "patient-sync", null, null, null, "patient_copy_v2", null,
                null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> taskService.update(created.taskId(), semanticEdit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("语义字段不允许直接编辑");

        jdbcTemplate.update("UPDATE task SET lifecycle_status = 'PAUSED' WHERE task_id = ?",
                created.taskId().toString());
        assertThatThrownBy(() -> taskService.update(created.taskId(), semanticEdit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("语义字段不允许直接编辑");
    }

    @Test
    void disabledTaskAllowsSemanticFieldEdit() {
        TaskRecord created = taskService.create(command());
        jdbcTemplate.update("UPDATE task SET lifecycle_status = 'DISABLED' WHERE task_id = ?",
                created.taskId().toString());

        TaskRecord updated = taskService.update(created.taskId(), new UpdateTaskCommand(
                "patient-sync", null, null, null, "patient_copy_v2", null,
                null, null, null, null, null, null, null, null, null));

        assertThat(updated.lifecycleStatus().name()).isEqualTo("DISABLED");
        assertThat(updated.targetTable()).isEqualTo("patient_copy_v2");
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

    private void insertRun(Identifiers.TaskId taskId) {
        jdbcTemplate.update("""
                INSERT INTO run (
                    run_id, task_id, task_name_snapshot, task_version, kind, status,
                    pause_reason, started_at, ended_at, source_row_count, confirmed_row_count, created_at)
                VALUES ('10000000-0000-0000-0000-000000000001', ?, 'patient-sync', 1,
                    'MANUAL', 'RUNNING', NULL, '2026-08-01T00:00:00Z', NULL, 0, 0,
                    '2026-08-01T00:00:00Z')
                """, taskId.toString());
    }
}
