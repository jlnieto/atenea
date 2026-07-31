package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunProgressCategory;
import com.atenea.persistence.worksession.AgentRunProgressEventEntity;
import com.atenea.persistence.worksession.AgentRunProgressEventRepository;
import com.atenea.persistence.worksession.AgentRunProgressNextAction;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkloadClass;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AgentRunProgressPersistenceTest {

    private static final AtomicLong FIXTURE_SEQUENCE = new AtomicLong();

    @Autowired
    private AgentRunProgressService progressService;

    @Autowired
    private AgentRunProgressEventRepository progressEventRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkSessionRepository workSessionRepository;

    @Autowired
    private SessionTurnRepository sessionTurnRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void coalescesBeforeAllocationAndRetainsNewestTwoHundredWithoutReuse() {
        AgentRunEntity run = createRun(AgentRunStatus.RUNNING);

        AgentRunProgressAppendResult first = progressService.append(
                run.getId(), AgentRunProgressCategory.ACCEPTED);
        AgentRunProgressAppendResult duplicate = progressService.append(
                run.getId(), AgentRunProgressCategory.ACCEPTED);

        assertTrue(first.inserted());
        assertFalse(duplicate.inserted());
        assertEquals(1, duplicate.event().getSequence());

        for (int index = 0; index < 200; index++) {
            AgentRunProgressCategory category = index % 2 == 0
                    ? AgentRunProgressCategory.QUEUED
                    : AgentRunProgressCategory.CHECKING;
            progressService.append(run.getId(), category);
        }

        AgentRunEntity persisted = agentRunRepository.findById(run.getId()).orElseThrow();
        List<AgentRunProgressEventEntity> retained =
                progressEventRepository.findByAgentRunIdOrderBySequenceAsc(run.getId());
        assertEquals(200, retained.size());
        assertEquals(2, retained.getFirst().getSequence());
        assertEquals(201, retained.getLast().getSequence());
        assertEquals(202, persisted.getProgressNextSequence());
        assertEquals(2, persisted.getProgressRetainedFloor());
        assertEquals(201, persisted.getProgressLatestSequence());
        assertEquals(AgentRunProgressCategory.CHECKING, persisted.getProgressCurrentState());
        assertEquals(AgentRunProgressNextAction.CANCEL, persisted.getProgressRequiredNextAction());
        assertNull(persisted.getProgressTerminalCategory());
    }

    @Test
    void replayBelowFloorReturnsProjectionAndRetainedGap() {
        AgentRunEntity run = createRun(AgentRunStatus.RUNNING);
        for (int index = 0; index < 202; index++) {
            progressService.append(
                    run.getId(),
                    index % 2 == 0
                            ? AgentRunProgressCategory.INSPECTING_PROJECT
                            : AgentRunProgressCategory.CHECKING);
        }

        AgentRunProgressReplay replay = progressService.replay(run.getId(), 0);

        assertTrue(replay.cursorWasBelowRetainedFloor());
        assertEquals(3, replay.retainedFloor());
        assertEquals(202, replay.latestEvent().sequence());
        assertEquals(200, replay.events().size());
        assertEquals(3, replay.events().getFirst().getSequence());
        assertEquals(202, replay.events().getLast().getSequence());
        assertEquals(AgentRunProgressCategory.CHECKING, replay.currentState());
        assertEquals(AgentRunProgressCategory.CHECKING, replay.latestEvent().category());
        assertEquals("Comprobando el resultado", replay.latestEvent().operatorMessage());
    }

    @Test
    void storesOnlyClosedGenericMessagesAndConsistentTerminalProjection() {
        AgentRunEntity run = createRun(AgentRunStatus.FAILED);

        AgentRunProgressAppendResult result = progressService.append(
                run.getId(), AgentRunProgressCategory.FAILED);
        AgentRunEntity persisted = agentRunRepository.findById(run.getId()).orElseThrow();

        assertEquals("La tarea necesita atención", result.event().getOperatorMessage());
        assertEquals(AgentRunProgressCategory.FAILED, persisted.getProgressTerminalCategory());
        assertEquals(AgentRunProgressNextAction.RETRY, persisted.getProgressRequiredNextAction());
        assertTrue(persisted.getProgressElapsedMillis() >= 0);
    }

    @Test
    void rejectsTerminalCategoryThatDoesNotMatchRunOutcome() {
        AgentRunEntity run = createRun(AgentRunStatus.RUNNING);

        assertThrows(
                AgentRunTransitionNotAllowedException.class,
                () -> progressService.append(run.getId(), AgentRunProgressCategory.COMPLETED));
        assertEquals(0, progressEventRepository.countByAgentRunId(run.getId()));
    }

    @Test
    void returnsEmptyProjectionForLegacyRunAndRejectsNegativeCursor() {
        AgentRunEntity run = createRun(AgentRunStatus.RUNNING);

        AgentRunProgressReplay replay = progressService.replay(run.getId(), 0);

        assertEquals(1, replay.retainedFloor());
        assertFalse(replay.cursorWasBelowRetainedFloor());
        assertNull(replay.latestEvent());
        assertTrue(replay.events().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> progressService.replay(run.getId(), -1));
    }

    @Test
    void databaseRejectsFreeFormOrSecretBearingProgressMessage() {
        AgentRunEntity run = createRun(AgentRunStatus.RUNNING);

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO agent_run_progress_event (
                    agent_run_id, sequence, category, operator_message,
                    occurred_at, created_at
                ) VALUES (?, 1, 'CHECKING', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, run.getId(), "token=synthetic-value"));
        assertEquals(0, progressEventRepository.countByAgentRunId(run.getId()));
    }

    private AgentRunEntity createRun(AgentRunStatus status) {
        long fixture = FIXTURE_SEQUENCE.incrementAndGet();
        Instant startedAt = Instant.parse("2026-07-31T10:00:00Z");

        ProjectEntity project = new ProjectEntity();
        project.setName("progress-project-" + fixture);
        project.setRepoPath("/workspace/repos/internal/progress-" + fixture);
        project.setDefaultBaseBranch("main");
        project.setCreatedAt(startedAt);
        project.setUpdatedAt(startedAt);
        project = projectRepository.save(project);

        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Progress persistence " + fixture);
        session.setBaseBranch("main");
        session.setExecutionTarget(ExecutionTarget.LOCAL);
        session.setWorkspaceIdentity("local:progress:" + fixture);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(startedAt);
        session.setLastActivityAt(startedAt);
        session.setCreatedAt(startedAt);
        session.setUpdatedAt(startedAt);
        session = workSessionRepository.save(session);

        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setSession(session);
        turn.setActor(SessionTurnActor.OPERATOR);
        turn.setMessageText("Synthetic progress fixture");
        turn.setCreatedAt(startedAt);
        turn = sessionTurnRepository.save(turn);

        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session);
        run.setOriginTurn(turn);
        run.setStatus(status);
        run.setTargetRepoPath(project.getRepoPath());
        run.setExecutionTarget(ExecutionTarget.LOCAL);
        run.setWorkspaceIdentity(session.getWorkspaceIdentity());
        run.setWorkloadClass(WorkloadClass.NORMAL);
        run.setStartedAt(startedAt);
        run.setCreatedAt(startedAt);
        if (status.isTerminal()) {
            run.setFinishedAt(startedAt.plusSeconds(30));
        }
        return agentRunRepository.saveAndFlush(run);
    }
}
