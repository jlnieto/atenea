package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryAction;
import com.atenea.persistence.worksession.AgentRunRecoveryOperationEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryOperationRepository;
import com.atenea.persistence.worksession.AgentRunRecoveryOutcome;
import com.atenea.persistence.worksession.AgentRunRecoveryState;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AgentRunRecoveryOperationPersistenceTest {

    private static final AtomicLong FIXTURE_SEQUENCE = new AtomicLong();

    @Autowired
    private AgentRunRecoveryOperationService service;
    @Autowired
    private AgentRunRecoveryOperationRepository operationRepository;
    @Autowired
    private AgentRunRepository agentRunRepository;
    @Autowired
    private OperatorRepository operatorRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private WorkSessionRepository workSessionRepository;
    @Autowired
    private SessionTurnRepository sessionTurnRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanPersistence() {
        operationRepository.deleteAll();
        jdbcTemplate.update("UPDATE agent_run SET retry_of_run_id = NULL WHERE retry_of_run_id IS NOT NULL");
        agentRunRepository.deleteAll();
        sessionTurnRepository.deleteAll();
        workSessionRepository.deleteAll();
        projectRepository.deleteAll();
        operatorRepository.deleteAll();
    }

    @AfterEach
    void cleanPersistenceAfterTest() {
        cleanPersistence();
    }

    @Test
    void repeatsExactRequestIdempotentlyAndRejectsConflictingReuse() {
        Fixture fixture = fixture(CodexOperationsRole.ROUTINE_OPERATOR, AgentRunStatus.RUNNING);
        UUID key = UUID.randomUUID();

        AgentRunRecoveryRequestResult first = service.request(
                fixture.operator().getId(), fixture.session().getId(), fixture.run().getId(),
                AgentRunRecoveryAction.CANCEL, key);
        AgentRunRecoveryRequestResult repeated = service.request(
                fixture.operator().getId(), fixture.session().getId(), fixture.run().getId(),
                AgentRunRecoveryAction.CANCEL, key);

        assertTrue(first.created());
        assertFalse(repeated.created());
        assertEquals(first.operation().getOperationId(), repeated.operation().getOperationId());
        assertEquals(1, operationRepository.count());
        assertThrows(AgentRunRecoveryConflictException.class, () -> service.request(
                fixture.operator().getId(), fixture.session().getId(), fixture.run().getId(),
                AgentRunRecoveryAction.DIAGNOSTIC, key));
        assertEquals(1, operationRepository.count());
    }

    @Test
    void persistsRoutinePrivilegedDenialWithActionableClosedOutcome() {
        Fixture fixture = fixture(CodexOperationsRole.ROUTINE_OPERATOR, AgentRunStatus.RUNNING);

        AgentRunRecoveryOperationEntity operation = service.request(
                fixture.operator().getId(), fixture.session().getId(), fixture.run().getId(),
                AgentRunRecoveryAction.RESTART_EXECUTION_SERVICE, UUID.randomUUID()).operation();

        assertEquals(AgentRunRecoveryState.REJECTED, operation.getState());
        assertEquals(AgentRunRecoveryOutcome.ROLE_REQUIRED, operation.getOutcomeCode());
        assertEquals("Se necesita un operador privilegiado", operation.getOutcomeSummary());
        assertTrue(operation.getRequiredNextAction().name().contains("PRIVILEGED"));
    }

    @Test
    void permitsPrivilegedMediatedRestartAndTerminalRepetitionOnly() {
        Fixture fixture = fixture(CodexOperationsRole.PRIVILEGED_OPERATOR, AgentRunStatus.RUNNING);
        AgentRunRecoveryOperationEntity requested = service.request(
                fixture.operator().getId(), fixture.session().getId(), fixture.run().getId(),
                AgentRunRecoveryAction.RESTART_PROJECT_APP_SERVER, UUID.randomUUID()).operation();

        service.start(requested.getOperationId());
        AgentRunRecoveryOperationEntity completed = service.complete(
                requested.getOperationId(), AgentRunRecoveryOutcome.SERVICE_RESTARTED, null);
        AgentRunRecoveryOperationEntity repeated = service.complete(
                requested.getOperationId(), AgentRunRecoveryOutcome.SERVICE_RESTARTED, null);

        assertEquals(AgentRunRecoveryState.SUCCEEDED, completed.getState());
        assertEquals(completed.getOperationId(), repeated.getOperationId());
        assertThrows(AgentRunRecoveryConflictException.class, () -> service.complete(
                requested.getOperationId(), AgentRunRecoveryOutcome.POLICY_BLOCKED, null));
    }

    @Test
    void persistsExactRetryLineageAndKeepsOriginalImmutable() {
        Fixture fixture = fixture(CodexOperationsRole.ROUTINE_OPERATOR, AgentRunStatus.FAILED);
        AgentRunRecoveryOperationEntity requested = service.request(
                fixture.operator().getId(), fixture.session().getId(), fixture.run().getId(),
                AgentRunRecoveryAction.RETRY, UUID.randomUUID()).operation();
        AgentRunEntity retry = createRetry(fixture);

        AgentRunRecoveryOperationEntity completed = service.complete(
                requested.getOperationId(), AgentRunRecoveryOutcome.RETRY_CREATED, retry.getId());

        AgentRunEntity persistedRetry = agentRunRepository.findById(retry.getId()).orElseThrow();
        AgentRunEntity original = agentRunRepository.findById(fixture.run().getId()).orElseThrow();
        assertEquals(fixture.run().getId(), persistedRetry.getRetryOfRun().getId());
        assertEquals(AgentRunStatus.FAILED, original.getStatus());
        assertEquals(retry.getId(), completed.getResultAgentRun().getId());
        assertEquals(AgentRunRecoveryOutcome.RETRY_CREATED, completed.getOutcomeCode());
    }

    @Test
    void rejectsForeignSessionAndInvalidActionOutcomeWithoutChangingRows() {
        Fixture fixture = fixture(CodexOperationsRole.ROUTINE_OPERATOR, AgentRunStatus.RUNNING);
        Fixture foreign = fixture(CodexOperationsRole.ROUTINE_OPERATOR, AgentRunStatus.RUNNING);

        assertThrows(AgentRunRecoveryAuthorizationException.class, () -> service.request(
                fixture.operator().getId(), foreign.session().getId(), fixture.run().getId(),
                AgentRunRecoveryAction.CANCEL, UUID.randomUUID()));
        assertEquals(0, operationRepository.count());

        AgentRunRecoveryOperationEntity requested = service.request(
                fixture.operator().getId(), fixture.session().getId(), fixture.run().getId(),
                AgentRunRecoveryAction.CANCEL, UUID.randomUUID()).operation();
        assertThrows(AgentRunRecoveryConflictException.class, () -> service.complete(
                requested.getOperationId(), AgentRunRecoveryOutcome.DIAGNOSTIC_READY, null));
        assertEquals(AgentRunRecoveryState.REQUESTED,
                operationRepository.findById(requested.getId()).orElseThrow().getState());
    }

    @Test
    void operatorRoleDefaultsToRoutineAndFingerprintBindsRoleSnapshot() {
        Fixture fixture = fixture(null, AgentRunStatus.RUNNING);
        AgentRunRecoveryOperationEntity first = service.request(
                fixture.operator().getId(), fixture.session().getId(), fixture.run().getId(),
                AgentRunRecoveryAction.DIAGNOSTIC, UUID.randomUUID()).operation();

        assertEquals(CodexOperationsRole.ROUTINE_OPERATOR, fixture.operator().getCodexOperationsRole());
        assertEquals(CodexOperationsRole.ROUTINE_OPERATOR, first.getRequestedRole());
        assertEquals(64, first.getRequestFingerprintSha256().length());
        assertNotEquals("0".repeat(64), first.getRequestFingerprintSha256());
    }

    private Fixture fixture(CodexOperationsRole role, AgentRunStatus status) {
        long value = FIXTURE_SEQUENCE.incrementAndGet();
        Instant now = Instant.parse("2026-07-31T10:00:00Z");

        OperatorEntity operator = new OperatorEntity();
        operator.setEmail("recovery-" + value + "@atenea.test");
        operator.setDisplayName("Recovery operator " + value);
        operator.setPasswordHash("synthetic-hash");
        operator.setActive(true);
        if (role != null) {
            operator.setCodexOperationsRole(role);
        }
        operator.setCreatedAt(now);
        operator.setUpdatedAt(now);
        operator = operatorRepository.save(operator);

        ProjectEntity project = new ProjectEntity();
        project.setName("recovery-project-" + value);
        project.setRepoPath("/workspace/repos/internal/recovery-" + value);
        project.setDefaultBaseBranch("main");
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project = projectRepository.save(project);

        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Recovery persistence " + value);
        session.setBaseBranch("main");
        session.setExecutionTarget(ExecutionTarget.LOCAL);
        session.setWorkspaceIdentity("local:recovery:" + value);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now);
        session.setLastActivityAt(now);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session = workSessionRepository.save(session);

        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setSession(session);
        turn.setActor(SessionTurnActor.OPERATOR);
        turn.setMessageText("Synthetic recovery fixture");
        turn.setCreatedAt(now);
        turn = sessionTurnRepository.save(turn);

        AgentRunEntity run = run(session, turn, status, now);
        return new Fixture(operator, session, turn, agentRunRepository.saveAndFlush(run));
    }

    private AgentRunEntity createRetry(Fixture fixture) {
        AgentRunEntity retry = run(
                fixture.session(), fixture.turn(), AgentRunStatus.RUNNING,
                Instant.parse("2026-07-31T10:01:00Z"));
        retry.setRetryOfRun(fixture.run());
        return agentRunRepository.saveAndFlush(retry);
    }

    private static AgentRunEntity run(
            WorkSessionEntity session,
            SessionTurnEntity turn,
            AgentRunStatus status,
            Instant now) {
        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session);
        run.setOriginTurn(turn);
        run.setStatus(status);
        run.setTargetRepoPath(session.getProject().getRepoPath());
        run.setExecutionTarget(ExecutionTarget.LOCAL);
        run.setWorkspaceIdentity(session.getWorkspaceIdentity());
        run.setWorkloadClass(WorkloadClass.NORMAL);
        run.setStartedAt(now);
        run.setCreatedAt(now);
        if (status.isTerminal()) {
            run.setFinishedAt(now.plusSeconds(30));
        }
        return run;
    }

    private record Fixture(
            OperatorEntity operator,
            WorkSessionEntity session,
            SessionTurnEntity turn,
            AgentRunEntity run) {
    }
}
