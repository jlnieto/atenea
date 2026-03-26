package com.atenea.api.taskexecution;

import com.atenea.persistence.taskexecution.TaskExecutionStatus;
import com.atenea.service.taskexecution.TaskExecutionQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/executions")
public class TaskExecutionQueryController {

    private final TaskExecutionQueryService taskExecutionQueryService;

    public TaskExecutionQueryController(TaskExecutionQueryService taskExecutionQueryService) {
        this.taskExecutionQueryService = taskExecutionQueryService;
    }

    @GetMapping
    public List<TaskExecutionListItemResponse> getExecutions(
            @RequestParam(required = false) TaskExecutionStatus status,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Integer limit
    ) {
        return taskExecutionQueryService.getExecutions(status, projectId, limit);
    }
}
