package com.mic.datasync.source;

import com.mic.datasync.run.RunEngine;
import com.mic.datasync.run.RunService.RunKind;
import com.mic.datasync.run.RunService.RunRecord;
import com.mic.datasync.task.TaskService.TaskRecord;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 首次全量 + 自动追赶执行器（内部委托 RunEngine）。
 */
@Component
public class FullSyncExecutor {

    private final RunEngine runEngine;
    private final TaskExecutor taskExecutor;

    public FullSyncExecutor(RunEngine runEngine, TaskExecutor taskExecutor) {
        this.runEngine = runEngine;
        this.taskExecutor = taskExecutor;
    }

    /** 创建 Run 后异步执行首次全量并自动追赶。 */
    public RunRecord start(TaskRecord task) {
        RunRecord run = runEngine.createRun(task, RunKind.INITIAL_FULL, null);
        taskExecutor.execute(() -> runEngine.executeCreated(task, run));
        return run;
    }

    /** 执行首次全量并自动追赶。 */
    public void executeInitialFull(TaskRecord task) {
        runEngine.execute(task, RunKind.INITIAL_FULL);
    }
}
