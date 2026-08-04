package com.atenea.codexoperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "atenea.auth.bootstrap.enabled=false",
        "atenea.remote-worker.remote-close-reconciliation-enabled=true",
        "atenea.remote-worker.remote-close-project-allowlist=atenea"
})
@AutoConfigureMockMvc
@Transactional
class LegacyRemoteCloseApiIntegrationTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkSessionRepository sessionRepository;

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

        String first = mockMvc.perform(post(
                        "/api/admin/work-sessions/{id}/remote-close-reconciliations", session.getId())
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(confirmation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workSessionId").value(session.getId()))
                .andExpect(jsonPath("$.operation").value("RECONCILE_REMOTE_CLOSE"))
                .andExpect(jsonPath("$.state").value("REQUESTED"))
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
        assertEquals(RemoteCloseState.UNVERIFIED_LEGACY, unchanged.getRemoteCloseState());
        assertNull(unchanged.getRemoteCloseOperationId());
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
        ProjectEntity project = projectRepository.findByName("Atenea").orElseGet(() -> {
            ProjectEntity created = new ProjectEntity();
            created.setName("Atenea");
            created.setRepoPath(ProjectCodexIdentity.REPO_PATH);
            created.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
            created.setCreatedAt(now);
            created.setUpdatedAt(now);
            return projectRepository.save(created);
        });

        UUID remoteSessionId = UUID.randomUUID();
        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project);
        session.setStatus(WorkSessionStatus.CLOSED);
        session.setTitle("Legacy close fixture");
        session.setBaseBranch(ProjectCodexIdentity.BRANCH);
        session.setWorkspaceBranch("atenea/session-" + remoteSessionId);
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(ProjectCodexIdentity.WORKER_ID);
        session.setWorkspaceIdentity("remote:" + ProjectCodexIdentity.WORKER_ID
                + ":work-session:" + remoteSessionId);
        session.setRemoteSessionId(remoteSessionId);
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setRemoteCloseState(RemoteCloseState.UNVERIFIED_LEGACY);
        session.setRemoteCloseRevision(0);
        session.setCanonicalSourceRef("refs/heads/main");
        session.setCanonicalSourceCommit("a".repeat(40));
        session.setCanonicalSourceObservationSha256("b".repeat(64));
        session.setCanonicalSourceObservedAt(now.minusSeconds(60));
        session.setAcceptanceState(WorkSessionAcceptanceState.DRAFT);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now.minusSeconds(3600));
        session.setLastActivityAt(now.minusSeconds(120));
        session.setClosedAt(now);
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
}
