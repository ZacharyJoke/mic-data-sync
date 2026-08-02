package com.mic.datasync.webapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.run.domain.RunStatus;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.shared.page.PageResult;
import com.mic.datasync.source.domain.ReadDefinition;
import com.mic.datasync.source.domain.ReadDefinition.ReadMode;
import com.mic.datasync.source.domain.SqlReadDefinition;
import com.mic.datasync.source.domain.TableReadDefinition;
import com.mic.datasync.task.FieldMapping;
import com.mic.datasync.task.TaskQueryService;
import com.mic.datasync.task.TaskQueryService.LatestRunSummary;
import com.mic.datasync.task.TaskQueryService.TaskListItem;
import com.mic.datasync.task.TaskQueryService.TaskQuery;
import com.mic.datasync.task.TaskService;
import com.mic.datasync.task.TaskService.CreateTaskCommand;
import com.mic.datasync.task.TaskService.TaskRecord;
import com.mic.datasync.task.TaskService.TaskLimitExceededException;
import com.mic.datasync.task.TaskService.UpdateTaskCommand;
import com.mic.datasync.task.TaskPreflightService;
import com.mic.datasync.task.TaskValidator.ValidationReport;
import com.mic.datasync.task.domain.TaskDefinition.LifecycleStatus;
import com.mic.datasync.task.domain.TaskDefinition.WriteMode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 任务配置接口（需管理员登录）。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskQueryService taskQueryService;
    private final TaskPreflightService taskPreflightService;
    private final ObjectMapper objectMapper;

    public TaskController(TaskService taskService, TaskQueryService taskQueryService,
                          TaskPreflightService taskPreflightService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.taskQueryService = taskQueryService;
        this.taskPreflightService = taskPreflightService;
        this.objectMapper = objectMapper;
    }

    /** 任务列表（分页）。 */
    @GetMapping
    public PageResult<TaskResponse> list(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String lifecycleStatus,
            @RequestParam(required = false) String readMode,
            @RequestParam(required = false) String latestRunStatus) {
        int parsedPage = parsePageParameter(page, "page");
        int parsedSize = parsePageParameter(size, "size");
        validatePage(parsedPage, parsedSize);
        TaskQuery query = new TaskQuery(
                parsedPage,
                parsedSize,
                keyword,
                parseEnumFilter(lifecycleStatus, LifecycleStatus.class, "lifecycleStatus"),
                parseReadMode(readMode),
                parseEnumFilter(latestRunStatus, RunStatus.class, "latestRunStatus"));
        return taskQueryService.search(query).map(this::toResponse);
    }

    /** 任务详情。 */
    @GetMapping("/{taskId}")
    public ResponseEntity<?> get(@PathVariable String taskId) {
        java.util.Optional<TaskRecord> record = taskService.get(parseTaskId(taskId));
        if (record.isEmpty()) {
            return notFound("任务不存在");
        }
        return ResponseEntity.ok(toResponse(record.get()));
    }

    /** 创建任务（草稿）。 */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody TaskRequest request) {
        try {
            CreateTaskCommand command = toCreateCommand(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(taskService.create(command)));
        } catch (TaskLimitExceededException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody("TASK_LIMIT_REACHED", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorBody("VALIDATION_FAILED", ex.getMessage()));
        }
    }

    /** 创建前预检（无副作用）。 */
    @PostMapping("/preflight")
    public ValidationReport preflight(@RequestBody TaskRequest request) {
        return taskPreflightService.preflight(toCreateCommand(request));
    }

    /** 更新任务（草稿全量；已启用任务语义字段锁定）。 */
    @PutMapping("/{taskId}")
    public ResponseEntity<?> update(@PathVariable String taskId, @RequestBody TaskRequest request) {
        try {
            UpdateTaskCommand command = toUpdateCommand(request);
            return ResponseEntity.ok(toResponse(taskService.update(parseTaskId(taskId), command)));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody("VALIDATION_FAILED", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorBody("VALIDATION_FAILED", ex.getMessage()));
        }
    }

    /** 删除任务（无活动 Run）。 */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<?> delete(@PathVariable String taskId) {
        try {
            taskService.delete(parseTaskId(taskId));
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody("VALIDATION_FAILED", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return notFound(ex.getMessage());
        }
    }

    /** 启用前校验报告。 */
    @PostMapping("/{taskId}/validate")
    public ResponseEntity<?> validate(@PathVariable String taskId) {
        try {
            ValidationReport report = taskService.validate(parseTaskId(taskId));
            return ResponseEntity.ok(report);
        } catch (IllegalArgumentException ex) {
            return notFound(ex.getMessage());
        }
    }

    /** 启用任务。 */
    @PostMapping("/{taskId}/enable")
    public ResponseEntity<?> enable(@PathVariable String taskId) {
        try {
            return ResponseEntity.ok(toResponse(taskService.enable(parseTaskId(taskId))));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody("VALIDATION_FAILED", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return notFound(ex.getMessage());
        }
    }

    /** 暂停任务。 */
    @PostMapping("/{taskId}/pause")
    public ResponseEntity<?> pause(@PathVariable String taskId) {
        try {
            return ResponseEntity.ok(toResponse(taskService.pause(parseTaskId(taskId))));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody("VALIDATION_FAILED", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return notFound(ex.getMessage());
        }
    }

    /** 继续任务。 */
    @PostMapping("/{taskId}/resume")
    public ResponseEntity<?> resume(@PathVariable String taskId) {
        try {
            return ResponseEntity.ok(toResponse(taskService.resume(parseTaskId(taskId))));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody("VALIDATION_FAILED", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return notFound(ex.getMessage());
        }
    }

    /** 禁用任务。 */
    @PostMapping("/{taskId}/disable")
    public ResponseEntity<?> disable(@PathVariable String taskId) {
        try {
            return ResponseEntity.ok(toResponse(taskService.disable(parseTaskId(taskId))));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody("VALIDATION_FAILED", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return notFound(ex.getMessage());
        }
    }

    private CreateTaskCommand toCreateCommand(TaskRequest request) {
        return new CreateTaskCommand(
                request.name(),
                request.readMode(),
                convertReadDefinition(request),
                request.targetSchema(),
                request.targetTable(),
                WriteMode.valueOf(request.writeMode()),
                request.uniqueKeys(),
                request.fieldMappings(),
                request.remoteSinkUrl(),
                request.sinkTokenRef(),
                parseInstanceId(request.expectedSinkInstanceId()),
                request.sourceEndpointId(),
                request.sinkEndpointId(),
                request.sourceDataSourceId(),
                request.targetDataSourceId());
    }

    private UpdateTaskCommand toUpdateCommand(TaskRequest request) {
        return new UpdateTaskCommand(
                request.name(),
                request.readMode(),
                request.readDefinition() == null ? null : convertReadDefinition(request),
                request.targetSchema(),
                request.targetTable(),
                request.writeMode() == null ? null : WriteMode.valueOf(request.writeMode()),
                request.uniqueKeys(),
                request.fieldMappings(),
                request.remoteSinkUrl(),
                request.sinkTokenRef(),
                parseInstanceId(request.expectedSinkInstanceId()),
                request.sourceEndpointId(),
                request.sinkEndpointId(),
                request.sourceDataSourceId(),
                request.targetDataSourceId());
    }

    private ReadDefinition convertReadDefinition(TaskRequest request) {
        if (request.readDefinition() == null) {
            return null;
        }
        try {
            if ("SQL".equals(request.readMode())) {
                return objectMapper.convertValue(request.readDefinition(), SqlReadDefinition.class);
            }
            return objectMapper.convertValue(request.readDefinition(), TableReadDefinition.class);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("读取定义格式错误", ex);
        }
    }

    private TaskResponse toResponse(TaskRecord record) {
        return new TaskResponse(
                record.taskId().toString(),
                record.name(),
                record.version(),
                record.lifecycleStatus().name(),
                record.readMode(),
                record.readDefinition(),
                record.targetSchema(),
                record.targetTable(),
                record.writeMode().name(),
                record.uniqueKeys(),
                record.fieldMappings(),
                record.remoteSinkUrl(),
                record.expectedSinkInstanceId() == null ? null : record.expectedSinkInstanceId().toString(),
                record.sourceEndpointId(),
                record.sinkEndpointId(),
                record.sourceDataSourceId(),
                record.targetDataSourceId(),
                record.createdAt().toString(),
                record.updatedAt().toString(),
                null);
    }

    private TaskResponse toResponse(TaskListItem item) {
        TaskResponse task = toResponse(item.task());
        return new TaskResponse(
                task.taskId(),
                task.name(),
                task.version(),
                task.lifecycleStatus(),
                task.readMode(),
                task.readDefinition(),
                task.targetSchema(),
                task.targetTable(),
                task.writeMode(),
                task.uniqueKeys(),
                task.fieldMappings(),
                task.remoteSinkUrl(),
                task.expectedSinkInstanceId(),
                task.sourceEndpointId(),
                task.sinkEndpointId(),
                task.sourceDataSourceId(),
                task.targetDataSourceId(),
                task.createdAt(),
                task.updatedAt(),
                toResponse(item.latestRun()));
    }

    private LatestRunResponse toResponse(LatestRunSummary latestRun) {
        if (latestRun == null) {
            return null;
        }
        return new LatestRunResponse(
                latestRun.runId(),
                latestRun.kind(),
                latestRun.status(),
                latestRun.startedAt(),
                latestRun.endedAt());
    }

    private void validatePage(int page, int size) {
        if (page < 1) {
            throw new ApiRequestException(
                    "INVALID_PAGE_REQUEST", "page 必须大于等于 1", "page");
        }
        if (size < 1 || size > 100) {
            throw new ApiRequestException(
                    "INVALID_PAGE_REQUEST", "size 必须在 1 到 100 之间", "size");
        }
    }

    private int parsePageParameter(String value, String field) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new ApiRequestException(
                    "INVALID_PAGE_REQUEST", field + " 必须是整数", field);
        }
    }

    private String parseReadMode(String value) {
        ReadMode mode = parseEnumFilter(value, ReadMode.class, "readMode");
        return mode == null ? null : mode.name();
    }

    private <E extends Enum<E>> E parseEnumFilter(String value, Class<E> enumType, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiRequestException(
                    "INVALID_FILTER_VALUE", field + " 取值非法", field);
        }
    }

    private Identifiers.TaskId parseTaskId(String taskId) {
        try {
            return Identifiers.TaskId.fromString(taskId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("任务 ID 格式非法");
        }
    }

    private Identifiers.InstanceId parseInstanceId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Identifiers.InstanceId.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("expectedSinkInstanceId 格式非法");
        }
    }

    private ResponseEntity<?> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("VALIDATION_FAILED", message));
    }

    private Map<String, Object> errorBody(String code, String message) {
        return Map.of("code", code, "message", message, "requestId", UUID.randomUUID().toString(), "details", Map.of());
    }

    /** 创建/更新请求体。 */
    public record TaskRequest(
            String name,
            String readMode,
            JsonNode readDefinition,
            String targetSchema,
            String targetTable,
            String writeMode,
            List<String> uniqueKeys,
            List<FieldMapping> fieldMappings,
            String remoteSinkUrl,
            String sinkTokenRef,
            String expectedSinkInstanceId,
            String sourceEndpointId,
            String sinkEndpointId,
            String sourceDataSourceId,
            String targetDataSourceId) {
    }

    /** 任务响应。 */
    public record TaskResponse(
            String taskId,
            String name,
            int version,
            String lifecycleStatus,
            String readMode,
            ReadDefinition readDefinition,
            String targetSchema,
            String targetTable,
            String writeMode,
            List<String> uniqueKeys,
            List<FieldMapping> fieldMappings,
            String remoteSinkUrl,
            String expectedSinkInstanceId,
            String sourceEndpointId,
            String sinkEndpointId,
            String sourceDataSourceId,
            String targetDataSourceId,
            String createdAt,
            String updatedAt,
            LatestRunResponse latestRun) {
    }

    /** 任务最近运行摘要。 */
    public record LatestRunResponse(
            String runId,
            String kind,
            String status,
            String startedAt,
            String endedAt) {
    }
}
