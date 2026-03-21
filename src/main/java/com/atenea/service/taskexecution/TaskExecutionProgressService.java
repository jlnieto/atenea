package com.atenea.service.taskexecution;

import com.atenea.persistence.taskexecution.TaskExecutionEntity;
import com.atenea.persistence.taskexecution.TaskExecutionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskExecutionProgressService {

    private final TaskExecutionRepository taskExecutionRepository;

    public TaskExecutionProgressService(TaskExecutionRepository taskExecutionRepository) {
        this.taskExecutionRepository = taskExecutionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistExternalThreadId(Long executionId, String threadId) {
        updateExternalIds(executionId, threadId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistExternalTurnId(Long executionId, String turnId) {
        updateExternalIds(executionId, null, turnId);
    }

    private void updateExternalIds(Long executionId, String threadId, String turnId) {
        TaskExecutionEntity taskExecution = taskExecutionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException("Task execution not found: " + executionId));

        if (threadId != null && !threadId.isBlank()) {
            taskExecution.setExternalThreadId(threadId.trim());
        }
        if (turnId != null && !turnId.isBlank()) {
            taskExecution.setExternalTurnId(turnId.trim());
        }

        taskExecutionRepository.save(taskExecution);
    }
}
