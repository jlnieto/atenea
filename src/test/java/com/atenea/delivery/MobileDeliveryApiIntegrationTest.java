package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.action.PrivilegedActionAuthorizationService;
import com.atenea.auth.recovery.OperatorRecoveryService;
import com.atenea.github.GitHubClient;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkSessionAcceptanceState;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.service.worksession.DevelopmentChangeBranchPublicationService;
import com.atenea.service.worksession.WorkSessionGitHubService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"atenea.auth.bootstrap.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class MobileDeliveryApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OperatorRepository operators;
    @Autowired ProjectRepository projects;
    @Autowired WorkSessionRepository sessions;
    @Autowired DevelopmentChangeRepository changes;
    @Autowired MobileDeliveryService delivery;
    @Autowired DeliveryStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean ReleaseControlClient executor;
    @MockBean GitHubClient github;
    @MockBean DevelopmentChangeBranchPublicationService ownership;
    @MockBean WorkSessionGitHubService publication;
    @MockBean OperatorRecoveryService factors;
    @MockBean PrivilegedActionAuthorizationService grants;
    OperatorEntity admin, routine;
    Long sessionId;
    @BeforeEach void fixture() {
        when(executor.enabled()).thenReturn(true);
        admin=operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        routine=operator(CodexOperationsRole.ROUTINE_OPERATOR);
        ProjectEntity project=new ProjectEntity(); project.setName("Delivery API "+UUID.randomUUID());
        project.setRepoPath("/synthetic"); project.setDefaultBaseBranch("main");
        project.setCreatedAt(Instant.now()); project.setUpdatedAt(Instant.now()); projects.saveAndFlush(project);
        WorkSessionEntity session=new WorkSessionEntity(); session.setProject(project); session.setStatus(WorkSessionStatus.OPEN);
        session.setPullRequestStatus(com.atenea.persistence.worksession.WorkSessionPullRequestStatus.NOT_CREATED);
        session.setTitle("Isolated delivery API"); session.setBaseBranch("main"); session.setWorkspaceIdentity("local:test:"+UUID.randomUUID());
        session.setOpenedAt(Instant.now()); session.setLastActivityAt(Instant.now());
        session.setCreatedAt(Instant.now()); session.setUpdatedAt(Instant.now()); sessionId=sessions.saveAndFlush(session).getId();
    }
    private OperatorEntity operator(CodexOperationsRole role) {
        OperatorEntity value=new OperatorEntity(); value.setEmail(UUID.randomUUID()+"@atenea.test");
        value.setDisplayName("Test"); value.setPasswordHash("synthetic"); value.setActive(true);
        value.setCodexOperationsRole(role); value.setCreatedAt(Instant.now()); value.setUpdatedAt(Instant.now());
        return operators.saveAndFlush(value);
    }
    private RequestPostProcessor auth(OperatorEntity actor) {
        var token=new UsernamePasswordAuthenticationToken(new AuthenticatedOperator(actor.getId(),actor.getEmail(),"Test"),
                null,List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        token.setDetails(UUID.randomUUID()); return authentication(token);
    }
    @Test void effectiveRoutineRoleCannotQueueDelivery() throws Exception {
        mvc.perform(post("/api/mobile/sessions/{id}/delivery/pr",sessionId).with(auth(routine))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation",Integer.class));
    }
    @Test void unknownPathsCommandsShaAndHostsCannotEnterProtocol() throws Exception {
        for (String key:List.of("sourceCommit","command","path","host","token")) {
            mvc.perform(post("/api/mobile/sessions/{id}/delivery/pr",sessionId).with(auth(admin))
                    .contentType(MediaType.APPLICATION_JSON).content("{\""+key+"\":\"foreign\"}"))
                    .andExpect(status().isConflict());
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation",Integer.class));
    }
    @Test void doubleTapQueuesOneDurableIntentWithoutDispatchingFromHttpRequest() throws Exception {
        String first=mvc.perform(post("/api/mobile/sessions/{id}/delivery/pr",sessionId).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(first).path("id").asText();
        mvc.perform(post("/api/mobile/sessions/{id}/delivery/pr",sessionId).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation",Integer.class));
        verify(executor,never()).execute(any(),any(),any()); verifyNoInteractions(publication);
    }
    @Test void readOnlyPollingDoesNotConsumeFactorOrDispatch() throws Exception {
        var op=store.create(sessionId,admin.getId(),"RELEASE",DeliveryTarget.APP_PROD,"1".repeat(40),"PREPARING",mapper.createObjectNode());
        mvc.perform(get("/api/mobile/sessions/{id}/delivery",sessionId).with(auth(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.operations[0].id").value(op.id().toString()))
                .andExpect(jsonPath("$.operations[0].state").value("PREPARING"))
                .andExpect(jsonPath("$.integration.mergeState").value("NOT_PUBLISHED"))
                .andExpect(jsonPath("$.integration.canRequestIntegration").value(false))
                .andExpect(jsonPath("$.operations[0].path").doesNotExist()).andExpect(jsonPath("$.operations[0].token").doesNotExist());
        verifyNoInteractions(factors,grants);
        verify(executor,never()).inspect(any());
        verifyNoInteractions(github,publication);
    }
    @Test void unpreparedPlanCannotConsumeTotpAndUnknownArgumentsAreRejected() throws Exception {
        var op=store.create(sessionId,admin.getId(),"RELEASE",DeliveryTarget.APP_PROD,"1".repeat(40),"PREPARING",mapper.createObjectNode());
        mvc.perform(post("/api/mobile/delivery/{id}/authorize",op.id()).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"totp\":\"123456\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/mobile/delivery/{id}/confirm",op.id()).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"authorization\":\""+UUID.randomUUID()+"\",\"command\":\"foreign\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(factors,grants);
    }
    @Test void oldUfdClaimIsFoundBeyondUiWindowAndOnlyForExactOwnedHead() {
        String head="1".repeat(40),branch="atenea/change-59315b6e-59bc-4884-9def-356e1ca86ef4";
        var evidence=mapper.createObjectNode();
        evidence.putObject("ufdDispatch").put("headSha",head).put("headBranch",branch)
                .put("requestId",GitHubClient.ufdRequestId(head,branch).toString()).put("authoritySha","2".repeat(40))
                .put("status","UNCONFIRMED").put("claimedAt",1);
        var old=store.create(sessionId,admin.getId(),"PUBLISH_PR",DeliveryTarget.APP_PROD,null,"BLOCKED",evidence);
        jdbc.update("UPDATE mobile_delivery_operation SET created_at=now()-interval '1 hour' WHERE id=?",old.id());
        for (int i=0;i<31;i++) store.create(sessionId,admin.getId(),"PUBLISH_PR",DeliveryTarget.APP_PROD,null,"BLOCKED",mapper.createObjectNode());
        assertFalse(store.list(sessionId).stream().anyMatch(op->op.id().equals(old.id())));
        assertEquals(old.id(),store.ownedHeadUfdRequest(sessionId,head,branch).orElseThrow().id());
        assertTrue(store.ownedHeadUfdRequest(sessionId,"3".repeat(40),branch).isEmpty());
        assertTrue(store.ownedHeadUfdRequest(sessionId,head,branch+"-foreign").isEmpty());
    }

    @Test void mergedGithubResultPersistsValidAcceptanceAndSameDurableIntegrationReceipt() {
        publishedValidatedFixture();
        var op=store.create(sessionId,admin.getId(),"INTEGRATE",DeliveryTarget.APP_PROD,"1".repeat(40),
                "WAITING_CI",mapper.createObjectNode());
        when(github.extractPullRequestNumber(anyString())).thenReturn(42L);
        // This is also the replay result when GitHub merged before the former
        // App transaction failed: observe that same merge, not another PR.
        when(github.integrateExact(any(),eq(42L),anyString(),eq("1".repeat(40))))
                .thenReturn("3".repeat(40));

        delivery.reconcile();

        assertEquals("INTEGRATION_READY",jdbc.queryForObject(
                "SELECT acceptance_state FROM work_session WHERE id=?",String.class,sessionId));
        assertEquals("MERGED",jdbc.queryForObject(
                "SELECT pull_request_status FROM work_session WHERE id=?",String.class,sessionId));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM work_session WHERE id=? "
                +"AND validated_at IS NOT NULL AND integration_ready_at IS NOT NULL "
                +"AND validation_projection_sha256=? AND validation_definition_revision='test-v1'",
                Integer.class,sessionId,"6".repeat(64)));
        var receipt=store.get(op.id(),false);
        assertEquals("SUCCEEDED",receipt.state());
        assertEquals("3".repeat(40),receipt.evidence().path("mergeCommit").asText());
        delivery.reconcile();
        assertEquals(op.id(),store.integrated(sessionId,"1".repeat(40)).orElseThrow().id());
        verify(github,times(1)).integrateExact(any(),anyLong(),anyString(),anyString());
        verify(executor,never()).execute(any(),any(),any());
        verify(executor,never()).plan(any(),any(),any());
        verifyNoInteractions(factors,grants,publication);

        // Readiness is not a new source authority: the original merged SHA
        // must still be canonical before a production plan can be requested.
        when(github.canonicalMain(any())).thenReturn("3".repeat(40));
        var plan=delivery.request(sessionId,new AuthenticatedOperator(admin.getId(),admin.getEmail(),"Test"),
                "RELEASE",DeliveryTarget.APP_PROD);
        assertEquals("PLANNING",plan.state());
        assertEquals("3".repeat(40),plan.sourceCommit());
        verify(ownership).requireExactIntegratedOwner(any());
        verify(executor,never()).execute(any(),any(),any());
        verify(executor,never()).plan(any(),any(),any());
        verifyNoInteractions(factors,grants,publication);
    }

    @Test void pendingGithubChecksDoNotPrematurelyPromoteAcceptance() {
        publishedValidatedFixture();
        var op=store.create(sessionId,admin.getId(),"INTEGRATE",DeliveryTarget.APP_PROD,"1".repeat(40),
                "QUEUED",mapper.createObjectNode());
        when(github.extractPullRequestNumber(anyString())).thenReturn(42L);
        when(github.integrateExact(any(),anyLong(),anyString(),anyString()))
                .thenThrow(new com.atenea.github.GitHubIntegrationException("CI_PENDING: exact owned head"));
        delivery.reconcile();
        assertEquals("WAITING_CI",store.get(op.id(),false).state());
        assertEquals("VALIDATED",jdbc.queryForObject(
                "SELECT acceptance_state FROM work_session WHERE id=?",String.class,sessionId));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM work_session WHERE id=? "
                +"AND integration_ready_at IS NOT NULL",Integer.class,sessionId));
    }

    @Test void recoveryDoubleTapCreatesOnePinnedPlanAndRetainsProofAcrossExecutorReceipts() throws Exception {
        publishedValidatedFixture();
        WorkSessionEntity session=sessions.findById(sessionId).orElseThrow();
        session.setAcceptanceState(WorkSessionAcceptanceState.INTEGRATION_READY);
        session.setIntegrationReadyAt(Instant.now()); session.setPullRequestStatus(WorkSessionPullRequestStatus.MERGED);
        sessions.saveAndFlush(session);
        var integrated=store.create(sessionId,admin.getId(),"INTEGRATE",DeliveryTarget.APP_PROD,"1".repeat(40),
                "SUCCEEDED",mapper.createObjectNode().put("mergeCommit","3".repeat(40)));
        when(github.canonicalMain(any())).thenReturn("4".repeat(40));
        when(github.extractPullRequestNumber(anyString())).thenReturn(42L);
        String response=mvc.perform(post("/api/mobile/sessions/{id}/delivery/release-recovery-plan",sessionId).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PLANNING"))
                .andExpect(jsonPath("$.sourceCommit").value("4".repeat(40)))
                .andExpect(jsonPath("$.releaseRecovery.integratedMergeCommit").value("3".repeat(40)))
                .andExpect(jsonPath("$.releaseRecovery.integrationOperationId").value(integrated.id().toString()))
                .andReturn().getResponse().getContentAsString();
        UUID id=UUID.fromString(mapper.readTree(response).path("id").asText());
        when(github.canonicalMain(any())).thenReturn("5".repeat(40));
        mvc.perform(post("/api/mobile/sessions/{id}/delivery/release-recovery-plan",sessionId).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.sourceCommit").value("4".repeat(40)));
        var before=store.get(id,false);
        store.update(before,"READY",null,"2".repeat(64),mapper.createObjectNode().put("expiresAt",Long.MAX_VALUE));
        var after=store.get(id,false);
        assertEquals(before.evidence().path("releaseRecovery"),after.evidence().path("releaseRecovery"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation WHERE kind='RELEASE'",Integer.class));
        verify(executor,never()).execute(any(),any(),any()); verifyNoInteractions(factors,grants);
    }

    @Test void recoveryRejectsRoutineRoleAndCallerSelectedSourcePathsCommandsOrTarget() throws Exception {
        mvc.perform(post("/api/mobile/sessions/{id}/delivery/release-recovery-plan",sessionId).with(auth(routine))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        for (String key:List.of("sourceCommit","command","path","host","target","version")) {
            mvc.perform(post("/api/mobile/sessions/{id}/delivery/release-recovery-plan",sessionId).with(auth(admin))
                    .contentType(MediaType.APPLICATION_JSON).content("{\""+key+"\":\"foreign\"}"))
                    .andExpect(status().isConflict());
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation",Integer.class));
        verify(executor,never()).execute(any(),any(),any()); verifyNoInteractions(factors,grants);
    }

    private void publishedValidatedFixture() {
        WorkSessionEntity session=sessions.findById(sessionId).orElseThrow();
        Instant now=Instant.now();
        DevelopmentChangeEntity change=new DevelopmentChangeEntity();
        change.setChangeKey(UUID.randomUUID()); change.setProject(session.getProject()); change.setTitle("Isolated owned delivery");
        change.setBaseRef("refs/heads/main"); change.setBaseCommit("2".repeat(40));
        change.setObservedCanonicalCommit("2".repeat(40));
        change.setWorkspaceBranch("atenea/change-"+change.getChangeKey());
        change.setWorkspaceIdentity("remote:ax42-01:change:"+change.getChangeKey()); change.setSelectedWorkerId("ax42-01");
        change.setProjectPolicyRevision(1);
        change.setSourceRevision(2); change.setSourceFingerprintSha256("5".repeat(64));
        change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        change.setWorkspaceOperationRevision(1); change.setWorkspaceObservationSha256("9".repeat(64));
        change.setWorkspaceUpdatedAt(now);
        change.setValidationState(DevelopmentChangeProjectionState.CURRENT);
        change.setCreatedAt(now); change.setUpdatedAt(now); changes.saveAndFlush(change);
        session.setDevelopmentChange(change); session.setWorkspaceBranch(change.getWorkspaceBranch());
        session.setSourceTreeFingerprintSha256(change.getSourceFingerprintSha256()); session.setSourceTreeObservedAt(now);
        session.setAcceptanceState(WorkSessionAcceptanceState.VALIDATED); session.setValidatedAt(now);
        session.setValidationProjectionSha256("6".repeat(64)); session.setValidationDefinitionRevision("test-v1");
        session.setPublishedChangeKey(change.getChangeKey()); session.setPublishedSourceRevision(2L);
        session.setPublishedSourceFingerprintSha256(change.getSourceFingerprintSha256());
        session.setPublishedWorkspaceOwnershipFingerprintSha256("7".repeat(64));
        session.setPublishedRepository("jlnieto/atenea"); session.setPublishedBaseBranch("main");
        session.setPublishedHeadBranch(change.getWorkspaceBranch()); session.setPublicationReceiptSha256("8".repeat(64));
        session.setFinalCommitSha("1".repeat(40)); session.setPullRequestUrl("https://github.com/jlnieto/atenea/pull/42");
        session.setPullRequestStatus(WorkSessionPullRequestStatus.OPEN); sessions.saveAndFlush(session);
    }
}
