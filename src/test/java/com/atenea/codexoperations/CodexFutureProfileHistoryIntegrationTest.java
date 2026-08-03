package com.atenea.codexoperations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.CodexReasoningEffort;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkloadClass;
import com.atenea.service.worksession.SessionTurnService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "atenea.codex-session-operations.profiles-enabled=true")
@Transactional
class CodexFutureProfileHistoryIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkSessionRepository sessionRepository;
    @Autowired private SessionTurnRepository turnRepository;
    @Autowired private AgentRunRepository runRepository;
    @Autowired private CodexExecutionProfileSnapshotService snapshotService;
    @Autowired private SessionTurnService turnService;

    @Test
    void changedSettingsApplyOnlyToFutureRunsAndRemainOnHistoricalTurns() {
        Instant now = Instant.parse("2026-07-31T15:00:00Z");
        String workerId = "history-profile-worker";
        insertCatalog(workerId);

        ProjectEntity project = new ProjectEntity();
        project.setName("profile-history-project");
        project.setRepoPath("/workspace/profile-history");
        project.setDefaultBaseBranch("main");
        project.setDefaultCodexModelId("gpt-5.6-sol");
        project.setDefaultCodexReasoningEffort(CodexReasoningEffort.MEDIUM);
        project.setCreatedAt(now); project.setUpdatedAt(now);
        project = projectRepository.save(project);

        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project); session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Profile history"); session.setBaseBranch("main");
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(workerId);
        session.setRemoteSessionId(UUID.fromString("00000000-0000-0000-0000-000000000045"));
        session.setRemoteWorkloadKind("synthetic-routing-v1");
        session.setWorkspaceIdentity("profile-history-workspace");
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now); session.setLastActivityAt(now);
        session.setCreatedAt(now); session.setUpdatedAt(now);
        session = sessionRepository.save(session);

        SessionTurnEntity firstTurn = turn(session, "First", now);
        AgentRunEntity firstRun = run(session, firstTurn, workerId, now);
        snapshotService.applyCurrentProfile(firstRun);
        firstRun = runRepository.saveAndFlush(firstRun);

        session.setDefaultCodexReasoningEffort(CodexReasoningEffort.HIGH);
        session.setUpdatedAt(now.plusSeconds(1));
        sessionRepository.saveAndFlush(session);

        SessionTurnEntity secondTurn = turn(session, "Second", now.plusSeconds(2));
        AgentRunEntity secondRun = run(session, secondTurn, workerId, now.plusSeconds(2));
        snapshotService.applyCurrentProfile(secondRun);
        runRepository.saveAndFlush(secondRun);

        AgentRunEntity unchangedFirst = runRepository.findById(firstRun.getId()).orElseThrow();
        assertEquals(CodexReasoningEffort.MEDIUM, unchangedFirst.getCodexReasoningEffort());
        assertEquals(CodexReasoningEffort.HIGH, secondRun.getCodexReasoningEffort());

        List<SessionTurnResponse> history = turnService.getTurns(session.getId());
        assertEquals("medium", history.get(0).executionProfile().reasoningEffort());
        assertEquals("PROJECT", history.get(0).executionProfile().effortSource());
        assertEquals("high", history.get(1).executionProfile().reasoningEffort());
        assertEquals("WORK_SESSION", history.get(1).executionProfile().effortSource());
        assertEquals("0.145.0", history.get(0).executionProfile().codexVersion());
    }

    private SessionTurnEntity turn(WorkSessionEntity session, String message, Instant at) {
        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setSession(session); turn.setActor(SessionTurnActor.OPERATOR);
        turn.setMessageText(message); turn.setCreatedAt(at);
        return turnRepository.save(turn);
    }

    private AgentRunEntity run(WorkSessionEntity session, SessionTurnEntity turn, String workerId, Instant at) {
        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session); run.setOriginTurn(turn); run.setStatus(AgentRunStatus.SUCCEEDED);
        run.setTargetRepoPath(session.getProject().getRepoPath()); run.setExecutionTarget(ExecutionTarget.REMOTE);
        run.setSelectedWorkerId(workerId); run.setWorkspaceIdentity(session.getWorkspaceIdentity());
        run.setRemoteSessionId(session.getRemoteSessionId()); run.setWorkloadKind("synthetic-routing-v1");
        run.setDispatchId(UUID.randomUUID());
        run.setWorkloadClass(WorkloadClass.NORMAL); run.setStartedAt(at); run.setFinishedAt(at);
        run.setCreatedAt(at);
        return run;
    }

    private void insertCatalog(String workerId) {
        String revision = "c".repeat(64);
        jdbcTemplate.update("""
                INSERT INTO worker_node (id, protocol_version, endpoint, enabled, healthy,
                    normal_capacity, heavy_capacity, capabilities, created_at, updated_at)
                VALUES (?, 'agent-run-worker/v1', 'https://worker.invalid', false, false,
                    4, 2, 'project-codex-v2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workerId);
        jdbcTemplate.update("""
                INSERT INTO worker_codex_catalog (worker_id, catalog_revision, schema_version,
                    codex_version, generated_at, observed_at)
                VALUES (?, ?, 'codex-model-catalog-v1', '0.145.0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workerId, revision);
        jdbcTemplate.update("""
                INSERT INTO worker_codex_model (worker_id, catalog_revision, model_id,
                    display_name, default_effort, availability, position)
                VALUES (?, ?, 'gpt-5.6-sol', 'GPT-5.6 Sol', 'medium', 'AVAILABLE', 0)
                """, workerId, revision);
        jdbcTemplate.update("""
                INSERT INTO worker_codex_model_effort (worker_id, catalog_revision, model_id, effort, position)
                VALUES (?, ?, 'gpt-5.6-sol', 'medium', 0),
                       (?, ?, 'gpt-5.6-sol', 'high', 1)
                """, workerId, revision, workerId, revision);
    }
}
