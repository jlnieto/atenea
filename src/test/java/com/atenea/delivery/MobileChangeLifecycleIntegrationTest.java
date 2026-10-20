package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.OperatorAuthProperties;
import com.atenea.auth.recovery.OperatorRecoveryService;
import com.atenea.auth.recovery.TotpAlgorithm;
import com.atenea.auth.recovery.TotpEnrollmentActivationRequest;
import com.atenea.persistence.auth.*;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.*;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.service.worksession.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * One committed API -> DB -> real App services -> real HTTP/Unix transports journey.
 * Worker/GitHub/publisher are closed SYNTHETIC peers: no real tests, builds,
 * deployments or Codex. Creation/execution and the Samsung remain separate gates.
 * No test transaction and no acceptance/publication state repair between actions.
 */
@SpringBootTest(properties={"atenea.auth.bootstrap.enabled=false","atenea.remote-worker.enabled=true",
    "atenea.auth.privileged-actions.enabled=true",
    "atenea.remote-worker.remote-close-release-enabled=true","atenea.remote-worker.remote-close-project-allowlist=atenea",
    "ATENEA_RELEASE_CONTROL_RECONCILE_DELAY_MS=3600000","atenea.delivery.completion-delay-ms=3600000"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MobileChangeLifecycleIntegrationTest {
    static final String PASSWORD="synthetic-lifecycle-only";
    static String database;
    static MobileLifecyclePeers peers;
    @DynamicPropertySource static void isolatedRuntime(DynamicPropertyRegistry properties) throws Exception {
        String url=System.getenv("SPRING_DATASOURCE_URL"),user=System.getenv("SPRING_DATASOURCE_USERNAME"),password=System.getenv("SPRING_DATASOURCE_PASSWORD");
        if(url==null || !url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|db-test):[0-9]+/atenea_test")
                || !"atenea".equals(user) || !"atenea".equals(password))throw new IllegalStateException("Caller-owned ephemeral test PostgreSQL required");
        database="mobile_lifecycle_"+UUID.randomUUID().toString().replace("-","");
        try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE \""+database+"\"");
        }
        peers=new MobileLifecyclePeers();
        properties.add("spring.datasource.url",()->url.substring(0,url.length()-"atenea_test".length())+database);
        properties.add("atenea.remote-worker.endpoint",peers::url);
        properties.add("atenea.remote-worker.token-file",()->peers.tokenFile.toString());
        properties.add("atenea.github.api-base-url",peers::url);
        properties.add("atenea.github.token",()->"synthetic-github-token");
    }
    @TestConfiguration static class IsolatedPublisher {
        @Bean @Primary ReleaseControlClient lifecyclePublisher(ObjectMapper mapper) {return new ReleaseControlClient(mapper,true,peers.address);}
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectRepository projects;
    @Autowired DevelopmentChangeRepository changes;
    @Autowired WorkSessionRepository sessions;
    @Autowired OperatorRepository operators;
    @Autowired PasswordEncoder passwords;
    @Autowired OperatorRecoveryService factors;
    @Autowired OperatorAuthProperties auth;
    @Autowired ChangeValidationCoordinator validation;
    @Autowired ChangeValidationWorkflowStore workflows;
    @Autowired MobileDeliveryService delivery;
    @Autowired MobileChangeCompletionService completion;
    @Autowired DeliveryStore deliveries;
    // Scheduler ticks are driven independently of HTTP, deterministically. The
    // actual coordinator, stores, services, clients and security are NOT mocks.
    @MockBean ChangeValidationScheduler scheduler;
    @MockBean ValidationResultReconciler resultCollector;
    @MockBean AgentRunReconciliationService runs;
    Long sessionId,changeId;
    String token;
    byte[] totp;

    @BeforeEach void freshPostRunFixture() throws Exception {
        assertEquals(database,jdbc.queryForObject("SELECT current_database()",String.class));
        var now=Instant.now();UUID change=UUID.randomUUID(),remote=UUID.randomUUID();
        peers.reset(change,remote,List.of("src/main/java/com/atenea/Synthetic.java"));
        jdbc.update("""
            INSERT INTO worker_node(id,protocol_version,endpoint,enabled,healthy,normal_capacity,heavy_capacity,capabilities)
            VALUES ('ax42-01','agent-run-worker/v1',?,true,true,1,1,'project-codex-v4') ON CONFLICT(id) DO NOTHING
            """,peers.url());
        var p=new ProjectEntity();p.setName("Atenea");p.setRepoPath(ProjectCodexIdentity.REPO_PATH);p.setDefaultBaseBranch("main");
        p.setCreatedAt(now);p.setUpdatedAt(now);projects.saveAndFlush(p);
        var c=new DevelopmentChangeEntity();c.setProject(p);c.setChangeKey(change);c.setTitle("Synthetic mobile lifecycle");
        c.setBaseRef("refs/heads/main");c.setBaseCommit(MobileLifecyclePeers.BASE);c.setProjectPolicyRevision(1);
        c.setWorkspaceIdentity(peers.workspace);c.setWorkspaceBranch(peers.branch);c.setSelectedWorkerId("ax42-01");
        c.setStatus(DevelopmentChangeStatus.OPEN);c.setSourceRevision(1);c.setObservedCanonicalCommit(MobileLifecyclePeers.BASE);
        c.setSourceFingerprintSha256(MobileLifecyclePeers.TREE);c.setSourceState(DevelopmentChangeSourceState.DIRTY);
        c.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);c.setWorkspaceObservationSha256("5".repeat(64));
        c.setWorkspaceOwnershipFingerprintSha256("6".repeat(64));c.setWorkspaceOperationRevision(1);c.setWorkspaceUpdatedAt(now);
        c.setValidationState(DevelopmentChangeProjectionState.NOT_STARTED);c.setCreatedAt(now);c.setUpdatedAt(now);changes.saveAndFlush(c);changeId=c.getId();
        var s=new WorkSessionEntity();s.setProject(p);s.setDevelopmentChange(c);s.setTitle(c.getTitle());s.setStatus(WorkSessionStatus.OPEN);
        s.setExecutionTarget(ExecutionTarget.REMOTE);s.setBaseBranch("main");s.setWorkspaceBranch(peers.branch);s.setWorkspaceIdentity(peers.workspace);
        s.setSelectedWorkerId("ax42-01");s.setRemoteSessionId(remote);s.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        s.setRemoteCloseState(RemoteCloseState.NOT_STARTED);s.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        s.setCanonicalSourceCommit(MobileLifecyclePeers.BASE);s.setCanonicalSourceRef("refs/heads/main");s.setCanonicalSourceObservedAt(now);
        s.setCanonicalSourceObservationSha256("5".repeat(64));s.setAcceptanceState(WorkSessionAcceptanceState.DRAFT);
        s.setOpenedAt(now);s.setLastActivityAt(now);s.setCreatedAt(now);s.setUpdatedAt(now);sessions.saveAndFlush(s);sessionId=s.getId();
        // Exactly the real first/post-run starting state; never pre-mark VALIDATED.
        assertNull(s.getSourceTreeFingerprintSha256());assertNull(s.getSourceTreeObservedAt());
        var operator=new OperatorEntity();operator.setEmail(UUID.randomUUID()+"@atenea.test");operator.setDisplayName("Synthetic lifecycle");
        operator.setPasswordHash(passwords.encode(PASSWORD));operator.setActive(true);operator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        operator.setCreatedAt(now);operator.setUpdatedAt(now);operators.saveAndFlush(operator);
        auth.getRecovery().setTotpEnabled(true);auth.getRecovery().setRecoveryEnabled(true);auth.getRecovery().setActiveEncryptionKeyVersion("synthetic");
        auth.getRecovery().setEncryptionKeys(List.of("synthetic:"+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32])));
        auth.getRecovery().setActiveHmacKeyVersion("synthetic");
        auth.getRecovery().setHmacKeys(List.of("synthetic:"+Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32])));
        var actor=new AuthenticatedOperator(operator.getId(),operator.getEmail(),operator.getDisplayName());
        var enrollment=factors.beginTotpEnrollment(actor);totp=decodeBase32(enrollment.secret());
        factors.activateTotpEnrollment(actor,new TotpEnrollmentActivationRequest(enrollment.enrollmentId(),
            TotpAlgorithm.code(totp,Instant.now().getEpochSecond()/30-1)));
        var login=mapper.readTree(mvc.perform(post("/api/mobile/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.createObjectNode().put("email",operator.getEmail()).put("password",PASSWORD)
                .put("clientType","ANDROID").put("deviceLabel","Synthetic lifecycle")
                .put("sessionProtocolVersion","FAMILY_V1").put("singleFlightRefresh",true).toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        token=login.path("accessToken").asText();assertFalse(token.isBlank());
    }
    @AfterEach void peerContractsHeld() throws Exception {
        try {assertEquals(List.of(),peers.failures,"synthetic peers must validate real client requests");}
        finally {if(totp!=null)Arrays.fill(totp,(byte)0);if(peers!=null)peers.close();}
    }

    @ParameterizedTest @ValueSource(strings={"BACKEND","ANDROID","BOTH"})
    void firstValidationThroughPublishedMergedDeliveredAndCompletedUsingOnlyNormalApiActions(String targets) throws Exception {
        peers.files=switch(targets) {
            case "ANDROID"->List.of("android/app/src/main/Synthetic.kt");
            case "BOTH"->List.of("src/main/java/com/atenea/Synthetic.java","android/app/src/main/Synthetic.kt");
            default->List.of("src/main/java/com/atenea/Synthetic.java");
        };
        runJourney(targets,false);
    }

    @Test void lostWorkerMergeAndPublisherAcknowledgementsRecoverTheSameDurableEffects() throws Exception {
        peers.lostCheckReply=true;peers.lostMergeReply=true;peers.lostReleaseReply=true;
        runJourney("BACKEND",true);
    }

    @Test void unhealthyDeliveryBlocksClosingAndNormalRecoveryKeepsTheSameCompletionAndPublication() throws Exception {
        runJourney("BACKEND",false,true);
    }

    @Test void foreignSourceIsRejectedBeforeAdmissionAndPassiveReadsDoNotRepairIt() throws Exception {
        peers.foreignSource=true;
        mvc.perform(post("/api/sessions/"+sessionId+"/validation-workflows").header("Authorization","Bearer "+token)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.createObjectNode().put("idempotencyKey",UUID.randomUUID().toString()).toString()))
            .andExpect(status().is(422));
        readOnlySnapshots();
        assertTrue(workflows.latestSession(sessionId).isEmpty());assertEquals(List.of(),peers.effects);
        assertNull(sessions.findById(sessionId).orElseThrow().getSourceTreeFingerprintSha256());
        assertNull(sessions.findById(sessionId).orElseThrow().getSourceTreeObservedAt());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM validation_operation WHERE work_session_id=?",Integer.class,sessionId));
        assertEquals(DevelopmentChangeProjectionState.NOT_STARTED,changes.findById(changeId).orElseThrow().getValidationState());
    }

    @Test void failedFirstCheckStopsTheJourneyAndReadsNeverRetryOrCreateAPr() throws Exception {
        peers.failCheck=true;UUID id=requestValidation();
        progressValidation(id);
        assertEquals("FAILED",workflows.find(id).orElseThrow().state());
        assertEquals(List.of("CHECK BACKEND_TEST"),peers.effects);
        readOnlySnapshots();
        // No PR action is admitted against the unvalidated owner.
        mvc.perform(post(base()+"/pr").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation WHERE session_id=?",Integer.class,sessionId));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM validation_operation WHERE work_session_id=?",Integer.class,sessionId));
        assertFalse(peers.prCreated);assertEquals(WorkSessionStatus.OPEN,sessions.findById(sessionId).orElseThrow().getStatus());
    }

    private void runJourney(String targets,boolean interrupted) throws Exception {
        runJourney(targets,interrupted,false);
    }
    private void runJourney(String targets,boolean interrupted,boolean unhealthyAfterIntent) throws Exception {
        UUID workflow=requestValidation();readOnlySnapshots();progressValidation(workflow);
        assertEquals("SUCCEEDED",workflows.find(workflow).orElseThrow().state());
        assertEquals(WorkSessionAcceptanceState.VALIDATED,sessions.findById(sessionId).orElseThrow().getAcceptanceState());
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM validation_operation WHERE work_session_id=? AND status='SUCCEEDED'",Integer.class,sessionId));
        UUID publication=id(postJson(base()+"/pr","{}"));
        assertEquals(publication,id(postJson(base()+"/pr","{}")));readOnlySnapshots();
        progressDelivery(publication,"SUCCEEDED");
        assertEquals(MobileLifecyclePeers.HEAD,sessions.findById(sessionId).orElseThrow().getFinalCommitSha());
        UUID integration=id(postJson(base()+"/integrate","{}"));readOnlySnapshots();
        progressDelivery(integration,"SUCCEEDED");
        var integrated=sessions.findById(sessionId).orElseThrow();
        assertEquals(WorkSessionPullRequestStatus.MERGED,integrated.getPullRequestStatus());
        assertEquals(WorkSessionAcceptanceState.INTEGRATION_READY,integrated.getAcceptanceState());
        assertEquals(MobileLifecyclePeers.MERGE,deliveries.get(integration,false).evidence().path("mergeCommit").asText());
        assertFalse(getJson(base()).path("readiness").path("ready").asBoolean());
        mvc.perform(post(base()+"/finalize").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isConflict());
        int factorOffset=0;
        if(!targets.equals("ANDROID"))publish(DeliveryTarget.APP_PROD,factorOffset++);
        if(!targets.equals("BACKEND"))publish(DeliveryTarget.ANDROID_STABLE,factorOffset);
        var readiness=getJson(base()).path("readiness");assertTrue(readiness.path("ready").asBoolean(),readiness.toPrettyString());
        UUID finish=id(postJson(base()+"/finalize","{}"));assertEquals(finish,id(postJson(base()+"/finalize","{}")));
        if(unhealthyAfterIntent) {
            peers.unhealthy=true;completion.advance(finish);
            assertEquals("BLOCKED",deliveries.get(finish,false).state());
            assertEquals("HEALTHY_DELIVERED_CHANGE_REQUIRED",deliveries.get(finish,false).errorCode());
            assertFalse(peers.effects.contains("CLOSE"));readOnlySnapshots();peers.unhealthy=false;
            assertEquals(finish,id(postJson(base()+"/finalize/reconcile","{}")));
        }
        if(interrupted) {
            peers.busyApp=true;completion.advance(finish);
            assertEquals("APPLYING",deliveries.get(finish,false).state());assertEquals("APP_OBSERVATION_BUSY",deliveries.get(finish,false).errorCode());
            assertFalse(peers.effects.contains("CLOSE"));readOnlySnapshots();peers.busyApp=false;
        }
        readOnlySnapshots();completion.advance(finish);
        assertEquals("SUCCEEDED",deliveries.get(finish,false).state(),deliveries.get(finish,false).errorCode());
        assertEquals(finish,id(postJson(base()+"/finalize","{}")));completion.advance(finish);
        var closed=sessions.findById(sessionId).orElseThrow();
        assertEquals(WorkSessionStatus.CLOSED,closed.getStatus());assertEquals(RemoteCloseState.RELEASED,closed.getRemoteCloseState());
        assertEquals(DevelopmentChangeStatus.COMPLETED,changes.findById(changeId).orElseThrow().getStatus());
        assertEquals(1,peers.effects.stream().filter("PUSH"::equals).count());
        for(String effect:List.of("CREATE_PR","MERGE","CLOSE"))assertEquals(1,peers.effects.stream().filter(effect::equals).count(),effect);
        assertEquals(4,peers.effects.stream().filter(value->value.startsWith("CHECK ")).count());
        assertEquals(targets.equals("BOTH")?2:1,peers.effects.stream().filter(value->value.startsWith("DEPLOY ")).count());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM change_validation_workflow WHERE session_id=?",Integer.class,sessionId));
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM validation_operation WHERE work_session_id=?",Integer.class,sessionId));
        assertEquals(targets.equals("BOTH")?5:4,deliveries.list(sessionId).size());
        readOnlySnapshots();
    }
    private void publish(DeliveryTarget target,int factorOffset) throws Exception {
        UUID plan=id(postJson(base()+"/release-plan",mapper.createObjectNode().put("target",target.name()).toString()));
        assertEquals(plan,id(postJson(base()+"/release-plan",mapper.createObjectNode().put("target",target.name()).toString())));
        progressDelivery(plan,"READY");readOnlySnapshots();
        String code=TotpAlgorithm.code(totp,Instant.now().getEpochSecond()/30+factorOffset);
        var authorization=postJson("/api/mobile/delivery/"+plan+"/authorize",mapper.createObjectNode().put("totp",code).toString());
        var body=mapper.createObjectNode().put("authorization",authorization.path("authorization").asText()).toString();
        assertEquals("CONFIRMED",postJson("/api/mobile/delivery/"+plan+"/confirm",body).path("state").asText());
        assertEquals("CONFIRMED",postJson("/api/mobile/delivery/"+plan+"/confirm",body).path("state").asText());
        readOnlySnapshots();progressDelivery(plan,"SUCCEEDED");
    }
    private UUID requestValidation() throws Exception {
        UUID key=UUID.randomUUID();String body=mapper.createObjectNode().put("idempotencyKey",key.toString()).toString();
        String endpoint="/api/sessions/"+sessionId+"/validation-workflows";
        assertEquals(key,id(postJson(endpoint,body)));assertEquals(key,id(postJson(endpoint,body)));
        return key;
    }
    private void progressValidation(UUID id) {
        for(int i=0;i<30 && !workflows.find(id).orElseThrow().terminal();i++)validation.progress(id);
        assertTrue(workflows.find(id).orElseThrow().terminal(),workflows.find(id).orElseThrow().toString());
    }
    private void progressDelivery(UUID id,String expected) {
        for(int i=0;i<8 && !deliveries.get(id,false).state().equals(expected) && !deliveries.get(id,false).terminal();i++)delivery.reconcile();
        assertEquals(expected,deliveries.get(id,false).state(),deliveries.get(id,false).errorCode());
    }
    private void readOnlySnapshots() throws Exception {
        var before=List.copyOf(peers.effects);
        int workflowsBefore=jdbc.queryForObject("SELECT count(*) FROM change_validation_workflow WHERE session_id=?",Integer.class,sessionId);
        int operationsBefore=deliveries.list(sessionId).size();
        for(int i=0;i<2;i++) {getJson(base());getJson("/api/sessions/"+sessionId+"/validation-evidence");}
        assertEquals(before,peers.effects,"reads must not dispatch, merge, deploy or close");
        assertEquals(workflowsBefore,jdbc.queryForObject("SELECT count(*) FROM change_validation_workflow WHERE session_id=?",Integer.class,sessionId));
        assertEquals(operationsBefore,deliveries.list(sessionId).size());
    }
    private String base() {return "/api/mobile/sessions/"+sessionId+"/delivery";}
    private UUID id(JsonNode value) {return UUID.fromString(value.path("id").asText());}
    private JsonNode postJson(String path,String body) throws Exception {return response(post(path).contentType(MediaType.APPLICATION_JSON).content(body));}
    private JsonNode getJson(String path) throws Exception {return response(get(path));}
    private JsonNode response(MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(mvc.perform(request.header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private static byte[] decodeBase32(String value) {
        var output=new ByteArrayOutputStream();int buffer=0,bits=0;
        for(char c:value.toCharArray()) {int digit="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);if(digit<0)throw new IllegalArgumentException("Invalid synthetic base32");
            buffer=(buffer<<5)|digit;bits+=5;if(bits>=8){bits-=8;output.write((buffer>>>bits)&255);}}
        return output.toByteArray();
    }
}
