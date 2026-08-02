package com.mic.datasync.webapi;

import com.mic.datasync.run.RunActionService;
import com.mic.datasync.run.RunActionService.RunActions;
import com.mic.datasync.run.RunControlService;
import com.mic.datasync.run.RunFailureService;
import com.mic.datasync.run.RunFailureService.RunDiagnosis;
import com.mic.datasync.run.RunQueryService;
import com.mic.datasync.run.RunQueryService.BatchRecord;
import com.mic.datasync.run.RunQueryService.RunQuery;
import com.mic.datasync.run.RunService;
import com.mic.datasync.run.RunService.RunKind;
import com.mic.datasync.run.RunService.RunRecord;
import com.mic.datasync.run.domain.RunStatus;
import com.mic.datasync.run.RunSlotGuard;
import com.mic.datasync.shared.command.CommandResult;
import com.mic.datasync.shared.error.ErrorCode;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.shared.page.PageResult;
import com.mic.datasync.source.FullSyncExecutor;
import com.mic.datasync.source.IncrementalSyncExecutor;
import com.mic.datasync.task.TaskService;
import com.mic.datasync.task.TaskService.TaskRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 运行接口（需管理员登录）。
 */
@RestController
@RequestMapping("/api/v1")
public class RunController {

    private final TaskService taskService;
    private final RunService runService;
    private final RunQueryService runQueryService;
    private final RunFailureService runFailureService;
    private final RunActionService runActionService;
    private final RunSlotGuard runSlotGuard;
    private final RunControlService runControlService;
    private final FullSyncExecutor fullSyncExecutor;
    private final IncrementalSyncExecutor incrementalSyncExecutor;

    public RunController(TaskService taskService, RunService runService, RunQueryService runQueryService,
                         RunFailureService runFailureService,
                         RunActionService runActionService,
                         RunSlotGuard runSlotGuard,
                         RunControlService runControlService,
                         FullSyncExecutor fullSyncExecutor, IncrementalSyncExecutor incrementalSyncExecutor) {
        this.taskService = taskService;
        this.runService = runService;
        this.runQueryService = runQueryService;
        this.runFailureService = runFailureService;
        this.runActionService = runActionService;
        this.runSlotGuard = runSlotGuard;
        this.runControlService = runControlService;
        this.fullSyncExecutor = fullSyncExecutor;
        this.incrementalSyncExecutor = incrementalSyncExecutor;
    }

    /** 触发首次全量（自动追赶）。 */
    @PostMapping("/tasks/{taskId}/runs/full")
    public ResponseEntity<?> startFull(@PathVariable String taskId) {
        TaskRecord task = taskById(taskId);
        if (!task.lifecycleStatus().name().equals("ENABLED")) {
            return conflict("VALIDATION_FAILED", "任务未启用");
        }
        if (!runSlotGuard.hasSlot()) {
            return conflict(ErrorCode.GLOBAL_CONCURRENCY_LIMIT.name(), "全局并发名额已满");
        }
        RunRecord run = fullSyncExecutor.start(task);
        return ResponseEntity.accepted().body(CommandResult.accepted(
                run.runId().toString(), run.status().name(), "首次全量已开始"));
    }

    /** 触发手动增量。 */
    @PostMapping("/tasks/{taskId}/runs/incremental")
    public ResponseEntity<?> startIncremental(@PathVariable String taskId) {
        TaskRecord task = taskById(taskId);
        if (!task.lifecycleStatus().name().equals("ENABLED")) {
            return conflict("VALIDATION_FAILED", "任务未启用");
        }
        if (!runSlotGuard.hasSlot()) {
            return conflict(ErrorCode.GLOBAL_CONCURRENCY_LIMIT.name(), "全局并发名额已满");
        }
        RunRecord run = incrementalSyncExecutor.start(task);
        return ResponseEntity.accepted().body(CommandResult.accepted(
                run.runId().toString(), run.status().name(), "手动增量已开始"));
    }

    /** 运行列表（分页）。 */
    @GetMapping("/runs")
    public PageResult<RunResponse> listRuns(
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String startedFrom,
            @RequestParam(required = false) String startedTo,
            @RequestParam(required = false) String keyword) {
        return runQueryService.search(parseRunQuery(
                page, size, status, taskId, kind, startedFrom, startedTo, keyword))
                .map(this::toResponse);
    }

    /** 任务运行历史（分页）。 */
    @GetMapping("/tasks/{taskId}/runs")
    public PageResult<RunResponse> taskRuns(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String startedFrom,
            @RequestParam(required = false) String startedTo,
            @RequestParam(required = false) String keyword) {
        RunQuery query = parseRunQuery(
                page, size, status, null, kind, startedFrom, startedTo, keyword);
        return runQueryService.searchByTask(parseTaskId(taskId), query).map(this::toResponse);
    }

    /** 运行详情。 */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> runDetail(@PathVariable String runId) {
        return runService.get(parseRunId(runId))
                .map(r -> ResponseEntity.ok(toResponse(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 运行失败诊断。 */
    @GetMapping("/runs/{runId}/diagnosis")
    public ResponseEntity<RunDiagnosis> diagnosis(@PathVariable String runId) {
        return runFailureService.diagnosis(parseRunId(runId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 暂停运行。 */
    @PostMapping("/runs/{runId}/pause")
    public ResponseEntity<?> pause(@PathVariable String runId) {
        try {
            RunRecord run = runControlService.pause(parseRunId(runId));
            return ResponseEntity.ok(CommandResult.accepted(
                    run.runId().toString(), run.status().name(), "已暂停"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 继续运行（复用原 Run）。 */
    @PostMapping("/runs/{runId}/resume")
    public ResponseEntity<?> resume(@PathVariable String runId) {
        try {
            RunRecord run = runControlService.resume(parseRunId(runId));
            return ResponseEntity.accepted().body(CommandResult.accepted(
                    run.runId().toString(), run.status().name(), "继续已开始"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException ex) {
            return conflict("VALIDATION_FAILED", ex.getMessage());
        }
    }

    /** 运行可用动作。 */
    @GetMapping("/runs/{runId}/actions")
    public RunActions actions(@PathVariable String runId) {
        return runActionService.actions(parseRunId(runId));
    }

    /** 安全重试：幂等键保证同一操作只创建一个新 Run。 */
    @PostMapping("/runs/{runId}/retry")
    public ResponseEntity<CommandResult> retry(
            @PathVariable String runId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            Authentication authentication) {
        return ResponseEntity.accepted().body(runActionService.retry(
                parseRunId(runId), authentication.getName(), idempotencyKey));
    }

    /** 运行批次列表（分页）。 */
    @GetMapping("/runs/{runId}/batches")
    public PageResult<BatchResponse> runBatches(
            @PathVariable String runId,
            @RequestParam(defaultValue = "1") String page,
            @RequestParam(defaultValue = "20") String size) {
        int parsedPage = parsePageParameter(page, "page");
        int parsedSize = parsePageParameter(size, "size");
        validatePage(parsedPage, parsedSize);
        return runQueryService.batches(parseRunId(runId), parsedPage, parsedSize)
                .map(this::toBatchResponse);
    }

    private RunQuery parseRunQuery(
            String page, String size, String status, String taskId, String kind,
            String startedFrom, String startedTo, String keyword) {
        int parsedPage = parsePageParameter(page, "page");
        int parsedSize = parsePageParameter(size, "size");
        validatePage(parsedPage, parsedSize);
        return new RunQuery(
                parsedPage,
                parsedSize,
                parseEnumFilter(status, RunStatus.class, "status"),
                parseTaskIdFilter(taskId),
                parseEnumFilter(kind, RunKind.class, "kind"),
                parseInstantFilter(startedFrom, "startedFrom"),
                parseInstantFilter(startedTo, "startedTo"),
                keyword);
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

    private <E extends Enum<E>> E parseEnumFilter(String value, Class<E> enumType, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ApiRequestException(
                    "INVALID_FILTER_VALUE", field + " 取值非法", field);
        }
    }

    private Identifiers.TaskId parseTaskIdFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Identifiers.TaskId.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiRequestException(
                    "INVALID_FILTER_VALUE", "taskId 格式非法", "taskId");
        }
    }

    private Instant parseInstantFilter(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (java.time.format.DateTimeParseException ex) {
            throw new ApiRequestException(
                    "INVALID_FILTER_VALUE", field + " 必须是 ISO-8601 时间", field);
        }
    }

    private Identifiers.RunId parseRunId(String runId) {
        try {
            return Identifiers.RunId.fromString(runId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("运行 ID 格式非法");
        }
    }

    private TaskRecord taskById(String taskId) {
        Optional<TaskRecord> task = taskService.get(parseTaskId(taskId));
        return task.orElseThrow(() -> new IllegalArgumentException("任务不存在"));
    }

    private Identifiers.TaskId parseTaskId(String taskId) {
        try {
            return Identifiers.TaskId.fromString(taskId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("任务 ID 格式非法");
        }
    }

    private ResponseEntity<?> conflict(String code, String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", code, "message", message, "requestId", UUID.randomUUID().toString(), "details", Map.of()));
    }

    private RunResponse toResponse(RunRecord record) {
        return new RunResponse(
                record.runId().toString(),
                record.taskId().toString(),
                record.taskNameSnapshot(),
                record.kind().name(),
                record.status().name(),
                record.pauseReason(),
                record.startedAt().toString(),
                record.endedAt() == null ? null : record.endedAt().toString(),
                record.sourceRowCount(),
                record.confirmedRowCount());
    }

    private BatchResponse toBatchResponse(BatchRecord record) {
        return new BatchResponse(
                record.batchId(),
                record.runId(),
                record.batchSequence(),
                record.payloadHash(),
                record.rowCount(),
                record.status(),
                record.attemptCount(),
                record.createdAt());
    }

    /** 批次响应。 */
    public record BatchResponse(
            String batchId,
            String runId,
            long batchSequence,
            String payloadHash,
            long rowCount,
            String status,
            int attemptCount,
            String createdAt) {
    }

    /** 运行响应。 */
    public record RunResponse(
            String runId,
            String taskId,
            String taskName,
            String kind,
            String status,
            String pauseReason,
            String startedAt,
            String endedAt,
            long sourceRowCount,
            long confirmedRowCount) {
    }
}
