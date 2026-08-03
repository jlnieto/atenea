package com.atenea.persistence.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CodexExecutionProfileJpaIntegrationTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkSessionRepository workSessionRepository;

    @Autowired
    private SessionTurnRepository sessionTurnRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsDefaultsAndKeepsEffectiveRunProfileImmutable() {
        Instant now = Instant.parse("2026-07-31T11:00:00Z");

        ProjectEntity project = new ProjectEntity();
        project.setName("codex-profile-integration");
        project.setRepoPath("/workspace/repos/internal/atenea");
        project.setDefaultBaseBranch("main");
        project.setDefaultCodexModelId("gpt-5.6-sol");
        project.setDefaultCodexReasoningEffort(CodexReasoningEffort.MEDIUM);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project = projectRepository.save(project);

        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Codex profile persistence");
        session.setBaseBranch("main");
        session.setExecutionTarget(ExecutionTarget.LOCAL);
        session.setWorkspaceIdentity("local:work-session:profile-integration");
        session.setDefaultCodexReasoningEffort(CodexReasoningEffort.HIGH);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now);
        session.setLastActivityAt(now);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session = workSessionRepository.save(session);

        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setSession(session);
        turn.setActor(SessionTurnActor.OPERATOR);
        turn.setMessageText("Synthetic profile persistence test");
        turn.setCreatedAt(now);
        turn = sessionTurnRepository.save(turn);

        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session);
        run.setOriginTurn(turn);
        run.setStatus(AgentRunStatus.RUNNING);
        run.setTargetRepoPath(project.getRepoPath());
        run.setExecutionTarget(ExecutionTarget.LOCAL);
        run.setWorkspaceIdentity(session.getWorkspaceIdentity());
        run.setWorkloadClass(WorkloadClass.NORMAL);
        run.setStartedAt(now);
        run.setCreatedAt(now);
        run.setCodexModelId("gpt-5.6-sol");
        run.setCodexModelSource(ExecutionProfileSource.PROJECT);
        run.setCodexReasoningEffort(CodexReasoningEffort.HIGH);
        run.setCodexEffortSource(ExecutionProfileSource.WORK_SESSION);
        run.setCodexCatalogRevision("a".repeat(64));
        run.setCodexVersion("0.145.0");
        run = agentRunRepository.saveAndFlush(run);
        Long runId = run.getId();

        entityManager.clear();
        AgentRunEntity persisted = agentRunRepository.findById(runId).orElseThrow();
        persisted.setCodexModelId("gpt-5.6-terra");
        persisted.setCodexReasoningEffort(CodexReasoningEffort.MAX);
        agentRunRepository.saveAndFlush(persisted);
        entityManager.clear();

        AgentRunEntity reloaded = agentRunRepository.findById(runId).orElseThrow();
        WorkSessionEntity reloadedSession = workSessionRepository.findById(session.getId()).orElseThrow();
        assertNull(reloadedSession.getDefaultCodexModelId());
        assertEquals(CodexReasoningEffort.HIGH, reloadedSession.getDefaultCodexReasoningEffort());
        assertEquals("gpt-5.6-sol", reloaded.getCodexModelId());
        assertEquals(CodexReasoningEffort.HIGH, reloaded.getCodexReasoningEffort());
        assertEquals(ExecutionProfileSource.PROJECT, reloaded.getCodexModelSource());
        assertEquals(ExecutionProfileSource.WORK_SESSION, reloaded.getCodexEffortSource());
        assertEquals("a".repeat(64), reloaded.getCodexCatalogRevision());
        assertEquals("0.145.0", reloaded.getCodexVersion());
    }

    @Test
    void persistsNormalizedWorkerCatalogWithAdvertisedDefaultEffort() {
        jdbcTemplate.update("""
                INSERT INTO worker_node (
                    id, protocol_version, endpoint, enabled, healthy,
                    normal_capacity, heavy_capacity, capabilities,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                "profile-test-worker",
                "agent-run-worker/v1",
                "https://worker.invalid",
                false,
                false,
                4,
                2,
                "project-codex-v2"
        );
        jdbcTemplate.update("""
                INSERT INTO worker_codex_catalog (
                    worker_id, catalog_revision, schema_version, codex_version,
                    generated_at, observed_at
                ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                "profile-test-worker",
                "b".repeat(64),
                "codex-model-catalog-v1",
                "0.145.0"
        );
        jdbcTemplate.update("""
                INSERT INTO worker_codex_model (
                    worker_id, catalog_revision, model_id, display_name,
                    default_effort, availability, position
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                "profile-test-worker",
                "b".repeat(64),
                "gpt-5.6-sol",
                "GPT-5.6 Sol",
                "medium",
                "AVAILABLE",
                0
        );
        jdbcTemplate.update("""
                INSERT INTO worker_codex_model_effort (
                    worker_id, catalog_revision, model_id, effort, position
                ) VALUES (?, ?, ?, ?, ?)
                """,
                "profile-test-worker",
                "b".repeat(64),
                "gpt-5.6-sol",
                "medium",
                0
        );
        jdbcTemplate.execute("SET CONSTRAINTS fk_worker_codex_model_default_effort IMMEDIATE");

        Integer models = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM worker_codex_model WHERE worker_id = ?",
                Integer.class,
                "profile-test-worker"
        );
        Integer efforts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM worker_codex_model_effort WHERE worker_id = ?",
                Integer.class,
                "profile-test-worker"
        );
        assertEquals(1, models);
        assertEquals(1, efforts);
    }
}
