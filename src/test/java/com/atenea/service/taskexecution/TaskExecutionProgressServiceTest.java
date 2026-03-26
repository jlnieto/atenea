package com.atenea.service.taskexecution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.persistence.taskexecution.TaskExecutionEntity;
import com.atenea.persistence.taskexecution.TaskExecutionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskExecutionProgressServiceTest {

    @Mock
    private TaskExecutionRepository taskExecutionRepository;

    @InjectMocks
    private TaskExecutionProgressService taskExecutionProgressService;

    @Test
    void persistExternalThreadIdTrimsAndSaves() {
        TaskExecutionEntity execution = new TaskExecutionEntity();
        execution.setId(15L);
        when(taskExecutionRepository.findById(15L)).thenReturn(Optional.of(execution));

        taskExecutionProgressService.persistExternalThreadId(15L, "  thread-123  ");

        assertEquals("thread-123", execution.getExternalThreadId());
        verify(taskExecutionRepository).save(execution);
    }

    @Test
    void persistExternalTurnIdIgnoresBlankValue() {
        TaskExecutionEntity execution = new TaskExecutionEntity();
        execution.setId(15L);
        when(taskExecutionRepository.findById(15L)).thenReturn(Optional.of(execution));

        taskExecutionProgressService.persistExternalTurnId(15L, "   ");

        assertNull(execution.getExternalTurnId());
        verify(taskExecutionRepository).save(execution);
    }

    @Test
    void persistExternalThreadIdThrowsWhenExecutionDoesNotExist() {
        when(taskExecutionRepository.findById(15L)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> taskExecutionProgressService.persistExternalThreadId(15L, "x"));
        verify(taskExecutionRepository, never()).save(any());
    }
}
