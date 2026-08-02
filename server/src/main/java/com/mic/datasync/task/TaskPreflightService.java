package com.mic.datasync.task;

import com.mic.datasync.endpoint.EndpointRecord;
import com.mic.datasync.endpoint.EndpointService;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.task.TaskService.CreateTaskCommand;
import com.mic.datasync.task.TaskService.TaskRecord;
import com.mic.datasync.task.TaskValidator.ValidationReport;
import org.springframework.stereotype.Service;

/**
 * 创建前预检：复用启用校验，但使用未落库的候选任务，不产生任何副作用。
 */
@Service
public class TaskPreflightService {

    private final TaskValidator validator;
    private final EndpointService endpointService;

    public TaskPreflightService(TaskValidator validator, EndpointService endpointService) {
        this.validator = validator;
        this.endpointService = endpointService;
    }

    public ValidationReport preflight(CreateTaskCommand command) {
        CreateTaskCommand resolved = resolveSinkBinding(command);
        TaskRecord candidate = TaskRecord.unsaved(resolved);
        return validator.validateForEnable(candidate);
    }

    /** 预检与创建保持同一语义：按 Sink 端解析 URL 与实例 ID。 */
    private CreateTaskCommand resolveSinkBinding(CreateTaskCommand command) {
        if (command.sinkEndpointId() == null || command.sinkEndpointId().isBlank()) {
            return command;
        }
        EndpointRecord endpoint = endpointService.resolveForTask(command.sinkEndpointId());
        return new CreateTaskCommand(
                command.name(),
                command.readMode(),
                command.readDefinition(),
                command.targetSchema(),
                command.targetTable(),
                command.writeMode(),
                command.uniqueKeys(),
                command.fieldMappings(),
                endpoint.baseUrl(),
                command.sinkTokenRef(),
                Identifiers.InstanceId.fromString(endpoint.instanceId()),
                command.sourceEndpointId(),
                command.sinkEndpointId(),
                command.sourceDataSourceId(),
                command.targetDataSourceId());
    }
}
