package com.mic.datasync.run;

import com.mic.datasync.run.RunFailureService.RunFailure;
import com.mic.datasync.run.RunService.RunRecord;
import com.mic.datasync.run.domain.RunStatus;
import com.mic.datasync.shared.command.CommandResult;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.task.TaskService;
import com.mic.datasync.task.TaskService.TaskRecord;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 运行动作能力判定与安全重试。
 *
 * <p>前端只渲染这里返回的可用动作；重试通过幂等键保证同一操作只创建一个新 Run，
 * 旧 Run、旧失败记录和旧批次保持不变。</p>
 */
@Service
public class RunActionService {

    private final RunService runService;
    private final RunFailureService failureService;
    private final TaskService taskService;
    private final RunEngine runEngine;
    private final TaskExecutor taskExecutor;
    private final JdbcTemplate jdbcTemplate;

    public RunActionService(RunService runService,
                            RunFailureService failureService,
                            TaskService taskService,
                            RunEngine runEngine,
                            TaskExecutor taskExecutor,
                            JdbcTemplate jdbcTemplate) {
        this.runService = runService;
        this.failureService = failureService;
        this.taskService = taskService;
        this.runEngine = runEngine;
        this.taskExecutor = taskExecutor;
        this.jdbcTemplate = jdbcTemplate;
    }

    public RunActions actions(Identifiers.RunId runId) {
        RunRecord run = requireRun(runId);
        Optional<RunFailure> failure = failureService.get(runId);
        List<RunAction> actions = new ArrayList<>();
        actions.add(action("PAUSE", run.status() == RunStatus.RUNNING, "仅运行中的 Run 可暂停"));
        actions.add(action("RESUME", run.status() == RunStatus.PAUSED, "仅已暂停的 Run 可继续"));
        actions.add(action("REVALIDATE", run.status() == RunStatus.FAILED, "仅失败运行需要重新校验"));
        actions.add(action("RETRY",
                run.status() == RunStatus.FAILED && failure.map(RunFailure::retryable).orElse(false),
                "该失败不是可安全重试类型"));
        return new RunActions(runId.toString(), actions);
    }

    @Transactional
    public CommandResult retry(Identifiers.RunId originalRunId, String actor, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        Optional<String> existing = findRetry(originalRunId, actor, idempotencyKey);
        if (existing.isPresent()) {
            RunRecord run = runService.get(Identifiers.RunId.fromString(existing.get())).orElseThrow();
            return command(run, "已返回先前创建的重试运行");
        }

        RunRecord original = requireRetryableFailure(originalRunId);
        TaskRecord task = taskService.get(original.taskId()).orElseThrow();
        RunRecord retried = runEngine.createRun(task, original.kind(), original.runId());
        insertRetryRequest(originalRunId, actor, idempotencyKey, retried.runId());
        taskExecutor.execute(() -> runEngine.executeCreated(task, retried));
        return command(retried, "安全重试已开始");
    }

    private RunRecord requireRetryableFailure(Identifiers.RunId runId) {
        RunRecord run = requireRun(runId);
        if (run.status() != RunStatus.FAILED) {
            throw new IllegalStateException("仅失败运行可以重试");
        }
        boolean retryable = failureService.get(runId)
                .map(RunFailure::retryable)
                .orElse(false);
        if (!retryable) {
            throw new IllegalStateException("该失败不是可安全重试类型");
        }
        return run;
    }

    private RunRecord requireRun(Identifiers.RunId runId) {
        return runService.get(runId)
                .orElseThrow(() -> new IllegalArgumentException("运行不存在"));
    }

    private Optional<String> findRetry(Identifiers.RunId originalRunId, String actor, String idempotencyKey) {
        List<String> rows = jdbcTemplate.queryForList("""
                SELECT new_run_id FROM run_retry_request
                WHERE original_run_id = ? AND actor = ? AND idempotency_key = ?
                """, String.class, originalRunId.toString(), actor, idempotencyKey);
        return rows.stream().findFirst();
    }

    private void insertRetryRequest(Identifiers.RunId originalRunId, String actor,
                                    String idempotencyKey, Identifiers.RunId newRunId) {
        jdbcTemplate.update("""
                INSERT INTO run_retry_request (
                    original_run_id, actor, idempotency_key, new_run_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, originalRunId.toString(), actor, idempotencyKey, newRunId.toString(),
                Instant.now().toString());
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key 必填且不能超过 128 字符");
        }
    }

    private CommandResult command(RunRecord run, String message) {
        return CommandResult.accepted(run.runId().toString(), run.status().name(), message);
    }

    private static RunAction action(String type, boolean enabled, String reason) {
        return new RunAction(type, enabled, reason);
    }

    public record RunAction(String type, boolean enabled, String reason) {
    }

    public record RunActions(String runId, List<RunAction> actions) {
    }
}
