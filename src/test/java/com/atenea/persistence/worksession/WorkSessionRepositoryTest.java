package com.atenea.persistence.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkSessionRepositoryTest {

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private SessionTurnRepository sessionTurnRepository;

    @Mock
    private ProjectRepository projectRepository;

    @InjectMocks
    private WorkSessionRepositoryUsageHarness harness;

    @Test
    void detectsOpenSessionByProject() {
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(true);

        assertTrue(harness.hasOpenSession(7L));
    }

    @Test
    void detectsRunningRunBySession() {
        when(agentRunRepository.existsBySessionIdAndStatus(12L, AgentRunStatus.RUNNING)).thenReturn(true);

        assertTrue(harness.hasRunningRun(12L));
    }

    @Test
    void loadsSessionWithProjectGraph() {
        WorkSessionEntity session = buildSession(12L, 7L);
        when(workSessionRepository.findWithProjectById(12L)).thenReturn(Optional.of(session));

        WorkSessionEntity loaded = harness.loadSession(12L);

        assertEquals(7L, loaded.getProject().getId());
        assertEquals(WorkSessionStatus.OPEN, loaded.getStatus());
    }

    @Test
    void returnsFalseWhenProjectHasNoOpenSession() {
        when(workSessionRepository.existsByProjectIdAndStatus(7L, WorkSessionStatus.OPEN)).thenReturn(false);

        assertFalse(harness.hasOpenSession(7L));
    }

    private static WorkSessionEntity buildSession(Long sessionId, Long projectId) {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setName("Atenea");
        project.setRepoPath("/workspace/repos/internal/atenea");
        project.setCreatedAt(Instant.parse("2026-03-25T10:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-25T10:00:00Z"));

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(sessionId);
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Inspect project status");
        session.setBaseBranch("main");
        session.setWorkspaceBranch(null);
        session.setExternalThreadId(null);
        session.setOpenedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setClosedAt(null);
        session.setCreatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        return session;
    }

    static final class WorkSessionRepositoryUsageHarness {

        private final WorkSessionRepository workSessionRepository;
        private final AgentRunRepository agentRunRepository;

        WorkSessionRepositoryUsageHarness(
                WorkSessionRepository workSessionRepository,
                AgentRunRepository agentRunRepository,
                SessionTurnRepository sessionTurnRepository,
                ProjectRepository projectRepository
        ) {
            this.workSessionRepository = workSessionRepository;
            this.agentRunRepository = agentRunRepository;
        }

        boolean hasOpenSession(Long projectId) {
            return workSessionRepository.existsByProjectIdAndStatus(projectId, WorkSessionStatus.OPEN);
        }

        boolean hasRunningRun(Long sessionId) {
            return agentRunRepository.existsBySessionIdAndStatus(sessionId, AgentRunStatus.RUNNING);
        }

        WorkSessionEntity loadSession(Long sessionId) {
            return workSessionRepository.findWithProjectById(sessionId)
                    .orElseThrow();
        }
    }
}
