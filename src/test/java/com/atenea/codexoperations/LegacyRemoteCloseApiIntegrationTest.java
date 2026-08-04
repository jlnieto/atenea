package com.atenea.codexoperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionAcceptanceState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "atenea.auth.bootstrap.enabled=false",
        "atenea.remote-worker.remote-close-reconciliation-enabled=true",
        "atenea.remote-worker.remote-close-project-allowlist=atenea"
})
@AutoConfigureMockMvc
class LegacyRemoteCloseApiIntegrationTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkSessionRepository sessionRepository;
    @MockBean private RemoteWorkerClient remoteWorkerClient;

    @BeforeEach
    void cleanSyntheticLegacyFixtures() {
        jdbcTemplate.update("DELETE FROM remote_close_legacy_operation");
        jdbcTemplate.update("DELETE FROM remote_close_legacy_plan");
        jdbcTemplate.update("""
                DELETE FROM agent_run WHERE session_id IN (
                    SELECT id FROM work_session WHERE title = 'Legacy close fixture')
                """);
        jdbcTemplate.update("""
                DELETE FROM session_turn WHERE session_id IN (
                    SELECT id FROM work_session WHERE title = 'Legacy close fixture')
                """);
        jdbcTemplate.update("""
                DELETE FROM work_session_attachment WHERE work_session_id IN (
                    SELECT id FROM work_session WHERE title = 'Legacy close fixture')
                """);
        jdbcTemplate.update("DELETE FROM work_session WHERE title = 'Legacy close fixture'");
        jdbcTemplate.update("DELETE FROM project WHERE name LIKE 'Foreign legacy %'");
        jdbcTemplate.update(
                "DELETE FROM operator_account WHERE email LIKE 'legacy-close-%@atenea.test'");
    }

    @Test
    void administratorCreatesIdempotentReadOnlyPlanWithoutChangingSession() throws Exception {
        OperatorEntity routine = operator(CodexOperationsRole.ROUTINE_OPERATOR);
        OperatorEntity administrator = operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        WorkSessionEntity session = legacySession();
        UUID idempotencyKey = UUID.randomUUID();
        String body = planBody(idempotencyKey, false);

        mockMvc.perform(post("/api/admin/work-sessions/{id}/remote-close-plans", session.getId())
                        .with(auth(routine)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertEquals(0, count("remote_close_legacy_plan"));

        String first = mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-plans", session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workSessionId").value(session.getId()))
                .andExpect(jsonPath("$.operation").value("RECONCILE_REMOTE_CLOSE"))
                .andExpect(jsonPath("$.state").value("READY_FOR_CONFIRMATION"))
                .andExpect(jsonPath("$.requiredRole").value("PLATFORM_ADMINISTRATOR"))
                .andExpect(jsonPath("$.ownershipFingerprintSha256").isString())
                .andExpect(jsonPath("$.consumed").value(false))
                .andExpect(jsonPath("$.valuesExposed").value(false))
                .andExpect(jsonPath("$.path").doesNotExist())
                .andExpect(jsonPath("$.slot").doesNotExist())
                .andExpect(jsonPath("$.endpoint").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-plans", session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
        assertEquals(1, count("remote_close_legacy_plan"));
        assertEquals(0, count("remote_close_legacy_operation"));
        WorkSessionEntity unchanged = sessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(WorkSessionStatus.CLOSED, unchanged.getStatus());
        assertEquals(RemoteCloseState.UNVERIFIED_LEGACY, unchanged.getRemoteCloseState());
        assertNull(unchanged.getRemoteCloseOperationId());
        assertEquals(0, unchanged.getRemoteCloseRevision());

        String planId = com.jayway.jsonpath.JsonPath.read(first, "$.planId");
        mockMvc.perform(get("/api/admin/work-sessions/remote-close-plans/{id}", planId)
                        .with(auth(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(planId));
        mockMvc.perform(post("/api/admin/work-sessions/{id}/remote-close-plans", session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID(), true)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exactConfirmationIsSingleUseFiniteAndIdempotent() throws Exception {
        OperatorEntity administrator = operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        WorkSessionEntity session = legacySession();
        String plan = createPlan(administrator, session, UUID.randomUUID());
        UUID planId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(plan, "$.planId"));
        String fingerprint = com.jayway.jsonpath.JsonPath.read(
                plan, "$.ownershipFingerprintSha256");
        UUID operationKey = UUID.randomUUID();
        String confirmation = confirmationBody(planId, fingerprint, operationKey, false);
        Instant historicalClosedAt = session.getClosedAt();
        when(remoteWorkerClient.releaseWorkspace(any()))
                .thenAnswer(invocation -> releaseReceipt(invocation.getArgument(0)));

        String first = mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-reconciliations", session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workSessionId").value(session.getId()))
                .andExpect(jsonPath("$.operation").value("RECONCILE_REMOTE_CLOSE"))
                .andExpect(jsonPath("$.state").value("RELEASED"))
                .andExpect(jsonPath("$.ownershipFingerprintSha256").value(fingerprint))
                .andExpect(jsonPath("$.valuesExposed").value(false))
                .andExpect(jsonPath("$.command").doesNotExist())
                .andExpect(jsonPath("$.resource").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-reconciliations", session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
        verify(remoteWorkerClient, times(1)).releaseWorkspace(any());
        assertEquals(1, count("remote_close_legacy_plan"));
        assertEquals(1, count("remote_close_legacy_operation"));
        mockMvc.perform(get(
                        "/api/admin/work-sessions/remote-close-plans/{id}", planId)
                .with(auth(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONSUMED"))
                .andExpect(jsonPath("$.consumed").value(true));
        mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-reconciliations", session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationBody(planId, fingerprint, UUID.randomUUID(), false)))
                .andExpect(status().isConflict());
        assertEquals(1, count("remote_close_legacy_operation"));

        WorkSessionEntity unchanged = sessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(WorkSessionStatus.CLOSED, unchanged.getStatus());
        assertEquals(historicalClosedAt, unchanged.getClosedAt());
        assertEquals(RemoteCloseState.RELEASED, unchanged.getRemoteCloseState());
        assertNotNull(unchanged.getRemoteCloseOperationId());
        assertNotNull(unchanged.getRemoteCloseReceiptSha256());
    }

    @Test
    void staleExpiredWrongRoleAndExtendedConfirmationsFailBeforeOperation() throws Exception {
        OperatorEntity routine = operator(CodexOperationsRole.ROUTINE_OPERATOR);
        OperatorEntity administrator = operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        WorkSessionEntity session = legacySession();
        String plan = createPlan(administrator, session, UUID.randomUUID());
        UUID planId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(plan, "$.planId"));
        String fingerprint = com.jayway.jsonpath.JsonPath.read(
                plan, "$.ownershipFingerprintSha256");
        String confirmation = confirmationBody(
                planId, fingerprint, UUID.randomUUID(), false);

        mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-reconciliations", session.getId())
                        .with(auth(routine)).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-reconciliations", session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationBody(planId, fingerprint, UUID.randomUUID(), true)))
                .andExpect(status().isBadRequest());

        session.setFinalCommitSha("c".repeat(40));
        sessionRepository.saveAndFlush(session);
        mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-reconciliations", session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation))
                .andExpect(status().isConflict());
        assertEquals(0, count("remote_close_legacy_operation"));

        WorkSessionEntity freshSession = legacySession();
        String expiringPlan = createPlan(administrator, freshSession, UUID.randomUUID());
        UUID expiringPlanId = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(expiringPlan, "$.planId"));
        String expiringFingerprint = com.jayway.jsonpath.JsonPath.read(
                expiringPlan, "$.ownershipFingerprintSha256");
        jdbcTemplate.update("""
                UPDATE remote_close_legacy_plan
                   SET created_at = CURRENT_TIMESTAMP - INTERVAL '20 minutes',
                       expires_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes'
                 WHERE plan_id = ?
                """, expiringPlanId);
        mockMvc.perform(get(
                        "/api/admin/work-sessions/remote-close-plans/{id}", expiringPlanId)
                        .with(auth(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("EXPIRED"))
                .andExpect(jsonPath("$.consumed").value(false));
        mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-reconciliations",
                        freshSession.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationBody(expiringPlanId, expiringFingerprint,
                                UUID.randomUUID(), false)))
                .andExpect(status().isConflict());
        assertEquals(0, count("remote_close_legacy_operation"));
        verifyNoInteractions(remoteWorkerClient);
    }

    @Test
    void nonTerminalRunBlocksConfirmedReleaseBeforeWorkerIo() throws Exception {
        OperatorEntity administrator = operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        WorkSessionEntity session = legacySession();
        jdbcTemplate.update("""
                INSERT INTO session_turn (session_id, actor, message_text, created_at)
                VALUES (?, 'OPERATOR', 'synthetic task-4.5 fixture', CURRENT_TIMESTAMP)
                """, session.getId());
        Long turnId = jdbcTemplate.queryForObject(
                "SELECT max(id) FROM session_turn WHERE session_id = ?",
                Long.class, session.getId());
        jdbcTemplate.update("""
                INSERT INTO agent_run (
                    session_id, origin_turn_id, status, target_repo_path,
                    started_at, execution_target,
                    selected_worker_id, workspace_identity, dispatch_id,
                    remote_session_id, workload_kind, project_identity,
                    repository_url, repository_branch, repository_commit,
                    manifest_sha256,
                    workload_class, lease_generation, lifecycle_revision,
                    lock_version, created_at)
                VALUES (?, ?, 'RUNNING', ?, CURRENT_TIMESTAMP, 'REMOTE', ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, 'NORMAL', 1, 1, 0,
                    CURRENT_TIMESTAMP)
                """, session.getId(), turnId, ProjectCodexIdentity.REPO_PATH,
                ProjectCodexIdentity.WORKER_ID, session.getWorkspaceIdentity(),
                UUID.randomUUID(), session.getRemoteSessionId(),
                ProjectCodexIdentity.WORKLOAD_KIND, ProjectCodexIdentity.PROJECT_IDENTITY,
                ProjectCodexIdentity.REPOSITORY, ProjectCodexIdentity.BRANCH,
                session.getCanonicalSourceCommit(), ProjectCodexIdentity.MANIFEST_SHA256);
        String plan = createPlan(administrator, session, UUID.randomUUID());
        UUID planId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(plan, "$.planId"));
        String fingerprint = com.jayway.jsonpath.JsonPath.read(
                plan, "$.ownershipFingerprintSha256");

        mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-reconciliations",
                        session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationBody(planId, fingerprint,
                                UUID.randomUUID(), false)))
                .andExpect(status().isConflict());

        assertEquals(0, count("remote_close_legacy_operation"));
        WorkSessionEntity unchanged = sessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(RemoteCloseState.UNVERIFIED_LEGACY, unchanged.getRemoteCloseState());
        assertNull(unchanged.getRemoteCloseOperationId());
        verifyNoInteractions(remoteWorkerClient);
    }

    @Test
    void openForeignAndAmbiguousOwnersAreRejectedBeforeMutation() throws Exception {
        OperatorEntity administrator = operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        Instant now = Instant.parse("2026-08-03T11:00:00Z")
                .plusSeconds(SEQUENCE.incrementAndGet());
        WorkSessionEntity open = remoteSession(
                canonicalProject(now), WorkSessionStatus.OPEN,
                RemoteCloseState.NOT_STARTED, now);

        mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-plans", open.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID(), false)))
                .andExpect(status().isConflict());

        ProjectEntity foreignProject = new ProjectEntity();
        foreignProject.setName("Foreign legacy " + SEQUENCE.incrementAndGet());
        foreignProject.setRepoPath("/workspace/repos/internal/foreign-legacy");
        foreignProject.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
        foreignProject.setCreatedAt(now);
        foreignProject.setUpdatedAt(now);
        foreignProject = projectRepository.save(foreignProject);
        WorkSessionEntity foreign = remoteSession(
                foreignProject, WorkSessionStatus.CLOSED,
                RemoteCloseState.UNVERIFIED_LEGACY, now.plusSeconds(1));
        mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-plans", foreign.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID(), false)))
                .andExpect(status().isConflict());

        WorkSessionEntity ambiguous = legacySession();
        ambiguous.setWorkspaceIdentity("remote:ax42-01:work-session:" + UUID.randomUUID());
        sessionRepository.saveAndFlush(ambiguous);
        mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-plans", ambiguous.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID(), false)))
                .andExpect(status().isConflict());

        assertEquals(0, count("remote_close_legacy_plan"));
        assertEquals(0, count("remote_close_legacy_operation"));
        verifyNoInteractions(remoteWorkerClient);
    }

    @Test
    void lostWorkerResponseReusesDurableOperationAndPersistsExactReceipt() throws Exception {
        OperatorEntity administrator = operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        WorkSessionEntity session = legacySession();
        String plan = createPlan(administrator, session, UUID.randomUUID());
        UUID planId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(plan, "$.planId"));
        String fingerprint = com.jayway.jsonpath.JsonPath.read(
                plan, "$.ownershipFingerprintSha256");
        String confirmation = confirmationBody(
                planId, fingerprint, UUID.randomUUID(), false);
        when(remoteWorkerClient.releaseWorkspace(any()))
                .thenThrow(new RemoteWorkerException(
                        "Synthetic lost response", new IOException("synthetic")))
                .thenAnswer(invocation -> releaseReceipt(invocation.getArgument(0)));

        assertThrows(jakarta.servlet.ServletException.class, () -> mockMvc.perform(post(
                                "/api/admin/work-sessions/{id}/remote-close-reconciliations",
                                session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation)));

        WorkSessionEntity durable = sessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(RemoteCloseState.REQUESTED, durable.getRemoteCloseState());
        assertNotNull(durable.getRemoteCloseOperationId());
        assertEquals(1, count("remote_close_legacy_operation"));

        mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-reconciliations",
                        session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RELEASED"));

        verify(remoteWorkerClient, times(2)).releaseWorkspace(any());
        WorkSessionEntity released = sessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(RemoteCloseState.RELEASED, released.getRemoteCloseState());
        assertEquals(durable.getRemoteCloseOperationId(), released.getRemoteCloseOperationId());
    }

    private String createPlan(
            OperatorEntity administrator, WorkSessionEntity session, UUID idempotencyKey)
            throws Exception {
        return mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-plans", session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(idempotencyKey, false)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private int count(String table) {
        if (!List.of("remote_close_legacy_plan", "remote_close_legacy_operation")
                .contains(table)) {
            throw new IllegalArgumentException("Unexpected table");
        }
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private OperatorEntity operator(CodexOperationsRole role) {
        long value = SEQUENCE.incrementAndGet();
        Instant now = Instant.parse("2026-08-03T12:00:00Z");
        OperatorEntity operator = new OperatorEntity();
        operator.setEmail("legacy-close-" + value + "@atenea.test");
        operator.setDisplayName("Legacy close operator");
        operator.setPasswordHash("synthetic-hash");
        operator.setActive(true);
        operator.setCodexOperationsRole(role);
        operator.setCreatedAt(now);
        operator.setUpdatedAt(now);
        return operatorRepository.save(operator);
    }

    private WorkSessionEntity legacySession() {
        long value = SEQUENCE.incrementAndGet();
        Instant now = Instant.parse("2026-08-03T11:00:00Z").plusSeconds(value);
        return remoteSession(canonicalProject(now), WorkSessionStatus.CLOSED,
                RemoteCloseState.UNVERIFIED_LEGACY, now);
    }

    private ProjectEntity canonicalProject(Instant now) {
        return projectRepository.findByName("Atenea").orElseGet(() -> {
            ProjectEntity created = new ProjectEntity();
            created.setName("Atenea");
            created.setRepoPath(ProjectCodexIdentity.REPO_PATH);
            created.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            return projectRepository.save(created);
        });
    }

    private WorkSessionEntity remoteSession(
            ProjectEntity project,
            WorkSessionStatus status,
            RemoteCloseState closeState,
            Instant now) {
        jdbcTemplate.update("""
                INSERT INTO worker_node (
                    id, protocol_version, endpoint, enabled, healthy,
                    normal_capacity, heavy_capacity, capabilities,
                    created_at, updated_at)
                VALUES (?, 'agent-run-worker/v1', 'https://worker.invalid',
                    FALSE, TRUE, 4, 2, 'project-codex-v1',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """, ProjectCodexIdentity.WORKER_ID);
        UUID remoteSessionId = UUID.randomUUID();
        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setStatus(status);
        session.setTitle("Legacy close fixture");
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setWorkspaceBranch("atenea/session-" + remoteSessionId);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        session.setWorkspaceIdentity("remote:" + ProjectCodexIdentity.WORKER_ID
                + ":work-session:" + remoteSessionId);
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setRemoteCloseState(closeState);
        session.setRemoteCloseRevision(0);
        session.setCanonicalSourceRef("refs/heads/main");
        session.setCanonicalSourceCommit("a".repeat(40));
        session.setCanonicalSourceObservationSha256("b".repeat(64));
        session.setCanonicalSourceObservedAt(now.minusSeconds(60));
        session.setAcceptanceState(WorkSessionAcceptanceState.DRAFT);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now.minusSeconds(3600));
        session.setLastActivityAt(now.minusSeconds(120));
        session.setClosedAt(status == WorkSessionStatus.CLOSED ? now : null);
        session.setCreatedAt(now.minusSeconds(3600));
        session.setUpdatedAt(now);
        return sessionRepository.saveAndFlush(session);
    }

    private RequestPostProcessor auth(OperatorEntity operator) {
        AuthenticatedOperator principal = new AuthenticatedOperator(
                operator.getId(), operator.getEmail(), operator.getDisplayName());
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }

    private String planBody(UUID idempotencyKey, boolean extra) {
        return """
                {"operation":"RECONCILE_REMOTE_CLOSE","idempotencyKey":"%s"%s}
                """.formatted(idempotencyKey, extra ? ",\"workerId\":\"ax42-01\"" : "");
    }

    private String confirmationBody(
            UUID planId, String fingerprint, UUID idempotencyKey, boolean extra) {
        return """
                {"operation":"RECONCILE_REMOTE_CLOSE","planId":"%s",
                 "ownershipFingerprintSha256":"%s","idempotencyKey":"%s"%s}
                """.formatted(planId, fingerprint, idempotencyKey,
                extra ? ",\"resource\":\"caller-selected\"" : "");
    }

    private RemoteWorkerClient.WorkspaceRelease releaseReceipt(WorkSessionEntity session) {
        String operationId = session.getRemoteCloseOperationId().toString();
        return new RemoteWorkerClient.WorkspaceRelease(
                "project-workspace-release-v1", "RELEASED", operationId, operationId,
                session.getRemoteSessionId().toString(), session.getWorkspaceIdentity(),
                ProjectCodexIdentity.PROJECT_IDENTITY, ProjectCodexIdentity.REPOSITORY,
                ProjectCodexIdentity.BRANCH, session.getCanonicalSourceCommit(),
                ProjectCodexIdentity.MANIFEST_SHA256, session.getWorkspaceBranch(),
                ProjectCodexIdentity.WORKER_ID, "1".repeat(64), 2,
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                "2".repeat(64), "3".repeat(64), false);
    }
}
