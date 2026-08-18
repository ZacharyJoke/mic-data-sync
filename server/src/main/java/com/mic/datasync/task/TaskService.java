package com.mic.datasync.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.endpoint.EndpointRecord;
import com.mic.datasync.endpoint.EndpointService;
import com.mic.datasync.instance.RoleProperties;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.source.domain.ReadDefinition;
import com.mic.datasync.source.domain.SqlReadDefinition;
import com.mic.datasync.source.domain.TableReadDefinition;
import com.mic.datasync.task.domain.TaskDefinition.LifecycleStatus;
import com.mic.datasync.task.domain.TaskDefinition.WriteMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 任务配置服务：列表、创建、查看、更新、删除与启用。
 *
 * <p>复杂结构（readDefinition / uniqueKeys / fieldMappings）以 JSON 存入 SQLite；
 * 任务数量上限由 {@code mic.sync.source.max-tasks} 控制（默认 10）。</p>
 */
@Service
public class TaskService {

    private static final Set<String> ACTIVE_RUN_STATUSES =
            Set.of("RUNNING", "WAITING_RETRY", "UNKNOWN", "PAUSED");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RoleProperties roleProperties;
    private final TaskValidator taskValidator;
    private final EndpointService endpointService;

    public TaskService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                       RoleProperties roleProperties, TaskValidator taskValidator,
                       EndpointService endpointService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.roleProperties = roleProperties;
        this.taskValidator = taskValidator;
        this.endpointService = endpointService;
    }

    /** 任务列表（按创建时间倒序）。 */
    public List<TaskRecord> list() {
        return jdbcTemplate.query("""
                SELECT task_id, name, version, lifecycle_status, read_mode, read_definition,
                       target_schema, target_table, write_mode, unique_keys, field_mappings,
                       remote_sink_url, sink_token_ref, expected_sink_instance_id,
                       source_endpoint_id, sink_endpoint_id, source_data_source_id, target_data_source_id,
                       created_at, updated_at
                FROM task ORDER BY created_at DESC
                """, (rs, rowNum) -> toRecord(rs.getString("task_id"), rs.getString("name"),
                rs.getInt("version"), rs.getString("lifecycle_status"), rs.getString("read_mode"),
                rs.getString("read_definition"), rs.getString("target_schema"), rs.getString("target_table"),
                rs.getString("write_mode"), rs.getString("unique_keys"), rs.getString("field_mappings"),
                rs.getString("remote_sink_url"), rs.getString("sink_token_ref"),
                rs.getString("expected_sink_instance_id"), rs.getString("source_endpoint_id"),
                rs.getString("sink_endpoint_id"), rs.getString("source_data_source_id"),
                rs.getString("target_data_source_id"), rs.getString("created_at"), rs.getString("updated_at")));
    }

    /** 任务详情。 */
    public Optional<TaskRecord> get(Identifiers.TaskId taskId) {
        return list().stream().filter(t -> t.taskId().equals(taskId)).findFirst();
    }

    /** 创建任务（草稿）。 */
    @Transactional
    public TaskRecord create(CreateTaskCommand command) {
        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("任务名称不能为空");
        }
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task", Integer.class);
        if (count != null && count >= roleProperties.source().maxTasks()) {
            throw new TaskLimitExceededException(roleProperties.source().maxTasks());
        }
        Identifiers.TaskId taskId = Identifiers.TaskId.generate();
        String now = Instant.now().toString();
        String readDefinitionJson = serializeReadDefinition(command.readMode(), command.readDefinition());
        SinkBinding sink = resolveSinkBinding(command);
        jdbcTemplate.update("""
                INSERT INTO task (task_id, name, version, lifecycle_status, read_mode, read_definition,
                    target_schema, target_table, write_mode, unique_keys, field_mappings,
                    remote_sink_url, sink_token_ref, expected_sink_instance_id,
                    source_endpoint_id, sink_endpoint_id, source_data_source_id, target_data_source_id,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                taskId.toString(), command.name(), 1, "DRAFT", command.readMode(), readDefinitionJson,
                command.targetSchema(), command.targetTable(), command.writeMode().name(),
                toJson(command.uniqueKeys()), toJson(command.fieldMappings()),
                sink.remoteSinkUrl(), command.sinkTokenRef(), sink.instanceId(),
                resolveSourceEndpointId(command), command.sinkEndpointId(),
                command.sourceDataSourceId(), command.targetDataSourceId(),
                now, now);
        return get(taskId).orElseThrow();
    }

    /** 更新任务。已启用/已暂停（可能触发或恢复运行）的任务，语义字段锁定；
     *  草稿、已禁用、已阻塞任务无活动同步，允许编辑语义字段后重新启用。 */
    @Transactional
    public TaskRecord update(Identifiers.TaskId taskId, UpdateTaskCommand command) {
        TaskRecord existing = get(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (existing.lifecycleStatus() == LifecycleStatus.ENABLED
                || existing.lifecycleStatus() == LifecycleStatus.PAUSED
                || existing.lifecycleStatus() == LifecycleStatus.DELETING
                || existing.lifecycleStatus() == LifecycleStatus.DELETED) {
            boolean semanticChanged = command.readDefinition() != null
                    || command.fieldMappings() != null
                    || command.uniqueKeys() != null
                    || command.targetTable() != null
                    || command.writeMode() != null
                    || command.sourceEndpointId() != null
                    || command.sinkEndpointId() != null
                    || command.sourceDataSourceId() != null
                    || command.targetDataSourceId() != null;
            if (semanticChanged) {
                throw new IllegalStateException("任务已启用或已暂停，语义字段不允许直接编辑，请复制或重建任务");
            }
        }
        String now = Instant.now().toString();
        // MVP：非语义字段（名称、Sink URL、Token 引用、期望实例）可更新；语义字段在 DRAFT 下更新
        String name = command.name() != null ? command.name() : existing.name();
        String readMode = existing.readMode();
        String readDefinitionJson = serializeReadDefinition(existing.readMode(), existing.readDefinition());
        if (command.readDefinition() != null) {
            readMode = command.readMode() == null ? existing.readMode() : command.readMode();
            readDefinitionJson = serializeReadDefinition(readMode, command.readDefinition());
        }
        String targetSchema = command.targetSchema() != null ? command.targetSchema() : existing.targetSchema();
        String targetTable = command.targetTable() != null ? command.targetTable() : existing.targetTable();
        String writeMode = command.writeMode() != null ? command.writeMode().name() : existing.writeMode().name();
        String uniqueKeysJson = command.uniqueKeys() != null
                ? toJson(command.uniqueKeys()) : toJson(existing.uniqueKeys());
        String fieldMappingsJson = command.fieldMappings() != null
                ? toJson(command.fieldMappings()) : toJson(existing.fieldMappings());
        String remoteSinkUrl = command.remoteSinkUrl() != null ? command.remoteSinkUrl() : existing.remoteSinkUrl();
        String sinkTokenRef = command.sinkTokenRef() != null ? command.sinkTokenRef() : existing.sinkTokenRef();
        String sinkInstanceId = command.expectedSinkInstanceId() != null
                ? command.expectedSinkInstanceId().toString() : existing.expectedSinkInstanceId() == null
                ? null : existing.expectedSinkInstanceId().toString();
        SinkBinding sink = resolveSinkBinding(command);
        if (sink.remoteSinkUrl() != null) {
            remoteSinkUrl = sink.remoteSinkUrl();
        }
        if (sink.instanceId() != null) {
            sinkInstanceId = sink.instanceId();
        }
        String sourceEndpointId = command.sourceEndpointId() != null
                ? command.sourceEndpointId() : existing.sourceEndpointId();
        String sinkEndpointId = command.sinkEndpointId() != null
                ? command.sinkEndpointId() : existing.sinkEndpointId();
        String sourceDataSourceId = command.sourceDataSourceId() != null
                ? command.sourceDataSourceId() : existing.sourceDataSourceId();
        String targetDataSourceId = command.targetDataSourceId() != null
                ? command.targetDataSourceId() : existing.targetDataSourceId();
        jdbcTemplate.update("""
                UPDATE task SET name = ?, read_mode = ?, read_definition = ?, target_schema = ?, target_table = ?,
                    write_mode = ?, unique_keys = ?, field_mappings = ?, remote_sink_url = ?,
                    sink_token_ref = ?, expected_sink_instance_id = ?,
                    source_endpoint_id = ?, sink_endpoint_id = ?, source_data_source_id = ?, target_data_source_id = ?,
                    updated_at = ?
                WHERE task_id = ?
                """, name, readMode, readDefinitionJson, targetSchema, targetTable, writeMode,
                uniqueKeysJson, fieldMappingsJson, remoteSinkUrl, sinkTokenRef, sinkInstanceId,
                sourceEndpointId, sinkEndpointId, sourceDataSourceId, targetDataSourceId, now, taskId.toString());
        return get(taskId).orElseThrow();
    }

    /** 删除任务：无活动 Run 时事务删除元数据并清理 Spool 目录。 */
    @Transactional
    public void delete(Identifiers.TaskId taskId) {
        TaskRecord existing = get(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (hasActiveRun(taskId)) {
            throw new IllegalStateException("任务存在活动 Run，不能删除");
        }
        // 事务删除关联数据
        jdbcTemplate.update("DELETE FROM alert WHERE task_id = ?", taskId.toString());
        jdbcTemplate.update("DELETE FROM checkpoint WHERE task_id = ?", taskId.toString());
        jdbcTemplate.update("DELETE FROM batch WHERE run_id IN (SELECT run_id FROM run WHERE task_id = ?)", taskId.toString());
        jdbcTemplate.update("DELETE FROM run WHERE task_id = ?", taskId.toString());
        jdbcTemplate.update("DELETE FROM task WHERE task_id = ?", taskId.toString());
        // 清理 Spool 目录（Task 14 落地 Spool 后此处生效）
        try {
            Path spoolDir = Path.of(roleProperties.dataDir(), "spool", taskId.toString());
            if (Files.exists(spoolDir)) {
                try (var paths = Files.walk(spoolDir)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ex) {
                            throw new IllegalStateException("Spool 清理失败: " + p, ex);
                        }
                    });
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Spool 清理失败，任务删除回滚: " + ex.getMessage(), ex);
        }
    }

    /** 启用前完整校验（连接 Source/Target）。 */
    public TaskValidator.ValidationReport validate(Identifiers.TaskId taskId) {
        TaskRecord task = get(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        return taskValidator.validateForEnable(task);
    }

    /** 启用任务（校验通过后）。 */
    @Transactional
    public TaskRecord enable(Identifiers.TaskId taskId) {
        TaskValidator.ValidationReport report = validate(taskId);
        if (!report.valid()) {
            throw new IllegalStateException("任务校验未通过，不能启用: " + report.firstIssueMessage());
        }
        String now = Instant.now().toString();
        jdbcTemplate.update("UPDATE task SET lifecycle_status = 'ENABLED', updated_at = ? WHERE task_id = ?",
                now, taskId.toString());
        return get(taskId).orElseThrow();
    }

    /** 暂停任务：停止新 Run，并暂停该任务的活动 Run。 */
    @Transactional
    public TaskRecord pause(Identifiers.TaskId taskId) {
        TaskRecord existing = get(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (existing.lifecycleStatus() != LifecycleStatus.ENABLED) {
            throw new IllegalStateException("仅已启用任务可以暂停");
        }
        jdbcTemplate.update("""
                UPDATE run SET status = 'PAUSED', pause_reason = '任务暂停'
                WHERE task_id = ? AND status IN ('RUNNING', 'WAITING_RETRY', 'UNKNOWN')
                """, taskId.toString());
        jdbcTemplate.update(
                "UPDATE task SET lifecycle_status = 'PAUSED', updated_at = ? WHERE task_id = ?",
                Instant.now().toString(), taskId.toString());
        return get(taskId).orElseThrow();
    }

    /** 继续任务：恢复为已启用，可再次触发新 Run。 */
    @Transactional
    public TaskRecord resume(Identifiers.TaskId taskId) {
        TaskRecord existing = get(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (existing.lifecycleStatus() != LifecycleStatus.PAUSED) {
            throw new IllegalStateException("仅已暂停任务可以继续");
        }
        jdbcTemplate.update(
                "UPDATE task SET lifecycle_status = 'ENABLED', updated_at = ? WHERE task_id = ?",
                Instant.now().toString(), taskId.toString());
        return get(taskId).orElseThrow();
    }

    /** 禁用任务：要求无活动 Run，之后不能再触发运行。 */
    @Transactional
    public TaskRecord disable(Identifiers.TaskId taskId) {
        TaskRecord existing = get(taskId).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        if (existing.lifecycleStatus() != LifecycleStatus.ENABLED
                && existing.lifecycleStatus() != LifecycleStatus.PAUSED
                && existing.lifecycleStatus() != LifecycleStatus.BLOCKED) {
            throw new IllegalStateException("当前状态不能禁用");
        }
        if (hasActiveRun(taskId)) {
            throw new IllegalStateException("任务存在活动 Run，不能禁用");
        }
        jdbcTemplate.update(
                "UPDATE task SET lifecycle_status = 'DISABLED', updated_at = ? WHERE task_id = ?",
                Instant.now().toString(), taskId.toString());
        return get(taskId).orElseThrow();
    }

    private boolean hasActiveRun(Identifiers.TaskId taskId) {
        String inClause = ACTIVE_RUN_STATUSES.stream()
                .map(s -> "'" + s + "'").reduce((a, b) -> a + "," + b).orElse("");
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM run WHERE task_id = ? AND status IN (" + inClause + ")",
                Integer.class, taskId.toString());
        return count != null && count > 0;
    }

    private String serializeReadDefinition(String mode, ReadDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("读取定义序列化失败", ex);
        }
    }

    private SinkBinding resolveSinkBinding(CreateTaskCommand command) {
        return resolveSinkBinding(
                command.sinkEndpointId(),
                command.remoteSinkUrl(),
                command.expectedSinkInstanceId());
    }

    private SinkBinding resolveSinkBinding(UpdateTaskCommand command) {
        return resolveSinkBinding(
                command.sinkEndpointId(),
                command.remoteSinkUrl(),
                command.expectedSinkInstanceId());
    }

    private SinkBinding resolveSinkBinding(String sinkEndpointId, String remoteSinkUrl,
                                           Identifiers.InstanceId expectedSinkInstanceId) {
        if (sinkEndpointId == null || sinkEndpointId.isBlank()) {
            return new SinkBinding(remoteSinkUrl,
                    expectedSinkInstanceId == null ? null : expectedSinkInstanceId.toString());
        }
        EndpointRecord endpoint = endpointService.resolveForTask(sinkEndpointId);
        return new SinkBinding(endpoint.baseUrl(), endpoint.instanceId());
    }

    private String resolveSourceEndpointId(CreateTaskCommand command) {
        return command.sourceEndpointId() != null && !command.sourceEndpointId().isBlank()
                ? command.sourceEndpointId() : EndpointService.SELF_SOURCE_ID;
    }

    private record SinkBinding(String remoteSinkUrl, String instanceId) {
    }

    private TaskRecord toRecord(String taskId, String name, int version, String status, String readMode,
                                String readDefinitionJson, String targetSchema, String targetTable,
                                String writeMode, String uniqueKeysJson, String fieldMappingsJson,
                                String remoteSinkUrl, String sinkTokenRef, String sinkInstanceId,
                                String sourceEndpointId, String sinkEndpointId,
                                String sourceDataSourceId, String targetDataSourceId,
                                String createdAt, String updatedAt) {
        ReadDefinition readDefinition;
        try {
            readDefinition = "SQL".equals(readMode)
                    ? objectMapper.readValue(readDefinitionJson, SqlReadDefinition.class)
                    : objectMapper.readValue(readDefinitionJson, TableReadDefinition.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("读取定义反序列化失败: " + taskId, ex);
        }
        return new TaskRecord(
                Identifiers.TaskId.fromString(taskId),
                name, version, LifecycleStatus.valueOf(status), readMode, readDefinition,
                targetSchema, targetTable, WriteMode.valueOf(writeMode),
                fromJson(uniqueKeysJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                }),
                fromJson(fieldMappingsJson, new com.fasterxml.jackson.core.type.TypeReference<List<FieldMapping>>() {
                }),
                remoteSinkUrl, sinkTokenRef,
                sinkInstanceId == null ? null : Identifiers.InstanceId.fromString(sinkInstanceId),
                sourceEndpointId, sinkEndpointId, sourceDataSourceId, targetDataSourceId,
                Instant.parse(createdAt), Instant.parse(updatedAt));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("JSON 序列化失败", ex);
        }
    }

    private <T> List<T> fromJson(String json, com.fasterxml.jackson.core.type.TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("JSON 反序列化失败", ex);
        }
    }

    /** 任务数量上限异常。 */
    public static class TaskLimitExceededException extends RuntimeException {
        public TaskLimitExceededException(int maxTasks) {
            super("已达到任务数量上限（" + maxTasks + "）");
        }
    }

    /** 任务记录（存储视图）。 */
    public record TaskRecord(
            Identifiers.TaskId taskId,
            String name,
            int version,
            LifecycleStatus lifecycleStatus,
            String readMode,
            ReadDefinition readDefinition,
            String targetSchema,
            String targetTable,
            WriteMode writeMode,
            List<String> uniqueKeys,
            List<FieldMapping> fieldMappings,
            String remoteSinkUrl,
            String sinkTokenRef,
            Identifiers.InstanceId expectedSinkInstanceId,
            String sourceEndpointId,
            String sinkEndpointId,
            String sourceDataSourceId,
            String targetDataSourceId,
            Instant createdAt,
            Instant updatedAt) {

        /** 仅用于无副作用预检的候选记录，不落库。 */
        public static TaskRecord unsaved(CreateTaskCommand command) {
            Instant now = Instant.now();
            return new TaskRecord(
                    Identifiers.TaskId.generate(),
                    command.name(),
                    1,
                    LifecycleStatus.DRAFT,
                    command.readMode(),
                    command.readDefinition(),
                    command.targetSchema(),
                    command.targetTable(),
                    command.writeMode(),
                    command.uniqueKeys(),
                    command.fieldMappings(),
                    command.remoteSinkUrl(),
                    command.sinkTokenRef(),
                    command.expectedSinkInstanceId(),
                    command.sourceEndpointId(),
                    command.sinkEndpointId(),
                    command.sourceDataSourceId(),
                    command.targetDataSourceId(),
                    now,
                    now);
        }
    }

    /** 创建任务命令。 */
    public record CreateTaskCommand(
            String name,
            String readMode,
            ReadDefinition readDefinition,
            String targetSchema,
            String targetTable,
            WriteMode writeMode,
            List<String> uniqueKeys,
            List<FieldMapping> fieldMappings,
            String remoteSinkUrl,
            String sinkTokenRef,
            Identifiers.InstanceId expectedSinkInstanceId,
            String sourceEndpointId,
            String sinkEndpointId,
            String sourceDataSourceId,
            String targetDataSourceId) {
    }

    /** 更新任务命令（null 字段表示不修改）。 */
    public record UpdateTaskCommand(
            String name,
            String readMode,
            ReadDefinition readDefinition,
            String targetSchema,
            String targetTable,
            WriteMode writeMode,
            List<String> uniqueKeys,
            List<FieldMapping> fieldMappings,
            String remoteSinkUrl,
            String sinkTokenRef,
            Identifiers.InstanceId expectedSinkInstanceId,
            String sourceEndpointId,
            String sinkEndpointId,
            String sourceDataSourceId,
            String targetDataSourceId) {
    }
}
