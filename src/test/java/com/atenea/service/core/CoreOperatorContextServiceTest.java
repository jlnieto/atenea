package com.atenea.service.core;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atenea.api.core.CoreRequestContext;
import com.atenea.api.core.CreateCoreCommandRequest;
import com.atenea.persistence.core.CoreOperatorContextEntity;
import com.atenea.persistence.core.CoreOperatorContextRepository;
import com.atenea.persistence.core.CoreChannel;
import com.atenea.persistence.core.CoreDomain;
import com.atenea.persistence.core.CoreResultType;
import com.atenea.persistence.core.CoreRiskLevel;
import com.atenea.persistence.core.CoreTargetType;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreOperatorContextServiceTest {

    @Mock
    private CoreOperatorContextRepository coreOperatorContextRepository;

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Test
    void applyActiveContextDoesNotInheritProjectOrSessionForGlobalScope() {
        CoreOperatorContextEntity activeContext = new CoreOperatorContextEntity();
        activeContext.setOperatorKey("operator@atenea.local");
        activeContext.setActiveProjectId(7L);
        activeContext.setActiveWorkSessionId(12L);
        when(coreOperatorContextRepository.findById("operator@atenea.local"))
                .thenReturn(Optional.of(activeContext));
        CoreOperatorContextService service = new CoreOperatorContextService(
                coreOperatorContextRepository,
                workSessionRepository,
                projectRepository);

        CreateCoreCommandRequest result = service.applyActiveContext(new CreateCoreCommandRequest(
                "comprueba apache en el dedicado",
                CoreChannel.TEXT,
                new CoreRequestContext(null, null, "operator@atenea.local", "GLOBAL"),
                null));

        assertNull(result.context().projectId());
        assertNull(result.context().workSessionId());
        assertEquals("GLOBAL", result.context().scope());
        verify(coreOperatorContextRepository).findById("operator@atenea.local");
        verifyNoInteractions(workSessionRepository, projectRepository);
    }

    @Test
    void activateProjectSelectsOpenWorkSessionWhenOneExists() {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        when(projectRepository.existsById(7L)).thenReturn(true);
        when(workSessionRepository.findByProjectIdAndStatus(7L, WorkSessionStatus.OPEN))
                .thenReturn(Optional.of(session));
        when(coreOperatorContextRepository.findById("default")).thenReturn(Optional.empty());
        CoreOperatorContextService service = new CoreOperatorContextService(
                coreOperatorContextRepository,
                workSessionRepository,
                projectRepository);

        Long activeWorkSessionId = service.activateProject("default", 7L, 101L);

        assertEquals(12L, activeWorkSessionId);
        ArgumentCaptor<CoreOperatorContextEntity> captor = ArgumentCaptor.forClass(CoreOperatorContextEntity.class);
        verify(coreOperatorContextRepository).save(captor.capture());
        assertEquals(7L, captor.getValue().getActiveProjectId());
        assertEquals(12L, captor.getValue().getActiveWorkSessionId());
        assertEquals(101L, captor.getValue().getActiveCommandId());
    }

    @Test
    void activateProjectLeavesWorkSessionEmptyWhenNoSessionIsOpen() {
        when(projectRepository.existsById(7L)).thenReturn(true);
        when(workSessionRepository.findByProjectIdAndStatus(7L, WorkSessionStatus.OPEN))
                .thenReturn(Optional.empty());
        when(coreOperatorContextRepository.findById("default")).thenReturn(Optional.empty());
        CoreOperatorContextService service = new CoreOperatorContextService(
                coreOperatorContextRepository,
                workSessionRepository,
                projectRepository);

        Long activeWorkSessionId = service.activateProject("default", 7L, 101L);

        assertNull(activeWorkSessionId);
        ArgumentCaptor<CoreOperatorContextEntity> captor = ArgumentCaptor.forClass(CoreOperatorContextEntity.class);
        verify(coreOperatorContextRepository).save(captor.capture());
        assertEquals(7L, captor.getValue().getActiveProjectId());
        assertNull(captor.getValue().getActiveWorkSessionId());
        assertEquals(101L, captor.getValue().getActiveCommandId());
    }

    @Test
    void closeWorkSessionClearsActiveSessionButKeepsProjectContext() {
        CoreOperatorContextEntity activeContext = new CoreOperatorContextEntity();
        activeContext.setOperatorKey("default");
        activeContext.setActiveProjectId(7L);
        activeContext.setActiveWorkSessionId(12L);
        when(coreOperatorContextRepository.findById("default")).thenReturn(Optional.of(activeContext));
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setProject(project);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));
        CoreOperatorContextService service = new CoreOperatorContextService(
                coreOperatorContextRepository,
                workSessionRepository,
                projectRepository);

        service.updateAfterSuccess(
                "default",
                101L,
                new CoreIntentEnvelope(
                        "close",
                        CoreDomain.DEVELOPMENT,
                        "close_work_session",
                        Map.of("workSessionId", 12L),
                        BigDecimal.ONE,
                        CoreRiskLevel.SAFE_WRITE,
                        false),
                new CoreCommandExecutionResult(
                        CoreResultType.WORK_SESSION,
                        CoreTargetType.WORK_SESSION,
                        12L,
                        null,
                        null,
                        null,
                        null));

        ArgumentCaptor<CoreOperatorContextEntity> captor = ArgumentCaptor.forClass(CoreOperatorContextEntity.class);
        verify(coreOperatorContextRepository).save(captor.capture());
        assertEquals(7L, captor.getValue().getActiveProjectId());
        assertNull(captor.getValue().getActiveWorkSessionId());
        assertEquals(101L, captor.getValue().getActiveCommandId());
    }
}
