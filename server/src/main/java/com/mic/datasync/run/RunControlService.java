package com.mic.datasync.run;

import com.mic.datasync.run.RunService.RunRecord;
import com.mic.datasync.run.domain.RunStatus;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.task.TaskService;
import com.mic.datasync.task.TaskService.TaskRecord;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 运行控制：暂停与继续（复用原 Run）。
 */
@Service
public class RunControlService {

    private final RunService runService;
    private final TaskService taskService;
    private final RunEngine runEngine;
    private final TaskExecutor taskExecutor;

    public RunControlService(RunService runService, TaskService taskService,
                             RunEngine runEngine, TaskExecutor taskExecutor) {
        this.runService = runService;
        this.taskService = taskService;
        this.runEngine = runEngine;
        this.taskExecutor = taskExecutor;
    }

    /** 暂停：不读取新批次（已 UNKNOWN 的批次由恢复流程核对回执）。 */
    public RunRecord pause(Identifiers.RunId runId) {
        RunRecord run = runService.get(runId)
                .orElseThrow(() -> new IllegalArgumentException("运行不存在"));
        runService.updateStatus(runId, RunStatus.PAUSED, "管理员暂停", run.sourceRowCount(), run.confirmedRowCount());
        return runService.get(runId).orElseThrow();
    }

    /** 继续：复用原 Run，先恢复 PENDING/UNKNOWN Spool 再读取新数据。 */
    public RunRecord resume(Identifiers.RunId runId) {
        RunRecord run = runService.get(runId)
                .orElseThrow(() -> new IllegalArgumentException("运行不存在"));
        if (run.status().isTerminal()) {
            throw new IllegalStateException("运行已终态，不能继续");
        }
        TaskRecord task = taskService.get(run.taskId())
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        runService.updateStatus(runId, RunStatus.RUNNING, null, run.sourceRowCount(), run.confirmedRowCount());
        RunRecord updated = runService.get(runId).orElseThrow();
        taskExecutor.execute(() -> runEngine.resume(task, updated));
        return updated;
    }
}
