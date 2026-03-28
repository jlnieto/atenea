package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.git.GitRepositoryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionTurnServiceTest {

    @Mock
    private WorkSessionRepository workSessionRepository;

    @Mock
    private SessionTurnRepository sessionTurnRepository;

    @Mock
    private GitRepositoryService gitRepositoryService;

    @Mock
    private AgentRunRepository agentRunRepository;

    @Mock
    private AgentRunService agentRunService;

    @Mock
    private AgentRunReconciliationService agentRunReconciliationService;

    @Mock
    private SessionCodexOrchestrator sessionCodexOrchestrator;

    @Mock
    private SessionTurnCompletionService sessionTurnCompletionService;

    private SessionTurnService sessionTurnService;

    @BeforeEach
    void setUp() {
        sessionTurnService = new SessionTurnService(
                workSessionRepository,
                sessionTurnRepository,
                new WorkspaceRepositoryPathValidator("/workspace/repos"),
                gitRepositoryService,
                agentRunRepository,
                agentRunService,
                new AgentRunProgressService(),
                agentRunReconciliationService,
                sessionCodexOrchestrator,
                sessionTurnCompletionService
        );
    }

    @Test
    void getTurnsReturnsVisibleTurnsInChronologicalOrder() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);
        when(sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(12L)).thenReturn(List.of(
                buildTurn(101L, SessionTurnActor.OPERATOR, "First question", false, "2026-03-25T10:05:00Z"),
                buildTurn(102L, SessionTurnActor.CODEX, "First answer", false, "2026-03-25T10:06:00Z")));

        List<SessionTurnResponse> turns = sessionTurnService.getTurns(12L);

        assertEquals(2, turns.size());
        assertEquals(101L, turns.get(0).id());
        assertEquals("First question", turns.get(0).messageText());
        assertEquals(102L, turns.get(1).id());
        assertEquals("First answer", turns.get(1).messageText());
    }

    @Test
    void getTurnsReturnsLatestWindowWhenLimitIsProvided() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);
        when(sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(12L)).thenReturn(List.of(
                buildTurn(101L, SessionTurnActor.OPERATOR, "Turn 1", false, "2026-03-25T10:01:00Z"),
                buildTurn(102L, SessionTurnActor.CODEX, "Turn 2", false, "2026-03-25T10:02:00Z"),
                buildTurn(103L, SessionTurnActor.OPERATOR, "Turn 3", false, "2026-03-25T10:03:00Z"),
                buildTurn(104L, SessionTurnActor.CODEX, "Turn 4", false, "2026-03-25T10:04:00Z")));

        List<SessionTurnResponse> turns = sessionTurnService.getTurns(12L, null, 2);

        assertEquals(List.of(103L, 104L), turns.stream().map(SessionTurnResponse::id).toList());
    }

    @Test
    void getTurnsReturnsOlderWindowBeforeTurnId() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);
        when(sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(12L)).thenReturn(List.of(
                buildTurn(101L, SessionTurnActor.OPERATOR, "Turn 1", false, "2026-03-25T10:01:00Z"),
                buildTurn(102L, SessionTurnActor.CODEX, "Turn 2", false, "2026-03-25T10:02:00Z"),
                buildTurn(103L, SessionTurnActor.OPERATOR, "Turn 3", false, "2026-03-25T10:03:00Z"),
                buildTurn(104L, SessionTurnActor.CODEX, "Turn 4", false, "2026-03-25T10:04:00Z"),
                buildTurn(105L, SessionTurnActor.OPERATOR, "Turn 5", false, "2026-03-25T10:05:00Z")));

        List<SessionTurnResponse> turns = sessionTurnService.getTurns(12L, 105L, 2);

        assertEquals(List.of(103L, 104L), turns.stream().map(SessionTurnResponse::id).toList());
    }

    @Test
    void getTurnsExcludesInternalTurnsFromPublicHistory() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);
        when(sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(12L)).thenReturn(List.of(
                buildTurn(101L, SessionTurnActor.ATENEA, "Internal AgentRun origin", true, "2026-03-25T10:05:00Z"),
                buildTurn(102L, SessionTurnActor.OPERATOR, "Visible operator turn", false, "2026-03-25T10:06:00Z"),
                buildTurn(103L, SessionTurnActor.CODEX, "Visible codex turn", false, "2026-03-25T10:07:00Z")));

        List<SessionTurnResponse> turns = sessionTurnService.getTurns(12L);

        assertEquals(2, turns.size());
        assertEquals(List.of(102L, 103L), turns.stream().map(SessionTurnResponse::id).toList());
    }

    @Test
    void getTurnsReturnsEmptyWindowWhenNoVisibleTurnsExistBeforeCursor() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);
        when(sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(12L)).thenReturn(List.of(
                buildTurn(101L, SessionTurnActor.OPERATOR, "Turn 1", false, "2026-03-25T10:01:00Z"),
                buildTurn(102L, SessionTurnActor.CODEX, "Turn 2", false, "2026-03-25T10:02:00Z")));

        List<SessionTurnResponse> turns = sessionTurnService.getTurns(12L, 101L, 20);

        assertEquals(List.of(), turns);
    }

    @Test
    void getTurnsThrowsWhenSessionDoesNotExist() {
        when(workSessionRepository.existsById(12L)).thenReturn(false);

        assertThrows(WorkSessionNotFoundException.class, () -> sessionTurnService.getTurns(12L));
    }

    @Test
    void getTurnsThrowsWhenLimitIsInvalid() {
        when(workSessionRepository.existsById(12L)).thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> sessionTurnService.getTurns(12L, null, 0));

        assertEquals("Turn limit must be greater than zero", exception.getMessage());
    }

    private static SessionTurnEntity buildTurn(
            Long turnId,
            SessionTurnActor actor,
            String messageText,
            boolean internal,
            String createdAt
    ) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("Atenea");
        project.setRepoPath("/workspace/repos/internal/atenea");
        project.setCreatedAt(Instant.parse("2026-03-25T10:00:00Z"));
        project.setUpdatedAt(Instant.parse("2026-03-25T10:00:00Z"));

        WorkSessionEntity session = new WorkSessionEntity();
        session.setId(12L);
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Inspect project status");
        session.setBaseBranch("main");
        session.setOpenedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setLastActivityAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setCreatedAt(Instant.parse("2026-03-25T10:05:00Z"));
        session.setUpdatedAt(Instant.parse("2026-03-25T10:05:00Z"));

        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setId(turnId);
        turn.setSession(session);
        turn.setActor(actor);
        turn.setMessageText(messageText);
        turn.setInternal(internal);
        turn.setCreatedAt(Instant.parse(createdAt));
        return turn;
    }
}
