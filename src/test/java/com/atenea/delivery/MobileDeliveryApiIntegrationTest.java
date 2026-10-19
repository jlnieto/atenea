package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.support.ChangeOwnedSessionFixtures;
import com.atenea.auth.action.PrivilegedActionAuthorizationService;
import com.atenea.auth.webauthn.WebAuthnService;
import com.atenea.auth.webauthn.WebAuthnOptionsResponse;
import com.atenea.auth.webauthn.WebAuthnChannel;
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
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @MockBean ReleaseControlClient executor;
    @MockBean GitHubClient github;
    @MockBean DevelopmentChangeBranchPublicationService ownership;
    @MockBean WorkSessionGitHubService publication;
    @MockBean OperatorRecoveryService factors;
    @MockBean PrivilegedActionAuthorizationService grants;
    @MockBean WebAuthnService passkeys;
    @MockBean ReleasePasskeyTrustStore trust;
    OperatorEntity admin, routine;
    Long sessionId;
    @BeforeEach void fixture() {
        when(executor.enabled()).thenReturn(true);
        admin=operator(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        routine=operator(CodexOperationsRole.ROUTINE_OPERATOR);
        sessionId=createSession(false);
    }
    @Test void buildRepairRequiresAdministratorAndRejectsCallerAuthority() throws Exception {
        mvc.perform(post("/api/mobile/sessions/{id}/delivery/build-repair",sessionId).with(auth(routine))
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().is4xxClientError());
        for(String body:List.of("{\"command\":\"anything\"}","{\"sourceCommit\":\"main\"}","{\"path\":\"/tmp\"}","{\"runId\":91}"))
            mvc.perform(post("/api/mobile/sessions/{id}/delivery/build-repair",sessionId).with(auth(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is4xxClientError());
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM mobile_release_build_repair WHERE session_id=?",Long.class,sessionId));
    }
    private Long createSession(boolean remote) {
        ProjectEntity project=new ProjectEntity(); project.setName("Delivery API "+UUID.randomUUID());
        project.setRepoPath("/synthetic"); project.setDefaultBaseBranch("main");
        project.setCreatedAt(Instant.now()); project.setUpdatedAt(Instant.now()); projects.saveAndFlush(project);
        WorkSessionEntity session=new WorkSessionEntity(); session.setProject(project); session.setStatus(WorkSessionStatus.OPEN);
        session.setPullRequestStatus(com.atenea.persistence.worksession.WorkSessionPullRequestStatus.NOT_CREATED);
        session.setTitle("Isolated delivery API"); session.setBaseBranch("main"); session.setWorkspaceIdentity("local:test:"+UUID.randomUUID());
        session.setOpenedAt(Instant.now()); session.setLastActivityAt(Instant.now());
        session.setCreatedAt(Instant.now()); session.setUpdatedAt(Instant.now());
        if (remote) {
            jdbc.update("""
                INSERT INTO worker_node(id,protocol_version,endpoint,enabled,healthy,normal_capacity,heavy_capacity,capabilities)
                VALUES ('synthetic-fixture-worker','agent-run-worker/v1','http://synthetic.invalid',true,true,1,1,'project-codex-v4')
                ON CONFLICT (id) DO NOTHING
                """);
            session.setExecutionTarget(com.atenea.persistence.worksession.ExecutionTarget.REMOTE);
            session.setSelectedWorkerId("synthetic-fixture-worker");
            session.setRemoteSessionId(UUID.randomUUID());
            session.setRemoteWorkloadKind(com.atenea.remoteworker.ProjectCodexIdentity.WORKLOAD_KIND);
            session.setRemoteCloseState(com.atenea.persistence.worksession.RemoteCloseState.NOT_STARTED);
        }
        return sessions.saveAndFlush(ChangeOwnedSessionFixtures.bind(session, changes)).getId();
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

    @Test void preparationAndValidationReferToTheSameCurrentRevisionWithoutReusingOldFlags() throws Exception {
        sessionId=createSession(true);
        var session=sessions.findById(sessionId).orElseThrow();
        var change=session.getDevelopmentChange();
        change.setSourceRevision(5);
        change.setSourceFingerprintSha256("5".repeat(64));
        change.setValidationState(DevelopmentChangeProjectionState.STALE);
        changes.saveAndFlush(change);
        session.setAcceptanceState(WorkSessionAcceptanceState.DRAFT);
        sessions.saveAndFlush(session);
        mvc.perform(get("/api/mobile/sessions/{id}/delivery",sessionId).with(auth(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validation.sourceRevision").value(5))
                .andExpect(jsonPath("$.validation.sourceFingerprintSha256").value("5".repeat(64)))
                .andExpect(jsonPath("$.validation.validationState").value("STALE"))
                .andExpect(jsonPath("$.validation.passedOperations").value(0))
                .andExpect(jsonPath("$.validation.requiredOperations").value(4));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM validation_operation",Integer.class));
        verifyNoInteractions(publication,factors,grants);
        verify(executor,never()).execute(any(),any(),any());
    }

    @Test void queuedWorkerProgressIsPersistedReadOnlyWithItsRevisionNotInferredFromSummary() throws Exception {
        sessionId=createSession(true);
        var change=sessions.findById(sessionId).orElseThrow().getDevelopmentChange();
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO validation_operation(id,work_session_id,operation,status,source_tree_fingerprint_sha256,definition_revision,
                identity_sha256,summary,started_at,created_at,updated_at,worker_state,worker_observed_at)
            VALUES (?,?,'ANDROID_BUILD','RUNNING',?,'atenea-android-build-v2',?,'Not used to infer a phase',now(),now(),now(),'QUEUED',now())
            """,id,sessionId,change.getSourceFingerprintSha256(),"c".repeat(64));
        String before=jdbc.queryForObject("SELECT to_jsonb(v)::text FROM validation_operation v WHERE id=?",String.class,id);
        for (int i=0;i<2;i++) {
            mvc.perform(get("/api/mobile/sessions/{id}/delivery",sessionId).with(auth(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.validation.operations[0].id").value(id.toString()))
                    .andExpect(jsonPath("$.validation.operations[0].workerState").value("QUEUED"))
                    .andExpect(jsonPath("$.validation.operations[0].workerObservedAt").isNotEmpty())
                    .andExpect(jsonPath("$.validation.operations[0].workerStartedAt").isEmpty())
                    .andExpect(jsonPath("$.validation.operations[0].durationMillis").isEmpty());
        }
        assertEquals(before,jdbc.queryForObject("SELECT to_jsonb(v)::text FROM validation_operation v WHERE id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM validation_operation",Integer.class));
        verifyNoInteractions(publication,factors,grants);
        verify(executor,never()).execute(any(),any(),any());
    }

    @Test void unconfirmedValidationIdentityPreservesReadOnlyDurableHistoryButNeverInventsEvidence() throws Exception {
        var op=store.create(sessionId,admin.getId(),"PUBLISH_PR",DeliveryTarget.APP_PROD,null,"BLOCKED",mapper.createObjectNode());
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(transaction -> {
            assertDoesNotThrow(() -> mvc.perform(get("/api/mobile/sessions/{id}/delivery",sessionId).with(auth(admin)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.operations[0].id").value(op.id().toString()))
                    .andExpect(jsonPath("$.validation").isEmpty()));
            assertFalse(transaction.isRollbackOnly(),"Optional evidence must not poison the read-only snapshot transaction");
        });
        assertEquals("BLOCKED",store.get(op.id(),false).state());
        verifyNoInteractions(publication,factors,grants);
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

    @Test void passkeyOptionsAreClosedAuthenticatedAndUseTheSameReadyPlanWithoutPublishing() throws Exception {
        var op=store.create(sessionId,admin.getId(),"RELEASE",DeliveryTarget.APP_PROD,"1".repeat(40),"READY",
                mapper.createObjectNode().put("expiresAt",Instant.now().plusSeconds(600).getEpochSecond()));
        jdbc.update("UPDATE mobile_delivery_operation SET plan_sha256=? WHERE id=?","2".repeat(64),op.id());
        String endpoint="/api/mobile/delivery/"+op.id()+"/authorize/passkey/options";
        String origin="android:apk-key-hash:"+"A".repeat(43);
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint).with(auth(routine)).header("X-Atenea-Android-Origin",origin)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(endpoint).with(auth(admin)).header("X-Atenea-Android-Origin",origin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"sourceCommit\":\"foreign\"}")).andExpect(status().isConflict());
        when(passkeys.beginStepUp(any(),any(),eq(WebAuthnChannel.ANDROID),eq(origin),any())).thenReturn(
            new WebAuthnOptionsResponse(UUID.randomUUID(),"challenge",300_000,"atenea.yudri.es",null,null,null,
                List.of(),List.of(new WebAuthnOptionsResponse.CredentialDescriptor("public-key","credential",List.of("internal"))),"required",null,null));
        when(trust.trustedCredentialIds(admin.getId())).thenReturn(java.util.Set.of("credential"));
        mvc.perform(post(endpoint).with(auth(admin)).header("X-Atenea-Android-Origin",origin)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.userVerification").value("required"));
        verify(passkeys,times(1)).beginStepUp(any(),any(),eq(WebAuthnChannel.ANDROID),eq(origin),any());
        assertEquals("READY",store.get(op.id(),false).state());
        verifyNoInteractions(factors,grants);
        verify(executor,never()).execute(any(),any(),any());
    }

    @Test void passkeyCannotAcceptClientAuthorityUnpreparedExpiredOrForeignPlans() throws Exception {
        String origin="android:apk-key-hash:"+"A".repeat(43);
        var op=store.create(sessionId,admin.getId(),"RELEASE",DeliveryTarget.APP_PROD,"1".repeat(40),"PREPARING",mapper.createObjectNode());
        String endpoint="/api/mobile/delivery/"+op.id()+"/authorize/passkey";
        var body=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("credentialId","credential")
                .putNull("userHandle").put("clientDataJson","client").put("authenticatorData","data").put("signature","signature");
        for(String extra:List.of("command","path","host","sourceCommit","planSha256","authorization","userVerified")) {
            mvc.perform(post(endpoint).with(auth(admin)).header("X-Atenea-Android-Origin",origin)
                    .contentType(MediaType.APPLICATION_JSON).content(body.deepCopy().put(extra,"foreign").toString()))
                    .andExpect(status().isConflict());
        }
        mvc.perform(post(endpoint).with(auth(admin)).header("X-Atenea-Android-Origin",origin)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isConflict());
        jdbc.update("UPDATE mobile_delivery_operation SET state='READY',plan_sha256=?,evidence_json=?::jsonb WHERE id=?",
                "2".repeat(64),"{\"expiresAt\":1}",op.id());
        mvc.perform(post(endpoint).with(auth(admin)).header("X-Atenea-Android-Origin",origin)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isConflict());
        jdbc.update("UPDATE mobile_delivery_operation SET operator_id=?,evidence_json=?::jsonb WHERE id=?",routine.getId(),
                "{\"expiresAt\":"+Instant.now().plusSeconds(600).getEpochSecond()+"}",op.id());
        mvc.perform(post(endpoint).with(auth(admin)).header("X-Atenea-Android-Origin",origin)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isConflict());
        verifyNoInteractions(passkeys,factors,grants);
    }

    @Test void validTrustedPasskeyReturnsOnlyAnAuthorizationWithoutAcceptingOrExecutingTheRelease() throws Exception {
        var op=store.create(sessionId,admin.getId(),"RELEASE",DeliveryTarget.APP_PROD,"1".repeat(40),"READY",
                mapper.createObjectNode().put("expiresAt",Instant.now().plusSeconds(600).getEpochSecond()));
        jdbc.update("UPDATE mobile_delivery_operation SET plan_sha256=? WHERE id=?","2".repeat(64),op.id());
        var grant=new com.atenea.auth.action.PrivilegedActionAuthorizationGrant(UUID.randomUUID(),Instant.now().plusSeconds(60));
        when(trust.isTrusted(admin.getId(),"credential")).thenReturn(true);
        when(passkeys.completeStepUp(any(),any(),eq(WebAuthnChannel.ANDROID),any(),any(),any())).thenAnswer(call ->
            new com.atenea.auth.action.VerifiedStepUp(admin.getId(),call.getArgument(1),call.getArgument(4),
                com.atenea.auth.action.PrivilegedActionFactor.WEBAUTHN,Instant.now()));
        when(grants.issueVerified(any())).thenReturn(grant);
        var body=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("credentialId","credential")
                .put("userHandle","user").put("clientDataJson","client").put("authenticatorData","data").put("signature","signature");
        mvc.perform(post("/api/mobile/delivery/{id}/authorize/passkey",op.id()).with(auth(admin))
                .header("X-Atenea-Android-Origin","android:apk-key-hash:"+"A".repeat(43))
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization").value(grant.authorization().toString()));
        assertEquals("READY",store.get(op.id(),false).state());
        verify(grants,never()).consumeForAcceptance(any(),any(),any(),any());
        verifyNoInteractions(factors);
        verify(executor,never()).execute(any(),any(),any());
    }

    @Test void passkeySetupRequiresBothClosedAssertionAndAuthenticatorNotSessionAlone() throws Exception {
        var body=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("credentialId","credential")
                .putNull("userHandle").put("clientDataJson","client").put("authenticatorData","data").put("signature","signature");
        mvc.perform(post("/api/mobile/release-passkeys/setup").with(auth(admin))
                .header("X-Atenea-Android-Origin","android:apk-key-hash:"+"A".repeat(43))
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isConflict());
        mvc.perform(post("/api/mobile/release-passkeys/setup").with(auth(admin))
                .header("X-Atenea-Android-Origin","android:apk-key-hash:"+"A".repeat(43))
                .contentType(MediaType.APPLICATION_JSON).content(body.put("totp","123456").put("trusted",true).toString()))
                .andExpect(status().isConflict());
        verifyNoInteractions(passkeys,factors,grants);
    }

    @Test void passkeySetupStatusRequiresCurrentAdministratorAndRemainsReadOnly() throws Exception {
        String origin="android:apk-key-hash:"+"A".repeat(43);
        mvc.perform(get("/api/mobile/release-passkeys").header("X-Atenea-Android-Origin",origin))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mobile/release-passkeys").with(auth(routine)).header("X-Atenea-Android-Origin",origin))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/mobile/release-passkeys").with(auth(admin)).header("X-Atenea-Android-Origin",origin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation",Integer.class));
        verifyNoInteractions(passkeys,factors,grants);
        verify(executor,never()).execute(any(),any(),any());
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

    @Test void androidVersionEndpointsAreClosedAndRequireEffectiveAdministrator() throws Exception {
        for (String endpoint:List.of("/android-version","/android-version/"+UUID.randomUUID()+"/integrate")) {
            mvc.perform(post("/api/mobile/sessions/"+sessionId+"/delivery"+endpoint).with(auth(routine))
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
            for (String extra:List.of("versionCode","versionName","path","command","sha","branch"))
                mvc.perform(post("/api/mobile/sessions/"+sessionId+"/delivery"+endpoint).with(auth(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\""+extra+"\":\"foreign\"}"))
                        .andExpect(status().isConflict());
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation",Integer.class));
        verifyNoInteractions(publication,factors,grants);verify(executor,never()).execute(any(),any(),any());
    }

    @Test void androidVersionDoubleTapPersistsSingleIntentBoundToOriginalIntegrationAndDoesNotDispatchFromHttp() throws Exception {
        publishedValidatedFixture();
        var session=sessions.findById(sessionId).orElseThrow();session.setAcceptanceState(WorkSessionAcceptanceState.INTEGRATION_READY);
        session.setIntegrationReadyAt(Instant.now());session.setPullRequestStatus(WorkSessionPullRequestStatus.MERGED);sessions.saveAndFlush(session);
        var integrated=store.create(sessionId,admin.getId(),"INTEGRATE",DeliveryTarget.APP_PROD,"1".repeat(40),"SUCCEEDED",mapper.createObjectNode().put("mergeCommit","3".repeat(40)));
        when(github.canonicalMain(any())).thenReturn("4".repeat(40));when(github.extractPullRequestNumber(anyString())).thenReturn(42L);
        when(github.androidProductVersion("4".repeat(40))).thenReturn(new com.atenea.github.AndroidProductVersion(160,"0.5.127"));
        var channel=mapper.createObjectNode().put("protocol","atenea-android-observation/v1").put("target","ANDROID_STABLE").put("state","OBSERVED")
                .put("sourceCommit","2".repeat(40)).put("versionCode",160).put("versionName","0.5.127").put("apkSha256","a".repeat(64)).put("certificateSha256","b".repeat(64))
                .put("observedAt",Instant.now().getEpochSecond()).put("planId",UUID.randomUUID().toString()).put("operationId",UUID.randomUUID().toString()).put("receiptSha256","c".repeat(64)).put("finishedAt",1);
        when(executor.observeAndroid()).thenReturn(channel);
        UUID id=null;
        for (int i=0;i<2;i++) {
            var response=mvc.perform(post("/api/mobile/sessions/{id}/delivery/android-version",sessionId).with(auth(admin))
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.kind").value("ANDROID_VERSION")).andExpect(jsonPath("$.state").value("QUEUED"))
                    .andExpect(jsonPath("$.versionCode").value(161)).andExpect(jsonPath("$.versionName").value("0.5.128"))
                    .andExpect(jsonPath("$.releaseRecovery").isEmpty()).andReturn().getResponse().getContentAsString();
            UUID observed=UUID.fromString(mapper.readTree(response).path("id").asText());if (id==null) id=observed;else assertEquals(id,observed);
        }
        assertEquals(integrated.id().toString(),store.get(id,false).evidence().path("versionSource").path("integrationOperationId").asText());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation WHERE kind='ANDROID_VERSION'",Integer.class));
        verify(github,never()).prepareAndroidVersion(any(),any(),any(),any());verify(github,never()).integrateExact(any(),anyLong(),any(),any());
        verify(executor,never()).execute(any(),any(),any());verifyNoInteractions(publication,factors,grants);
    }

    @Test void pollingInstalledOperatorReleasePreservesDatabaseHistoryAndDoesNotAdoptPublication() throws Exception {
        publishedValidatedFixture();
        var session=sessions.findById(sessionId).orElseThrow();
        session.setAcceptanceState(WorkSessionAcceptanceState.INTEGRATION_READY);
        session.setIntegrationReadyAt(Instant.now()); session.setPullRequestStatus(WorkSessionPullRequestStatus.MERGED);
        sessions.saveAndFlush(session);
        var integrated=store.create(sessionId,admin.getId(),"INTEGRATE",DeliveryTarget.APP_PROD,"1".repeat(40),
                "SUCCEEDED",mapper.createObjectNode().put("mergeCommit","3".repeat(40)));
        UUID plan=UUID.randomUUID(),execution=UUID.randomUUID();
        when(executor.observeApp()).thenReturn(mapper.createObjectNode().put("protocol","atenea-app-observation/v1")
                .put("target","APP_PROD").put("state","OBSERVED").put("sourceCommit","4".repeat(40))
                .put("imageSha256","5".repeat(64)).put("healthy",true).put("observedAt",Instant.now().getEpochSecond())
                .put("planId",plan.toString()).put("operationId",execution.toString()).put("receiptSha256","6".repeat(64)).put("finishedAt",1L));
        when(github.extractPullRequestNumber(anyString())).thenReturn(42L);
        for (int i=0;i<2;i++) mvc.perform(get("/api/mobile/sessions/{id}/delivery",sessionId).with(auth(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deployment.status").value("DEPLOYED"))
                .andExpect(jsonPath("$.deployment.origin").value("OPERATOR"))
                .andExpect(jsonPath("$.deployment.planId").value(plan.toString()))
                .andExpect(jsonPath("$.operations[0].kind").value("INTEGRATE"));
        assertEquals(integrated,store.get(integrated.id(),false));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation WHERE kind='RELEASE'",Integer.class));
        verify(executor,never()).execute(any(),any(),any()); verify(executor,never()).plan(any(),any(),any());
        verifyNoInteractions(factors,grants,publication);
        mvc.perform(get("/api/mobile/sessions/{id}/delivery",sessionId).with(auth(routine))).andExpect(status().isForbidden());
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
