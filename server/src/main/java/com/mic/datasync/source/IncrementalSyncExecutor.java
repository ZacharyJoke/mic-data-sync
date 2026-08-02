package com.mic.datasync.source;

import com.mic.datasync.run.RunEngine;
import com.mic.datasync.run.RunService.RunKind;
import com.mic.datasync.run.RunService.RunRecord;
import com.mic.datasync.task.TaskService.TaskRecord;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 手动增量执行器（内部委托 RunEngine，从 Checkpoint 减回看窗口）。
 */
@Component
public class IncrementalSyncExecutor {

    private final RunEngine runEngine;
    private final TaskExecutor taskExecutor;

    public IncrementalSyncExecutor(RunEngine runEngine, TaskExecutor taskExecutor) {
        this.runEngine = runEngine;
        this.taskExecutor = taskExecutor;
    }

    /** 创建 Run 后异步执行手动增量。 */
    public RunRecord start(TaskRecord task) {
        RunRecord run = runEngine.createRun(task, RunKind.INCREMENTAL, null);
        taskExecutor.execute(() -> runEngine.executeCreated(task, run));
        return run;
    }

    /** 执行手动增量。 */
    public void executeIncremental(TaskRecord task) {
        runEngine.execute(task, RunKind.INCREMENTAL);
    }
}
