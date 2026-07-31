package com.atenea.codexoperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

    private record Inventory(UUID current, UUID previous, UUID candidate) {}
}
