package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.SessionDeliverableRepository;
import com.atenea.persistence.worksession.SessionDeliverableType;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionDeliverableGenerationServiceTest {

    @Mock
    private SessionDeliverableRepository sessionDeliverableRepository;

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private SessionTurnRepository sessionTurnRepository;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;

    @Mock
    private SessionDeliverableCodexOrchestrator sessionDeliverableCodexOrchestrator;

    private SessionDeliverableGenerationService sessionDeliverableGenerationService;

    @BeforeEach
    void setUp() {
        sessionDeliverableGenerationService = new SessionDeliverableGenerationService(
                sessionDeliverableRepository,
                workSessionRepository,
                sessionTurnRepository,
                agentRunRepository,
                workspaceRepositoryPathValidator,
                new ObjectMapper(),
                sessionDeliverableCodexOrchestrator
        );
    }

    @Test
    void generateDeliverableThrowsWhenSessionIsClosed() {
        WorkSessionEntity session = buildSession(12L, WorkSessionStatus.CLOSED);

        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));

        assertThrows(
                WorkSessionNotOpenException.class,
                () -> sessionDeliverableGenerationService.generateDeliverable(12L, SessionDeliverableType.WORK_TICKET)
        );

        verifyNoInteractions(
                workspaceRepositoryPathValidator,
                sessionDeliverableRepository,
                sessionTurnRepository,
                agentRunRepository,
                sessionDeliverableCodexOrchestrator
        );
    }

    private static WorkSessionEntity buildSession(Long sessionId, WorkSessionStatus status) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("Atenea");
        project.setRepoPath("/workspace/repos/internal/atenea");
        project.setCreatedAt(Instant.parse("2026-03-25T10:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-25T10:00:00Z"));

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(sessionId);
        session.setProject(project);
        session.setStatus(status);
        session.setTitle("Review mobile state");
        session.setBaseBranch("main");
        session.setWorkspaceBranch("atenea/session-12");
        session.setOpenedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        return session;
    }
}
