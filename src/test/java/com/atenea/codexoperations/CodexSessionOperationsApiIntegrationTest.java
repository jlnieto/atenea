package com.atenea.codexoperations;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorPushDeviceEntity;
import com.atenea.persistence.auth.OperatorPushDeviceRepository;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunProgressCategory;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.CodexReasoningEffort;
import com.atenea.persistence.worksession.ExecutionProfileSource;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkloadClass;
import com.atenea.service.worksession.AgentRunProgressService;
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
        "atenea.codex-session-operations.profiles-enabled=true",
        "atenea.codex-session-operations.progress-enabled=true",
        "atenea.codex-session-operations.recovery-enabled=true",
        "atenea.codex-session-operations.notification-outbox-enabled=true",
        "atenea.codex-session-operations.managed-updates-enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class CodexSessionOperationsApiIntegrationTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private OperatorPushDeviceRepository deviceRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkSessionRepository sessionRepository;
    @Autowired private SessionTurnRepository turnRepository;
    @Autowired private AgentRunRepository runRepository;
    @Autowired private AgentRunProgressService progressService;

    @Test
    void requiresAuthenticationForEveryOperationsApi() throws Exception {
        mockMvc.perform(get("/api/codex/catalog"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/codex/inventory"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/codex/workers/ax42-01/inventory"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/codex/update-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsCurrentCatalogWithoutWorkerEndpointOrCredentials() throws Exception {
        Fixture fixture = fixture(CodexOperationsRole.ROUTINE_OPERATOR, AgentRunStatus.RUNNING);
        catalog();

        mockMvc.perform(get("/api/codex/catalog").with(auth(fixture.operator())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value("ax42-01"))
                .andExpect(jsonPath("$.codexVersion").value("0.145.0"))
                .andExpect(jsonPath("$.models[0].modelId").value("gpt-5.6-sol"))
                .andExpect(jsonPath("$.models[0].efforts[0]").value("medium"))
                .andExpect(jsonPath("$.endpoint").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void updatesOnlyFutureSessionSettingsFromExactCatalogAndRejectsExtraAuthority() throws Exception {
        Fixture fixture = fixture(CodexOperationsRole.ROUTINE_OPERATOR, AgentRunStatus.RUNNING);
        catalog();

        mockMvc.perform(put("/api/sessions/{id}/codex-settings", fixture.session().getId())
                        .with(auth(fixture.operator())).contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelId").value("gpt-5.6-sol"))
                .andExpect(jsonPath("$.reasoningEffort").value("medium"));

        AgentRunEntity historical = runRepository.findById(fixture.run().getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("gpt-5.6-terra", historical.getCodexModelId());

        mockMvc.perform(put("/api/sessions/{id}/codex-settings", fixture.session().getId())
                        .with(auth(fixture.operator())).contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson(true)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exposesImmutableRunDetailAndDurableProgressReplay() throws Exception {
        Fixture fixture = fixture(CodexOperationsRole.ROUTINE_OPERATOR, AgentRunStatus.RUNNING);
        progressService.append(fixture.run().getId(), AgentRunProgressCategory.ACCEPTED);
        progressService.append(fixture.run().getId(), AgentRunProgressCategory.CHECKING);

        mockMvc.perform(get("/api/runs/{id}/codex-detail", fixture.run().getId())
                        .with(auth(fixture.operator())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelId").value("gpt-5.6-terra"))
                .andExpect(jsonPath("$.modelSource").value("PROJECT"))
                .andExpect(jsonPath("$.latestSequence").value(2));
        mockMvc.perform(get("/api/runs/{id}/progress?afterSequence=1", fixture.run().getId())
                        .with(auth(fixture.operator())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].category").value("CHECKING"))
                .andExpect(jsonPath("$.events[0].message").value("Comprobando el resultado"));
    }

    @Test
    void persistsRecoveryIdempotentlyAndRejectsClosedRequestExtensions() throws Exception {
        Fixture fixture = fixture(CodexOperationsRole.ROUTINE_OPERATOR, AgentRunStatus.RUNNING);
        UUID key = UUID.randomUUID();
        String body = """
                {"workSessionId":%d,"action":"CANCEL","idempotencyKey":"%s"}
                """.formatted(fixture.session().getId(), key);

        String first = mockMvc.perform(post("/api/runs/{id}/recovery", fixture.run().getId())
                        .with(auth(fixture.operator())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/runs/{id}/recovery", fixture.run().getId())
                        .with(auth(fixture.operator())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(first, second);

        mockMvc.perform(post("/api/runs/{id}/recovery", fixture.run().getId())
                        .with(auth(fixture.operator())).contentType(MediaType.APPLICATION_JSON)
                        .content(body.substring(0, body.lastIndexOf('}')) + ",\"host\":\"ax42\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preferencesAreDefaultEnabledExplicitAndOwnedByOperator() throws Exception {
        Fixture fixture = fixture(CodexOperationsRole.ROUTINE_OPERATOR, AgentRunStatus.RUNNING);
        OperatorPushDeviceEntity device = device(fixture.operator());
        Fixture foreign = fixture(CodexOperationsRole.ROUTINE_OPERATOR, AgentRunStatus.RUNNING);

        mockMvc.perform(get("/api/mobile/notifications/devices/{id}/preferences", device.getId())
                        .with(auth(fixture.operator())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].explicit").value(false));
        mockMvc.perform(put("/api/mobile/notifications/devices/{id}/preferences", device.getId())
                        .with(auth(fixture.operator())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"RUN_COMPLETED\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.explicit").value(true));
        mockMvc.perform(get("/api/mobile/notifications/devices/{id}/preferences", device.getId())
                        .with(auth(foreign.operator())))
                .andExpect(status().isNotFound());
    }

    @Test
    void administratorInventoryIsRoleScopedAndShowsDefaultFalseUpdateGate() throws Exception {
        Fixture routine = fixture(CodexOperationsRole.ROUTINE_OPERATOR, AgentRunStatus.RUNNING);
        Fixture admin = fixture(CodexOperationsRole.PLATFORM_ADMINISTRATOR, AgentRunStatus.RUNNING);
        catalog();

        mockMvc.perform(get("/api/admin/codex/inventory").with(auth(routine.operator())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/codex/inventory").with(auth(admin.operator())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managedUpdatesEnabled").value(false))
                .andExpect(jsonPath("$.workers[0].workerId").value("ax42-01"))
                .andExpect(jsonPath("$.workers[0].endpoint").doesNotExist());

        mockMvc.perform(get("/api/codex/workers/ax42-01/inventory").with(auth(routine.operator())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value("ax42-01"))
                .andExpect(jsonPath("$.installedVersions").isArray())
                .andExpect(jsonPath("$.compatibilityState").value("WORKER_UNHEALTHY"))
                .andExpect(jsonPath("$.endpoint").doesNotExist());

        mockMvc.perform(post("/api/admin/codex/update-plans")
                        .with(auth(admin.operator())).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operation":"PLAN_CODEX_UPDATE","workerId":"ax42-01",
                                 "idempotencyKey":"11111111-1111-4111-8111-111111111111"}
                                """))
                .andExpect(status().isNotFound());
    }

    private RequestPostProcessor auth(OperatorEntity operator) {
        AuthenticatedOperator principal = new AuthenticatedOperator(operator.getId(), operator.getEmail(),
                operator.getDisplayName());
        return authentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }

    private String profileJson(boolean extra) {
        String suffix = extra ? ",\"provider\":\"arbitrary\"" : "";
        return """
                {"modelId":"gpt-5.6-sol","reasoningEffort":"medium",
                 "catalogRevision":"%s","idempotencyKey":"%s"%s}
                """.formatted("b".repeat(64), UUID.randomUUID(), suffix);
    }

    private void catalog() {
        jdbcTemplate.update("""
                INSERT INTO worker_node (id, protocol_version, endpoint, enabled, healthy,
                    normal_capacity, heavy_capacity, capabilities, created_at, updated_at)
                VALUES ('ax42-01','agent-run-worker/v1','https://worker.invalid',false,true,
                    4,2,'project-codex-v2',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """);
        jdbcTemplate.update("""
                INSERT INTO worker_codex_catalog (worker_id,catalog_revision,schema_version,
                    codex_version,generated_at,observed_at)
                VALUES ('ax42-01',?,'codex-model-catalog-v1','0.145.0',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON CONFLICT DO NOTHING
                """, "b".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO worker_codex_model (worker_id,catalog_revision,model_id,display_name,
                    default_effort,availability,position)
                VALUES ('ax42-01',?,'gpt-5.6-sol','GPT-5.6 Sol','medium','AVAILABLE',0)
                ON CONFLICT DO NOTHING
                """, "b".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO worker_codex_model_effort (worker_id,catalog_revision,model_id,effort,position)
                VALUES ('ax42-01',?,'gpt-5.6-sol','medium',0) ON CONFLICT DO NOTHING
                """, "b".repeat(64));
        jdbcTemplate.execute("SET CONSTRAINTS fk_worker_codex_model_default_effort IMMEDIATE");
    }

    private Fixture fixture(CodexOperationsRole role, AgentRunStatus status) {
        long value = SEQUENCE.incrementAndGet();
        Instant now = Instant.parse("2026-07-31T10:00:00Z");
        OperatorEntity operator = new OperatorEntity();
        operator.setEmail("api-" + value + "@atenea.test"); operator.setDisplayName("API operator");
        operator.setPasswordHash("synthetic-hash"); operator.setActive(true);
        operator.setCodexOperationsRole(role); operator.setCreatedAt(now); operator.setUpdatedAt(now);
        operator = operatorRepository.save(operator);
        ProjectEntity project = new ProjectEntity();
        project.setName("api-project-" + value); project.setRepoPath("/workspace/repos/internal/api-" + value);
        project.setDefaultBaseBranch("main"); project.setCreatedAt(now); project.setUpdatedAt(now);
        project = projectRepository.save(project);
        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(project); session.setStatus(WorkSessionStatus.OPEN); session.setTitle("API fixture");
        session.setBaseBranch("main"); session.setExecutionTarget(ExecutionTarget.LOCAL);
        session.setWorkspaceIdentity("local:api:" + value); session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now); session.setLastActivityAt(now); session.setCreatedAt(now); session.setUpdatedAt(now);
        session = sessionRepository.save(session);
        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setSession(session); turn.setActor(SessionTurnActor.OPERATOR);
        turn.setMessageText("Sensitive synthetic conversation content"); turn.setCreatedAt(now);
        turn = turnRepository.save(turn);
        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session); run.setOriginTurn(turn); run.setStatus(status);
        run.setTargetRepoPath(project.getRepoPath()); run.setExecutionTarget(ExecutionTarget.LOCAL);
        run.setWorkspaceIdentity(session.getWorkspaceIdentity()); run.setWorkloadClass(WorkloadClass.NORMAL);
        run.setStartedAt(now); run.setCreatedAt(now);
        if (status.isTerminal()) run.setFinishedAt(now.plusSeconds(30));
        run.setCodexModelId("gpt-5.6-terra"); run.setCodexModelSource(ExecutionProfileSource.PROJECT);
        run.setCodexReasoningEffort(CodexReasoningEffort.HIGH); run.setCodexEffortSource(ExecutionProfileSource.PROJECT);
        run.setCodexCatalogRevision("a".repeat(64)); run.setCodexVersion("0.145.0");
        run = runRepository.saveAndFlush(run);
        return new Fixture(operator, session, turn, run);
    }

    private OperatorPushDeviceEntity device(OperatorEntity operator) {
        Instant now = Instant.parse("2026-07-31T10:00:00Z");
        OperatorPushDeviceEntity device = new OperatorPushDeviceEntity();
        device.setOperator(operator); device.setPushToken("synthetic-api-device-" + SEQUENCE.incrementAndGet());
        device.setDeviceId("api-device"); device.setDeviceName("API test device");
        device.setPlatform("ANDROID"); device.setAppVersion("test"); device.setActive(true);
        device.setLastRegisteredAt(now); device.setCreatedAt(now); device.setUpdatedAt(now);
        return deviceRepository.save(device);
    }

    private record Fixture(OperatorEntity operator, WorkSessionEntity session,
                           SessionTurnEntity turn, AgentRunEntity run) {}
}
