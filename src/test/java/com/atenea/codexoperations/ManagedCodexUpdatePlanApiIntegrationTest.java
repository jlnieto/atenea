package com.atenea.codexoperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.atenea.remoteworker.RemoteWorkerClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "atenea.auth.bootstrap.enabled=false",
        "atenea.codex-session-operations.managed-updates-enabled=true"
})
@AutoConfigureMockMvc
@Transactional
class ManagedCodexUpdatePlanApiIntegrationTest {

    private static final String WORKER_ID = "ax42-01";
    private static final String CATALOG = "b".repeat(64);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OperatorRepository operatorRepository;
    @MockBean private RemoteWorkerClient remoteWorkerClient;

    @Test
    void routineOperatorInspectsClosedInstalledCurrentPreviousInventory() throws Exception {
        OperatorEntity routine = operator(CodexOperationsRole.ROUTINE_OPERATOR);
        Inventory inventory = inventory(true);

        mockMvc.perform(get("/api/codex/workers/{workerId}/inventory", WORKER_ID)
                        .with(auth(routine)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value(WORKER_ID))
                .andExpect(jsonPath("$.protocolVersion").value("agent-run-worker/v1"))
                .andExpect(jsonPath("$.installedVersions.length()").value(2))
                .andExpect(jsonPath("$.currentVersion").value("0.145.0"))
                .andExpect(jsonPath("$.previousVersion").value("0.144.0"))
                .andExpect(jsonPath("$.compatibilityState").value("COMPATIBLE"))
                .andExpect(jsonPath("$.releases[0].releaseDigestSha256").exists())
                .andExpect(jsonPath("$.endpoint").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.command").doesNotExist());

        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM worker_codex_release_inventory WHERE worker_id = ?",
                Integer.class, WORKER_ID));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM worker_codex_update_plan", Integer.class));
        org.junit.jupiter.api.Assertions.assertNotNull(inventory.current());
    }

    @Test
    void administratorCreatesIdempotentReadOnlyPlanFromPersistedCandidate() throws Exception {
        OperatorEntity routine = operator(CodexOperationsRole.ROUTINE_OPERATOR);
        OperatorEntity administrator = operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        Inventory inventory = inventory(true);
        UUID idempotencyKey = UUID.randomUUID();
        String body = planBody(idempotencyKey, false);

        mockMvc.perform(post("/api/admin/codex/update-plans")
                        .with(auth(routine)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        String first = mockMvc.perform(post("/api/admin/codex/update-plans")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value(WORKER_ID))
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.compatibilityState").value("COMPATIBLE"))
                .andExpect(jsonPath("$.current.inventoryId").value(inventory.current().toString()))
                .andExpect(jsonPath("$.previous.inventoryId").value(inventory.previous().toString()))
                .andExpect(jsonPath("$.candidate.inventoryId").value(inventory.candidate().toString()))
                .andExpect(jsonPath("$.gates.length()").value(4))
                .andExpect(jsonPath("$.gates[0].state").value("PASS"))
                .andExpect(jsonPath("$.expectedServiceImpact").value(
                        "No installation or restart; a later activation would restart only the exact Codex/worker boundary, never project runtimes or unrelated slots."))
                .andExpect(jsonPath("$.host").doesNotExist())
                .andExpect(jsonPath("$.service").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/admin/codex/update-plans")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM worker_codex_update_plan", Integer.class));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM worker_codex_release_inventory WHERE worker_id = ?",
                Integer.class, WORKER_ID));

        String planId = com.jayway.jsonpath.JsonPath.read(first, "$.planId");
        mockMvc.perform(get("/api/admin/codex/update-plans/{planId}", planId)
                        .with(auth(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(planId));

        mockMvc.perform(post("/api/admin/codex/update-plans")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID(), true)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void planFailsClosedWhenCandidateIsUnavailable() throws Exception {
        OperatorEntity administrator = operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        inventory(false);

        mockMvc.perform(post("/api/admin/codex/update-plans")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID(), false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BLOCKED"))
                .andExpect(jsonPath("$.compatibilityState").value("BLOCKED"))
                .andExpect(jsonPath("$.candidate").doesNotExist())
                .andExpect(jsonPath("$.gates[3].gate").value("CANDIDATE_COMPATIBILITY"))
                .andExpect(jsonPath("$.gates[3].state").value("BLOCKED"));
    }

    @Test
    void incompatibleCandidateRemainsVisibleAndBlocksPlan() throws Exception {
        OperatorEntity administrator = operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        Inventory inventory = inventory(true);
        jdbcTemplate.update("""
                UPDATE worker_codex_release_inventory
                   SET compatibility_state = 'INCOMPATIBLE'
                 WHERE inventory_id = ?
                """, inventory.candidate());

        mockMvc.perform(post("/api/admin/codex/update-plans")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID(), false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BLOCKED"))
                .andExpect(jsonPath("$.candidate.inventoryId").value(inventory.candidate().toString()))
                .andExpect(jsonPath("$.candidate.compatibilityState").value("INCOMPATIBLE"))
                .andExpect(jsonPath("$.gates[3].state").value("BLOCKED"));
    }

    @Test
    void administratorStagesVerifiedCandidateIdempotentlyWithoutChangingLinks() throws Exception {
        OperatorEntity routine = operator(CodexOperationsRole.ROUTINE_OPERATOR);
        OperatorEntity administrator = operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        Inventory inventory = inventory(true);
        String plan = mockMvc.perform(post("/api/admin/codex/update-plans")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID(), false)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID planId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(plan, "$.planId"));
        UUID idempotencyKey = UUID.randomUUID();
        when(remoteWorkerClient.stageCodexUpdate(planId, inventory.candidate(), idempotencyKey))
                .thenReturn(stageResult(planId, inventory.candidate(), idempotencyKey,
                        "3".repeat(64)));
        String body = stageBody(planId, inventory.candidate(), idempotencyKey, false);

        mockMvc.perform(post("/api/admin/codex/update-stages")
                        .with(auth(routine)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        String first = mockMvc.perform(post("/api/admin/codex/update-stages")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STAGED"))
                .andExpect(jsonPath("$.planId").value(planId.toString()))
                .andExpect(jsonPath("$.current.inventoryId").value(inventory.current().toString()))
                .andExpect(jsonPath("$.current.linkState").value("CURRENT"))
                .andExpect(jsonPath("$.previous.inventoryId").value(inventory.previous().toString()))
                .andExpect(jsonPath("$.previous.linkState").value("PREVIOUS"))
                .andExpect(jsonPath("$.candidate.inventoryId").value(inventory.candidate().toString()))
                .andExpect(jsonPath("$.candidate.installationState").value("STAGED"))
                .andExpect(jsonPath("$.gates.length()").value(3))
                .andExpect(jsonPath("$.gates[0].state").value("PASS"))
                .andExpect(jsonPath("$.linksChanged").value(false))
                .andExpect(jsonPath("$.valuesExposed").value(false))
                .andExpect(jsonPath("$.host").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/admin/codex/update-stages")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        verify(remoteWorkerClient, times(1))
                .stageCodexUpdate(planId, inventory.candidate(), idempotencyKey);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM worker_codex_stage_operation", Integer.class));
        assertEquals("STAGED", jdbcTemplate.queryForObject(
                "SELECT installation_state FROM worker_codex_release_inventory WHERE inventory_id = ?",
                String.class, inventory.candidate()));
        assertEquals("CURRENT", jdbcTemplate.queryForObject(
                "SELECT link_state FROM worker_codex_release_inventory WHERE inventory_id = ?",
                String.class, inventory.current()));
        assertEquals("PREVIOUS", jdbcTemplate.queryForObject(
                "SELECT link_state FROM worker_codex_release_inventory WHERE inventory_id = ?",
                String.class, inventory.previous()));

        String stageId = com.jayway.jsonpath.JsonPath.read(first, "$.stageId");
        mockMvc.perform(get("/api/admin/codex/update-stages/{stageId}", stageId)
                        .with(auth(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stageId").value(stageId));
        mockMvc.perform(post("/api/admin/codex/update-stages")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(stageBody(planId, inventory.candidate(), UUID.randomUUID(), true)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stageRejectsBlockedPlanAndConflictingWorkerProofWithoutMutation() throws Exception {
        OperatorEntity administrator = operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        Inventory inventory = inventory(true);
        jdbcTemplate.update("UPDATE worker_codex_release_inventory SET compatibility_state = 'INCOMPATIBLE' WHERE inventory_id = ?",
                inventory.candidate());
        String blocked = mockMvc.perform(post("/api/admin/codex/update-plans")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID(), false)))
                .andReturn().getResponse().getContentAsString();
        UUID blockedPlan = UUID.fromString(com.jayway.jsonpath.JsonPath.read(blocked, "$.planId"));
        mockMvc.perform(post("/api/admin/codex/update-stages")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(stageBody(blockedPlan, inventory.candidate(), UUID.randomUUID(), false)))
                .andExpect(status().isConflict());
        verify(remoteWorkerClient, never()).stageCodexUpdate(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        jdbcTemplate.update("DELETE FROM worker_codex_update_plan");
        jdbcTemplate.update("UPDATE worker_codex_release_inventory SET compatibility_state = 'COMPATIBLE' WHERE inventory_id = ?",
                inventory.candidate());
        String ready = mockMvc.perform(post("/api/admin/codex/update-plans")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID(), false)))
                .andReturn().getResponse().getContentAsString();
        UUID readyPlan = UUID.fromString(com.jayway.jsonpath.JsonPath.read(ready, "$.planId"));
        UUID key = UUID.randomUUID();
        when(remoteWorkerClient.stageCodexUpdate(readyPlan, inventory.candidate(), key))
                .thenReturn(stageResult(readyPlan, inventory.candidate(), key, "f".repeat(64)));

        mockMvc.perform(post("/api/admin/codex/update-stages")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(stageBody(readyPlan, inventory.candidate(), key, false)))
                .andExpect(status().isConflict());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM worker_codex_stage_operation", Integer.class));
        assertEquals("DISCOVERED", jdbcTemplate.queryForObject(
                "SELECT installation_state FROM worker_codex_release_inventory WHERE inventory_id = ?",
                String.class, inventory.candidate()));
    }

    @Test
    void administratorSeparatelyAuthorizesAndActivatesOneCanaryIdempotently() throws Exception {
        OperatorEntity routine = operator(CodexOperationsRole.ROUTINE_OPERATOR);
        OperatorEntity administrator = operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        Inventory inventory = inventory(true);
        String plan = mockMvc.perform(post("/api/admin/codex/update-plans")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID(), false)))
                .andReturn().getResponse().getContentAsString();
        UUID planId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(plan, "$.planId"));
        UUID stageKey = UUID.randomUUID();
        when(remoteWorkerClient.stageCodexUpdate(planId, inventory.candidate(), stageKey))
                .thenReturn(stageResult(planId, inventory.candidate(), stageKey, "3".repeat(64)));
        mockMvc.perform(post("/api/admin/codex/update-stages")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(stageBody(planId, inventory.candidate(), stageKey, false)))
                .andExpect(status().isOk());

        UUID authorizationKey = UUID.randomUUID();
        String authorizationBody = authorizationBody(
                planId, inventory.candidate(), authorizationKey, false);
        mockMvc.perform(post("/api/admin/codex/update-activation-authorizations")
                        .with(auth(routine)).contentType(MediaType.APPLICATION_JSON)
                        .content(authorizationBody))
                .andExpect(status().isForbidden());
        String authorization = mockMvc.perform(
                        post("/api/admin/codex/update-activation-authorizations")
                                .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                                .content(authorizationBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(planId.toString()))
                .andExpect(jsonPath("$.currentInventoryId").value(inventory.current().toString()))
                .andExpect(jsonPath("$.candidateInventoryId").value(inventory.candidate().toString()))
                .andExpect(jsonPath("$.authorizationDigestSha256").isString())
                .andExpect(jsonPath("$.automaticRestoreAuthorized").value(true))
                .andExpect(jsonPath("$.consumedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID authorizationId = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(authorization, "$.authorizationId"));
        String authorizationRepeat = mockMvc.perform(
                        post("/api/admin/codex/update-activation-authorizations")
                                .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                                .content(authorizationBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(authorization, authorizationRepeat);
        mockMvc.perform(post("/api/admin/codex/update-activation-authorizations")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(authorizationBody(planId, inventory.candidate(), UUID.randomUUID(), true)))
                .andExpect(status().isBadRequest());

        UUID activationKey = UUID.randomUUID();
        Long activeProject = insertSyntheticActiveRun();
        mockMvc.perform(post("/api/admin/codex/update-activations")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(activationBody(planId, inventory.candidate(), authorizationId,
                                activationKey, false)))
                .andExpect(status().isConflict());
        verify(remoteWorkerClient, never()).activateCodexUpdate(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        jdbcTemplate.update("DELETE FROM project WHERE id = ?", activeProject);

        when(remoteWorkerClient.activateCodexUpdate(
                planId, inventory.candidate(), authorizationId, activationKey))
                .thenReturn(activationResult(
                        planId, inventory.candidate(), authorizationId, activationKey));
        String activationBody = activationBody(
                planId, inventory.candidate(), authorizationId, activationKey, false);
        String first = mockMvc.perform(post("/api/admin/codex/update-activations")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(activationBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVATED"))
                .andExpect(jsonPath("$.current.inventoryId").value(inventory.candidate().toString()))
                .andExpect(jsonPath("$.current.linkState").value("CURRENT"))
                .andExpect(jsonPath("$.previous.inventoryId").value(inventory.current().toString()))
                .andExpect(jsonPath("$.previous.linkState").value("PREVIOUS"))
                .andExpect(jsonPath("$.gates.length()").value(4))
                .andExpect(jsonPath("$.gates[0].state").value("PASS"))
                .andExpect(jsonPath("$.gates[3].gate").value("CANARY"))
                .andExpect(jsonPath("$.gates[3].state").value("PASS"))
                .andExpect(jsonPath("$.automaticRestore").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.valuesExposed").value(false))
                .andExpect(jsonPath("$.service").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/admin/codex/update-activations")
                        .with(auth(administrator)).contentType(MediaType.APPLICATION_JSON)
                        .content(activationBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        verify(remoteWorkerClient, times(1)).activateCodexUpdate(
                planId, inventory.candidate(), authorizationId, activationKey);
        assertEquals("ACTIVATED", jdbcTemplate.queryForObject(
                "SELECT state FROM worker_codex_update_plan WHERE plan_id = ?",
                String.class, planId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM worker_codex_activation_operation", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM worker_codex_activation_authorization
                 WHERE authorization_id = ? AND consumed_at IS NOT NULL
                   AND consumed_activation_id IS NOT NULL
                """, Integer.class, authorizationId));

        String activationId = com.jayway.jsonpath.JsonPath.read(first, "$.activationId");
        mockMvc.perform(get("/api/admin/codex/update-activations/{activationId}", activationId)
                        .with(auth(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activationId").value(activationId));
        mockMvc.perform(get("/api/admin/codex/update-activation-authorizations/{authorizationId}",
                        authorizationId).with(auth(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumedAt").exists())
                .andExpect(jsonPath("$.consumedActivationId").value(activationId));
    }

    private Inventory inventory(boolean candidate) {
        jdbcTemplate.update("""
                INSERT INTO worker_node (id, protocol_version, endpoint, enabled, healthy,
                    normal_capacity, heavy_capacity, capabilities, created_at, updated_at)
                VALUES (?, 'agent-run-worker/v1', 'https://worker.invalid', true, true,
                    4, 2, 'project-codex-v2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, WORKER_ID);
        jdbcTemplate.update("""
                INSERT INTO worker_codex_catalog (worker_id, catalog_revision, schema_version,
                    codex_version, generated_at, observed_at)
                VALUES (?, ?, 'codex-model-catalog-v1', '0.145.0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, WORKER_ID, CATALOG);
        UUID current = release("0.145.0", "1".repeat(64), "INSTALLED", "CURRENT", CATALOG, 3);
        UUID previous = release("0.144.0", "2".repeat(64), "INSTALLED", "PREVIOUS", CATALOG, 1);
        UUID candidateId = candidate
                ? release("0.146.0", "3".repeat(64), "DISCOVERED", "NONE", CATALOG, 4)
                : null;
        return new Inventory(current, previous, candidateId);
    }

    private UUID release(String version, String digest, String installation, String link,
                         String catalog, long hour) {
        UUID id = UUID.randomUUID();
        Instant observedAt = Instant.parse("2026-07-31T10:00:00Z").plusSeconds(hour * 3600);
        jdbcTemplate.update("""
                INSERT INTO worker_codex_release_inventory (
                    inventory_id, worker_id, codex_version, release_digest_sha256,
                    installation_state, link_state, compatibility_state,
                    catalog_revision, observed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'COMPATIBLE', ?, ?, ?, ?)
                """, id, WORKER_ID, version, digest, installation, link, catalog,
                java.sql.Timestamp.from(observedAt), java.sql.Timestamp.from(observedAt),
                java.sql.Timestamp.from(observedAt));
        return id;
    }

    private OperatorEntity operator(CodexOperationsRole role) {
        Instant now = Instant.parse("2026-07-31T10:00:00Z");
        OperatorEntity operator = new OperatorEntity();
        operator.setEmail(UUID.randomUUID() + "@atenea.test");
        operator.setDisplayName("Managed update operator");
        operator.setPasswordHash("synthetic-hash");
        operator.setActive(true);
        operator.setCodexOperationsRole(role);
        operator.setCreatedAt(now);
        operator.setUpdatedAt(now);
        return operatorRepository.saveAndFlush(operator);
    }

    private Long insertSyntheticActiveRun() {
        String identity = UUID.randomUUID().toString();
        Long projectId = jdbcTemplate.queryForObject("""
                INSERT INTO project (name, repo_path, created_at, updated_at)
                VALUES (?, '/tmp/synthetic', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, "activation-active-" + identity);
        Long sessionId = jdbcTemplate.queryForObject("""
                INSERT INTO work_session (
                    project_id, status, title, base_branch, opened_at, last_activity_at,
                    execution_target, selected_worker_id, workspace_identity,
                    remote_session_id, remote_workload_kind)
                VALUES (?, 'OPEN', 'Synthetic active gate', 'main', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, 'REMOTE', ?, ?, ?::uuid, 'synthetic-routing-v1')
                RETURNING id
                """, Long.class, projectId, WORKER_ID, "remote:" + identity, identity);
        Long turnId = jdbcTemplate.queryForObject("""
                INSERT INTO session_turn (session_id, actor, message_text)
                VALUES (?, 'OPERATOR', 'Synthetic active execution') RETURNING id
                """, Long.class, sessionId);
        jdbcTemplate.update("""
                INSERT INTO agent_run (
                    session_id, origin_turn_id, status, target_repo_path, started_at,
                    execution_target, selected_worker_id, workspace_identity, dispatch_id,
                    remote_session_id, workload_kind)
                VALUES (?, ?, 'QUEUED', '/tmp/synthetic', CURRENT_TIMESTAMP, 'REMOTE',
                    ?, ?, ?::uuid, ?::uuid, 'synthetic-routing-v1')
                """, sessionId, turnId, WORKER_ID, "remote:" + identity,
                UUID.randomUUID().toString(), identity);
        return projectId;
    }

    private RequestPostProcessor auth(OperatorEntity operator) {
        AuthenticatedOperator principal = new AuthenticatedOperator(operator.getId(), operator.getEmail(),
                operator.getDisplayName());
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }

    private String planBody(UUID idempotencyKey, boolean extra) {
        return """
                {"operation":"PLAN_CODEX_UPDATE","workerId":"ax42-01",
                 "idempotencyKey":"%s"%s}
                """.formatted(idempotencyKey, extra ? ",\"host\":\"ax42\"" : "");
    }

    private String stageBody(UUID planId, UUID candidateId, UUID idempotencyKey, boolean extra) {
        return """
                {"operation":"STAGE_CODEX_UPDATE","planId":"%s",
                 "candidateId":"%s","idempotencyKey":"%s"%s}
                """.formatted(planId, candidateId, idempotencyKey,
                extra ? ",\"releaseUrl\":\"https://foreign.invalid/release\"" : "");
    }

    private String authorizationBody(
            UUID planId, UUID candidateId, UUID idempotencyKey, boolean extra) {
        return """
                {"operation":"AUTHORIZE_CODEX_UPDATE_ACTIVATION","planId":"%s",
                 "candidateId":"%s","idempotencyKey":"%s"%s}
                """.formatted(planId, candidateId, idempotencyKey,
                extra ? ",\"expiresAt\":\"2099-01-01T00:00:00Z\"" : "");
    }

    private String activationBody(UUID planId, UUID candidateId, UUID authorizationId,
                                  UUID idempotencyKey, boolean extra) {
        return """
                {"operation":"ACTIVATE_CODEX_UPDATE","planId":"%s",
                 "candidateId":"%s","authorizationId":"%s","idempotencyKey":"%s"%s}
                """.formatted(planId, candidateId, authorizationId, idempotencyKey,
                extra ? ",\"service\":\"foreign.service\"" : "");
    }

    private RemoteWorkerClient.CodexUpdateStage stageResult(
            UUID planId, UUID candidateId, UUID idempotencyKey, String releaseDigest) {
        String proof = "a".repeat(64);
        return new RemoteWorkerClient.CodexUpdateStage(
                "codex-update-stage-v1", "STAGE_CODEX_UPDATE", WORKER_ID,
                planId, candidateId, idempotencyKey, "STAGED", "0.146.0",
                releaseDigest, CATALOG, proof, proof,
                "PASS", "PASS", "PASS", proof, proof, false, false);
    }

    private RemoteWorkerClient.CodexUpdateActivation activationResult(
            UUID planId, UUID candidateId, UUID authorizationId, UUID idempotencyKey) {
        String proof = "c".repeat(64);
        return new RemoteWorkerClient.CodexUpdateActivation(
                "codex-update-activate-v1", "ACTIVATE_CODEX_UPDATE", WORKER_ID,
                planId, candidateId, authorizationId, idempotencyKey, "ACTIVATED",
                "0.146.0", "3".repeat(64), CATALOG,
                "PASS", "PASS", "PASS", "PASS", proof, proof, proof, proof,
                "NOT_REQUIRED", false);
    }

    private record Inventory(UUID current, UUID previous, UUID candidate) {}
}
