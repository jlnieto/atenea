package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.atenea.delivery.DeliveryRejectedException;
import com.atenea.delivery.SourceUpdateBaseGuard;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.*;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.*;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Caller-owned ephemeral PostgreSQL16 only; worker/source responses are synthetic. */
// Other Spring contexts retain scheduled readers against the job's base DB.
// Use a private database in that same caller-owned ephemeral PostgreSQL runtime.
// A schema alone is insufficient: historical V45 inspects information_schema
// across schemas, so migrating a second schema can incorrectly skip its rename.
@SpringBootTest(properties={"atenea.auth.bootstrap.enabled=false","atenea.remote-worker.enabled=true"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ChangeValidationWorkflowIntegrationTest {
    static final String COMMIT="1".repeat(40), TREE="4".repeat(64);
    static final String DATABASE="server_validation_"+UUID.randomUUID().toString().replace("-","");

    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry properties) throws SQLException {
        String url=System.getenv("SPRING_DATASOURCE_URL");
        String user=System.getenv("SPRING_DATASOURCE_USERNAME"),password=System.getenv("SPRING_DATASOURCE_PASSWORD");
        if (url==null || !url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|db-test):[0-9]+/atenea_test")
                || !"atenea".equals(user) || !"atenea".equals(password))
            throw new IllegalStateException("Caller-owned ephemeral PostgreSQL and synthetic credentials required");
        try (var db=DriverManager.getConnection(url,user,password);var sql=db.createStatement()) {
            sql.execute("CREATE DATABASE \""+DATABASE+"\"");
        }
        properties.add("spring.datasource.url",()->url.substring(0,url.length()-"atenea_test".length())+DATABASE);
        // The owning test container/job removes this DB with its PostgreSQL
        // runtime. No shared DEV/PROD database or server configuration is used.
    }
    @Autowired ChangeValidationCoordinator coordinator;
    @Autowired ChangeValidationWorkflowStore store;
    @Autowired ClosedValidationOperationService validation;
    @Autowired WorkSessionRepository sessions;
    @Autowired DevelopmentChangeRepository changes;
    @Autowired ProjectRepository projects;
    @Autowired ValidationOperationRepository operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AgentRunService agentRuns;
    @Autowired com.atenea.delivery.SourceUpdateStore sourceUpdates;
    @Autowired com.atenea.delivery.DeliveryStore deliveries;
    @Autowired com.atenea.service.mobile.ValidationRecoveryStore recoveries;
    @MockBean RemoteWorkerClient worker;
    @MockBean SourceUpdateBaseGuard base;
    @MockBean ChangeValidationScheduler scheduler;
    @MockBean ValidationResultReconciler resultCollector;
    Long sessionId,changeId,projectId;
    UUID remoteSession,key;
    boolean createdWorker;
    final Map<String,RemoteWorkerClient.DurableValidationResult> remote=new ConcurrentHashMap<>();

    @BeforeEach void fixture() {
        remote.clear(); remoteSession=UUID.randomUUID();key=UUID.randomUUID();
        tx(()->{
            createdWorker=jdbc.update("""
                INSERT INTO worker_node(id,protocol_version,endpoint,enabled,healthy,normal_capacity,heavy_capacity,capabilities)
                VALUES ('ax42-01','agent-run-worker/v1','http://synthetic.invalid',true,true,1,1,'project-codex-v4')
                ON CONFLICT(id) DO NOTHING
                """)==1;
            var now=Instant.now();
            var p=new ProjectEntity();p.setName(ProjectCodexIdentity.PROJECT_NAME);p.setRepoPath(ProjectCodexIdentity.REPO_PATH);
            p.setDefaultBaseBranch("main");p.setCreatedAt(now);p.setUpdatedAt(now);projects.saveAndFlush(p);projectId=p.getId();
            var c=new DevelopmentChangeEntity();c.setProject(p);c.setChangeKey(key);c.setTitle("Synthetic server validation");
            c.setBaseRef("refs/heads/main");c.setBaseCommit(COMMIT);c.setProjectPolicyRevision(1);
            c.setWorkspaceIdentity("remote:ax42-01:change:"+key);c.setWorkspaceBranch("atenea/change-"+key);
            c.setSelectedWorkerId("ax42-01");c.setStatus(DevelopmentChangeStatus.OPEN);
            c.setSourceRevision(3);c.setObservedCanonicalCommit(COMMIT);c.setSourceFingerprintSha256(TREE);
            c.setSourceState(DevelopmentChangeSourceState.DIRTY);c.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
            c.setWorkspaceObservationSha256("2".repeat(64));c.setWorkspaceOwnershipFingerprintSha256("3".repeat(64));
            c.setWorkspaceOperationRevision(1);c.setWorkspaceUpdatedAt(now);c.setValidationState(DevelopmentChangeProjectionState.STALE);
            c.setCreatedAt(now);c.setUpdatedAt(now);changes.saveAndFlush(c);changeId=c.getId();
            var s=new WorkSessionEntity();s.setProject(p);s.setDevelopmentChange(c);s.setTitle(c.getTitle());
            s.setStatus(WorkSessionStatus.OPEN);s.setExecutionTarget(ExecutionTarget.REMOTE);s.setBaseBranch("main");
            s.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);s.setRemoteCloseState(RemoteCloseState.NOT_STARTED);
            s.setWorkspaceBranch(c.getWorkspaceBranch());s.setWorkspaceIdentity(c.getWorkspaceIdentity());s.setSelectedWorkerId("ax42-01");
            s.setRemoteSessionId(remoteSession);s.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
            s.setCanonicalSourceCommit(COMMIT);s.setCanonicalSourceRef("refs/heads/main");s.setCanonicalSourceObservedAt(now);
            s.setCanonicalSourceObservationSha256("2".repeat(64));s.setSourceTreeFingerprintSha256(TREE);s.setSourceTreeObservedAt(now);
            s.setOpenedAt(now);s.setLastActivityAt(now);s.setCreatedAt(now);s.setUpdatedAt(now);sessions.saveAndFlush(s);sessionId=s.getId();
        });
        when(worker.fingerprintSourceTree(any())).thenAnswer(call->{
            var s=call.getArgument(0,WorkSessionEntity.class);
            return new RemoteWorkerClient.SourceTreeFingerprint("observed",remoteSession.toString(),s.getWorkspaceIdentity(),
                    "atenea",COMMIT,TREE,1,0,0,false);
        });
        when(worker.inspectValidation(any(),anyString())).thenAnswer(call->{
            var value=remote.get(call.getArgument(1,String.class));
            if (value==null) throw absent();
            return value;
        });
        when(worker.startValidation(any(),any(),anyString(),anyString())).thenAnswer(call->{
            var value=result(call.getArgument(3),call.getArgument(1),"SUCCEEDED");
            remote.put(value.operationId(),value);return value;
        });
    }
    @AfterEach void cleanup() {
        tx(()->{
            jdbc.update("DELETE FROM change_validation_workflow WHERE session_id=?",sessionId);
            jdbc.update("DELETE FROM validation_operation WHERE work_session_id=?",sessionId);
            if (sessionId!=null) { sessions.deleteById(sessionId);sessions.flush(); }
            if (changeId!=null) {changes.deleteById(changeId);changes.flush();}
            if (projectId!=null) {projects.deleteById(projectId);projects.flush();}
            if (createdWorker) jdbc.update("DELETE FROM worker_node WHERE id='ax42-01'");
        });
    }

    @Test void fixturesAreIsolatedFromOtherCachedApplicationSchedulers() {
        assertEquals(DATABASE,jdbc.queryForObject("SELECT current_database()",String.class));
        assertEquals("public",jdbc.queryForObject("SELECT current_schema()",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM work_session WHERE id=?",Integer.class,sessionId));
        assertTrue(mockingDetails(scheduler).isMock());
        assertTrue(mockingDetails(resultCollector).isMock());
    }

    @Test void oneRequestRunsAllFourWithoutAnotherMobileCall() {
        UUID id=UUID.randomUUID();var receipt=coordinator.request(sessionId,id);
        assertEquals("RUNNING",receipt.state());assertEquals("QUEUED",receipt.phase());
        assertEquals(0,countChecks());verify(worker,never()).startValidation(any(),any(),anyString(),anyString());
        complete(id);
        assertEquals("SUCCEEDED",store.find(id).orElseThrow().state());
        assertEquals(4,countChecks());
        for (var kind:ValidationOperationKind.values()) verify(worker,times(1)).startValidation(any(),eq(kind),eq(TREE),anyString());
        assertEquals(DevelopmentChangeProjectionState.CURRENT,changes.findById(changeId).orElseThrow().getValidationState());
        assertEquals(WorkSessionAcceptanceState.VALIDATED,sessions.findById(sessionId).orElseThrow().getAcceptanceState());
    }

    @Test void repeatedPostAndConcurrentClickCannotCreateAnotherWorkflow() {
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);
        for(int i=0;i<5;i++) assertEquals(id,coordinator.request(sessionId,id).id());
        assertThrows(WorkSessionOperationBlockedException.class,()->coordinator.request(sessionId,UUID.randomUUID()));
        complete(id);
        assertEquals("SUCCEEDED",coordinator.request(sessionId,id).state());
        assertEquals(4,countChecks());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM change_validation_workflow WHERE session_id=?",Integer.class,sessionId));
    }

    @Test void restartBeforeDispatchRecoversCommittedOperationId() {
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);coordinator.progress(id);
        UUID operation=store.find(id).orElseThrow().activeOperationId();
        assertNotNull(operation);assertEquals(1,countChecks());
        verify(worker,never()).startValidation(any(),any(),anyString(),anyString());
        var restarted=new ChangeValidationCoordinator(store,sessions,operations,validation,worker,base,jdbc);
        for(int i=0;i<20 && !store.find(id).orElseThrow().terminal();i++) tx(()->restarted.progress(id));
        assertEquals("SUCCEEDED",store.find(id).orElseThrow().state());
        verify(worker,times(1)).startValidation(any(),eq(ValidationOperationKind.BACKEND_TEST),eq(TREE),eq(operation.toString()));
        assertEquals(4,countChecks());
    }

    @Test void lostStartResponseIsReconciledWithoutReexecutingTheCheck() {
        doAnswer(call->{
            var kind=call.getArgument(1,ValidationOperationKind.class);
            var value=result(call.getArgument(3),kind,"SUCCEEDED");remote.put(value.operationId(),value);
            if (kind==ValidationOperationKind.BACKEND_TEST) throw new RemoteWorkerException("synthetic lost ACK",503);
            return value;
        }).when(worker).startValidation(any(),any(),anyString(),anyString());
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);
        coordinator.progress(id);UUID operation=store.find(id).orElseThrow().activeOperationId();coordinator.progress(id);
        assertEquals("DISPATCHING",store.find(id).orElseThrow().phase());
        assertEquals("VALIDATION_WORKER_UNCONFIRMED",store.find(id).orElseThrow().errorCode());
        complete(id);
        assertEquals("SUCCEEDED",store.find(id).orElseThrow().state());
        verify(worker,times(1)).startValidation(any(),eq(ValidationOperationKind.BACKEND_TEST),eq(TREE),eq(operation.toString()));
        assertEquals(4,countChecks());
    }

    @Test void unknownTransportOrUntrustedNotFoundNeverAdmitsAnotherOperation() {
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);coordinator.progress(id);
        doThrow(new RemoteWorkerException("synthetic unavailable",503)).when(worker).inspectValidation(any(),anyString());
        for(int i=0;i<3;i++)coordinator.progress(id);
        assertEquals("RUNNING",store.find(id).orElseThrow().state());assertEquals(1,countChecks());
        verify(worker,never()).startValidation(any(),any(),anyString(),anyString());
        doThrow(new RemoteWorkerException("untyped or wrong route",404)).when(worker).inspectValidation(any(),anyString());
        coordinator.progress(id);
        assertEquals("BLOCKED",store.find(id).orElseThrow().state());
        verify(worker,never()).startValidation(any(),any(),anyString(),anyString());
    }

    @Test void crashAfterWorkerAdmissionBeforeDatabaseCommitReusesTheDurableOutbox() {
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);coordinator.progress(id);
        UUID operation=store.find(id).orElseThrow().activeOperationId();
        assertThrows(IllegalStateException.class,()->tx(()->{
            coordinator.progress(id); // Synthetic worker has already accepted/completed the UUID.
            throw new IllegalStateException("synthetic process lost before transaction commit");
        }));
        assertEquals("DISPATCHING",store.find(id).orElseThrow().phase());
        assertEquals(ValidationOperationStatus.RUNNING,operations.findById(operation).orElseThrow().getStatus());
        assertTrue(remote.containsKey(operation.toString()));
        complete(id);
        verify(worker,times(1)).startValidation(any(),eq(ValidationOperationKind.BACKEND_TEST),eq(TREE),eq(operation.toString()));
        assertEquals(4,countChecks());assertEquals("SUCCEEDED",store.find(id).orElseThrow().state());
    }

    @Test void candidateFailureStopsAndOnlyANewExplicitIntentRetriesIt() {
        doAnswer(call->{
            var value=result(call.getArgument(3),call.getArgument(1),"CANDIDATE_FAILED");remote.put(value.operationId(),value);return value;
        }).when(worker).startValidation(any(),any(),anyString(),anyString());
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);complete(id);
        assertEquals("FAILED",store.find(id).orElseThrow().state());assertEquals(1,countChecks());
        for(int i=0;i<5;i++)coordinator.progress(id);
        assertEquals("FAILED",coordinator.request(sessionId,id).state());assertEquals(1,countChecks());
        verify(worker,times(1)).startValidation(any(),any(),anyString(),anyString());
        doAnswer(call->{
            var value=result(call.getArgument(3),call.getArgument(1),"SUCCEEDED");remote.put(value.operationId(),value);return value;
        }).when(worker).startValidation(any(),any(),anyString(),anyString());
        UUID retry=UUID.randomUUID();coordinator.request(sessionId,retry);complete(retry);
        assertEquals("SUCCEEDED",store.find(retry).orElseThrow().state());assertEquals(5,countChecks());
        assertNotEquals(id,retry);
        assertNotNull(store.find(retry).orElseThrow().retryOfOperationId());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM validation_operation WHERE work_session_id=? AND status='FAILED'",Integer.class,sessionId));
    }

    @Test void secondFailureDuringExplicitRetryIsNotAutomaticallyRetriedAgain() {
        doAnswer(call->{
            var value=result(call.getArgument(3),call.getArgument(1),"CANDIDATE_FAILED");remote.put(value.operationId(),value);return value;
        }).when(worker).startValidation(any(),any(),anyString(),anyString());
        UUID first=UUID.randomUUID();coordinator.request(sessionId,first);complete(first);
        UUID retry=UUID.randomUUID();coordinator.request(sessionId,retry);complete(retry);
        for(int i=0;i<5;i++)coordinator.progress(retry);
        assertEquals("FAILED",store.find(retry).orElseThrow().state());assertEquals(2,countChecks());
        verify(worker,times(2)).startValidation(any(),eq(ValidationOperationKind.BACKEND_TEST),eq(TREE),anyString());
    }

    @Test void changedSourceOrOwnerCannotCarryPermissionToAnotherRevision() {
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);
        tx(()->jdbc.update("UPDATE development_change SET source_revision=source_revision+1 WHERE id=?",changeId));
        coordinator.progress(id);
        assertEquals("STALE",store.find(id).orElseThrow().state());assertEquals(0,countChecks());
        verify(worker,never()).startValidation(any(),any(),anyString(),anyString());
        assertEquals("STALE",coordinator.request(sessionId,id).state()); // Same key cannot authorize the new revision.
    }

    @Test void movingMainBetweenChecksStopsNewAdmissionAndKeepsThePassedResult() {
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);coordinator.progress(id);coordinator.progress(id);
        assertEquals("QUEUED",store.find(id).orElseThrow().phase());assertEquals(1,countChecks());
        doThrow(new DeliveryRejectedException("SOURCE_FINALIZATION_MAIN_MOVED")).when(base).requireCurrent(sessionId);
        coordinator.progress(id);
        assertEquals("BLOCKED",store.find(id).orElseThrow().state());
        assertEquals("SOURCE_FINALIZATION_MAIN_MOVED",store.find(id).orElseThrow().errorCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM validation_operation WHERE work_session_id=? AND status='SUCCEEDED'",Integer.class,sessionId));
        verify(worker,never()).startValidation(any(),eq(ValidationOperationKind.WEB_BUILD),anyString(),anyString());
    }

    @Test void foreignEvidenceCannotPromoteOrStartTheNextCheck() {
        doAnswer(call->result(UUID.randomUUID().toString(),call.getArgument(1),"SUCCEEDED"))
                .when(worker).startValidation(any(),any(),anyString(),anyString());
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);coordinator.progress(id);coordinator.progress(id);
        assertEquals("BLOCKED",store.find(id).orElseThrow().state());assertEquals(1,countChecks());
        assertNotEquals(DevelopmentChangeProjectionState.CURRENT,changes.findById(changeId).orElseThrow().getValidationState());
        verify(worker,never()).startValidation(any(),eq(ValidationOperationKind.WEB_BUILD),anyString(),anyString());
    }

    @Test void mainMovingAfterCommittedAdmissionDoesNotStrandAnUnsentOperation() {
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);coordinator.progress(id);
        UUID operation=store.find(id).orElseThrow().activeOperationId();
        doThrow(new DeliveryRejectedException("SOURCE_FINALIZATION_MAIN_MOVED")).when(base).requireCurrent(sessionId);
        coordinator.progress(id); // Completes the already admitted UUID, not another check.
        assertEquals(ValidationOperationStatus.SUCCEEDED,operations.findById(operation).orElseThrow().getStatus());
        coordinator.progress(id); // New admission is refused against moved main.
        assertEquals("BLOCKED",store.find(id).orElseThrow().state());assertEquals(1,countChecks());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM validation_operation WHERE work_session_id=? AND status='RUNNING'",Integer.class,sessionId));
        verify(worker,never()).startValidation(any(),eq(ValidationOperationKind.WEB_BUILD),anyString(),anyString());
    }

    @Test void readOnlyProgressAndInactiveScreenNeverAdvanceOrRepeatChecks() {
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);clearInvocations(worker);
        for(int i=0;i<5;i++) {
            var evidence=validation.getDevelopmentChangeEvidence(sessionId);
            assertEquals("RUNNING",evidence.workflow().state());assertEquals("QUEUED",evidence.workflow().phase());
            assertEquals(0,evidence.passedOperations());assertEquals(0,countChecks());
        }
        verifyNoInteractions(worker);
        assertTrue(sessions.existsActiveValidationWorkflowBySessionId(sessionId));
        assertThrows(WorkSessionOperationBlockedException.class,()->validation.run(sessionId,ValidationOperationKind.BACKEND_TEST));
    }

    @Test void workflowBlocksOtherWritersEvenBetweenChecksWithNoRunningAttempt() {
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);
        assertEquals(0,countChecks());
        assertThrows(DeliveryRejectedException.class,()->tx(sourceUpdates::requireIdle));
        assertThrows(DeliveryRejectedException.class,()->tx(()->deliveries.lockCompletionAdmissionAndRequireIdle(sessionId)));
        tx(()->assertFalse(recoveries.idle()));
        var s=sessions.findById(sessionId).orElseThrow();
        assertThrows(WorkSessionOperationBlockedException.class,()->agentRuns.createRemoteQueuedRun(s,new SessionTurnEntity(),WorkloadClass.NORMAL));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE session_id=?",Integer.class,sessionId));
    }

    @Test void twoDispatchersSerializeTheSameCommittedRequest() throws Exception {
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);coordinator.progress(id);
        UUID operation=store.find(id).orElseThrow().activeOperationId();
        try(var threads=Executors.newFixedThreadPool(2)) {
            var a=threads.submit(()->coordinator.progress(id));var b=threads.submit(()->coordinator.progress(id));
            a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);
        }
        verify(worker,times(1)).startValidation(any(),eq(ValidationOperationKind.BACKEND_TEST),eq(TREE),eq(operation.toString()));
        assertTrue(countChecks()<=2);complete(id);assertEquals(4,countChecks());
    }

    @Test void legacyPollingReusesOneIntentAndNeverRetriesAFailedResult() {
        doAnswer(call->result(call.getArgument(3),call.getArgument(1),"CANDIDATE_FAILED"))
                .when(worker).startValidation(any(),any(),anyString(),anyString());
        coordinator.legacyRequest(sessionId);UUID id=store.latestSession(sessionId).orElseThrow().id();
        for(int i=0;i<5;i++)coordinator.legacyRequest(sessionId);
        complete(id);
        for(int i=0;i<5;i++)assertEquals("FAILED",coordinator.legacyRequest(sessionId).state());
        assertEquals(1,countChecks());verify(worker,times(1)).startValidation(any(),any(),anyString(),anyString());
    }

    @Test void anotherRevisionStillRunningCannotBeBypassedByANewRequest() {
        tx(()->operations.saveAndFlush(validation.newValidationEntity(sessions.findById(sessionId).orElseThrow(),
                ValidationOperationKind.BACKEND_TEST,"f".repeat(64),null)));
        assertThrows(WorkSessionOperationBlockedException.class,()->coordinator.request(sessionId,UUID.randomUUID()));
        assertTrue(store.latestSession(sessionId).isEmpty());
        verify(worker,never()).startValidation(any(),any(),anyString(),anyString());
    }

    @Test void explicitRecoveryCanResumeAnUnsentOwnedAttemptWithoutANewOperationId() {
        UUID id=UUID.randomUUID();coordinator.request(sessionId,id);coordinator.progress(id);
        UUID operation=store.find(id).orElseThrow().activeOperationId();
        doThrow(new RemoteWorkerException("synthetic wrong gateway",404)).when(worker).inspectValidation(any(),anyString());
        coordinator.progress(id);assertEquals("BLOCKED",store.find(id).orElseThrow().state());
        doAnswer(call->{var found=remote.get(call.getArgument(1,String.class));if(found==null)throw absent();return found;})
                .when(worker).inspectValidation(any(),anyString());
        UUID recovery=UUID.randomUUID();coordinator.request(sessionId,recovery);complete(recovery);
        assertEquals("SUCCEEDED",store.find(recovery).orElseThrow().state());assertEquals(4,countChecks());
        verify(worker,times(1)).startValidation(any(),eq(ValidationOperationKind.BACKEND_TEST),eq(TREE),eq(operation.toString()));
    }

    private void complete(UUID id) {
        for(int i=0;i<25 && !store.find(id).orElseThrow().terminal();i++)coordinator.progress(id);
        assertTrue(store.find(id).orElseThrow().terminal(),"bounded scheduler simulation must reach a terminal outcome");
    }
    private int countChecks() {return jdbc.queryForObject("SELECT count(*) FROM validation_operation WHERE work_session_id=?",Integer.class,sessionId);}
    private void tx(Runnable code) {new TransactionTemplate(transactions).executeWithoutResult(ignored->code.run());}
    private RemoteWorkerException absent() {
        return new RemoteWorkerException("synthetic not found",404,"VALIDATION_NOT_FOUND",RemoteWorkerFailureCategory.VALIDATION,
                false,AgentRunRecoveryNextAction.NONE,null);
    }
    private RemoteWorkerClient.DurableValidationResult result(String id,ValidationOperationKind kind,String state) {
        boolean success="SUCCEEDED".equals(state),candidate="CANDIDATE_FAILED".equals(state);
        return new RemoteWorkerClient.DurableValidationResult(1,"closed-validation-broker/v1",id,remoteSession.toString(),
            "remote:ax42-01:change:"+key,"atenea",COMMIT,TREE,kind.name(),kind.definitionRevision(),state,
            candidate?"CANDIDATE":"NONE","CONFIRMED",success?0:candidate?1:null,100,"a".repeat(64),
            "Closed validation "+(success?"passed":"failed"),Instant.now().toString(),Instant.now().toString(),
            Instant.now().toString(),Instant.now().toString(),1,false);
    }
}
