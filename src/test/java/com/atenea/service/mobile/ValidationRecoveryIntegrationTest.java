package com.atenea.service.mobile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.DeliveryRejectedException;
import com.atenea.persistence.auth.*;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.*;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.*;
import com.atenea.remoteworker.ValidationRecoveryCommand.Action;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL16 fixtures belong only to the caller-owned ephemeral test runtime. */
@SpringBootTest(properties={"atenea.auth.bootstrap.enabled=false","atenea.remote-worker.enabled=true",
    "atenea.validation-recovery.poll-ms=3600000"})
class ValidationRecoveryIntegrationTest {
    @Autowired ValidationRecoveryService service;
    @Autowired ValidationRecoveryStore store;
    @Autowired WorkSessionRepository sessions;
    @Autowired DevelopmentChangeRepository changes;
    @Autowired ProjectRepository projects;
    @Autowired OperatorRepository operators;
    @Autowired AgentRunRepository runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;
    @MockBean ValidationRecoveryGateway gateway;
    @MockBean RemoteWorkerClient worker;
    @MockBean RemoteAgentRunCoordinator coordinator;
    @MockBean com.atenea.service.worksession.AgentRunService agentRuns;
    TransactionTemplate tx;
    Long sessionId,changeId,projectId,operatorId;
    UUID validationId;
    AuthenticatedOperator actor;
    boolean createdWorker;
    final String before="4".repeat(64),after="5".repeat(64),head="1".repeat(40);

    @BeforeEach void fixture() {
        tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(ignored->{
            createdWorker=jdbc.update("""
                INSERT INTO worker_node(id,protocol_version,endpoint,enabled,healthy,normal_capacity,heavy_capacity,capabilities)
                VALUES('ax42-01','agent-run-worker/v1','http://synthetic.invalid',true,true,1,1,'project-codex-v4')
                ON CONFLICT(id) DO NOTHING
                """) == 1;
            var now=Instant.now();var operator=new OperatorEntity();operator.setEmail(UUID.randomUUID()+"@recovery.test");
            operator.setDisplayName("Synthetic admin");operator.setPasswordHash("synthetic");operator.setActive(true);
            operator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);operator.setCreatedAt(now);operator.setUpdatedAt(now);
            operators.saveAndFlush(operator);operatorId=operator.getId();actor=new AuthenticatedOperator(operatorId,operator.getEmail(),operator.getDisplayName());
            var project=new ProjectEntity();project.setName("Atenea");project.setRepoPath("/synthetic/atenea");
            project.setDefaultBaseBranch("main");project.setCreatedAt(now);project.setUpdatedAt(now);projects.saveAndFlush(project);projectId=project.getId();
            var change=new DevelopmentChangeEntity();change.setChangeKey(UUID.randomUUID());change.setProject(project);change.setTitle("Synthetic validation recovery");
            change.setBaseRef("refs/heads/main");change.setBaseCommit(head);change.setProjectPolicyRevision(1);change.setSourceRevision(1);
            change.setWorkspaceBranch("atenea/change-"+change.getChangeKey());change.setWorkspaceIdentity("remote:ax42-01:change:"+change.getChangeKey());
            change.setSelectedWorkerId("ax42-01");change.setObservedCanonicalCommit(head);change.setSourceFingerprintSha256(before);
            change.setSourceState(DevelopmentChangeSourceState.DIRTY);change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
            change.setWorkspaceOperationRevision(1);change.setWorkspaceUpdatedAt(now);change.setWorkspaceObservationSha256("a".repeat(64));
            change.setWorkspaceOwnershipFingerprintSha256(before);change.setValidationState(DevelopmentChangeProjectionState.BLOCKED);
            change.setCreatedAt(now);change.setUpdatedAt(now);changes.saveAndFlush(change);changeId=change.getId();
            var session=new WorkSessionEntity();session.setProject(project);session.setDevelopmentChange(change);session.setStatus(WorkSessionStatus.OPEN);
            session.setTitle(change.getTitle());session.setBaseBranch("main");session.setWorkspaceBranch(change.getWorkspaceBranch());
            session.setWorkspaceIdentity(change.getWorkspaceIdentity());session.setExecutionTarget(ExecutionTarget.REMOTE);session.setSelectedWorkerId("ax42-01");
            session.setRemoteCloseState(RemoteCloseState.NOT_STARTED);session.setRemoteSessionId(UUID.randomUUID());session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
            session.setCanonicalSourceCommit(head);session.setCanonicalSourceRef("refs/heads/main");session.setCanonicalSourceObservationSha256("b".repeat(64));
            session.setCanonicalSourceObservedAt(now);session.setAcceptanceState(WorkSessionAcceptanceState.BLOCKED);session.setSourceTreeFingerprintSha256(before);
            session.setSourceTreeObservedAt(now);session.setValidationProjectionSha256("c".repeat(64));
            session.setValidationDefinitionRevision("atenea-required-validation-v2");session.setAcceptanceBlockedCheck("ANDROID_BUILD");
            session.setAcceptanceNextAction("Inspect the retained validation failure");
            session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);session.setOpenedAt(now);session.setLastActivityAt(now);session.setCreatedAt(now);session.setUpdatedAt(now);
            sessions.saveAndFlush(session);sessionId=session.getId();validationId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO validation_operation(id,work_session_id,operation,status,source_tree_fingerprint_sha256,
                    definition_revision,identity_sha256,summary,started_at,finished_at,created_at,updated_at)
                VALUES(?,?,'ANDROID_BUILD','BLOCKED',?,'atenea-android-build-v2',?,
                    '[UNSUPPORTED_DEPENDENCY_MANIFEST] No se pudo empezar.',now(),now(),now(),now())
                """,validationId,sessionId,before,UUID.randomUUID().toString().replace("-","").repeat(2));
        });
        when(gateway.call(any(),eq(Action.DIAGNOSE))).thenReturn(proof("AVAILABLE",null));
        when(gateway.call(any(),eq(Action.PREPARE))).thenReturn(proof("READY",null));
        when(gateway.call(any(),eq(Action.APPLY))).thenReturn(proof("APPLIED",after));
        when(worker.fingerprintSourceTree(any())).thenAnswer(call->{var s=call.getArgument(0,WorkSessionEntity.class);
            return new RemoteWorkerClient.SourceTreeFingerprint("observed",s.getRemoteSessionId().toString(),s.getWorkspaceIdentity(),
                "atenea",head,after,1,0,0,false);});
        jdbc.update("UPDATE validation_operation SET artifact_manifest_sha256=? WHERE id=?","6".repeat(64),validationId);
        when(worker.validationDiagnostic(any(),anyString())).thenAnswer(call->{
            var s=call.getArgument(0,WorkSessionEntity.class);
            var value=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(getClass().getResourceAsStream("/remoteworker/validation-diagnostic-v1.json"));
            value.put("workspaceIdentity",s.getWorkspaceIdentity());
            var d=(com.fasterxml.jackson.databind.node.ObjectNode)value.get("diagnostic");
            d.put("operationId",validationId.toString());d.put("sessionId",s.getRemoteSessionId().toString());
            d.put("operation","ANDROID_BUILD");d.put("definitionRevision","atenea-android-build-v2");d.put("sourceTreeFingerprintSha256",before);
            return value;
        });
        clearInvocations(gateway,worker,agentRuns,coordinator);
    }
    @AfterEach void cleanup() {
        tx.executeWithoutResult(ignored->{
            jdbc.update("DELETE FROM mobile_validation_recovery WHERE session_id=?",sessionId);
            jdbc.update("DELETE FROM validation_operation WHERE work_session_id=?",sessionId);
            jdbc.update("DELETE FROM agent_run WHERE session_id=?",sessionId);jdbc.update("DELETE FROM session_turn WHERE session_id=?",sessionId);
            if(sessionId!=null){sessions.deleteById(sessionId);sessions.flush();}
            if(changeId!=null){changes.deleteById(changeId);changes.flush();}
            if(projectId!=null)projects.deleteById(projectId);if(operatorId!=null)operators.deleteById(operatorId);
            if(createdWorker){jdbc.update("DELETE FROM worker_codex_activation_barrier WHERE worker_id='ax42-01'");jdbc.update("DELETE FROM worker_node WHERE id='ax42-01'");}
        });
    }
    private ValidationRecoveryGateway.Proof proof(String state,String fingerprint) {
        var value=mapper.createObjectNode().put("state",state).put("receiptSha256","6".repeat(64));
        return new ValidationRecoveryGateway.Proof(state,"atenea-explicit-android-version-v1",fingerprint,"6".repeat(64),value);
    }
    private ValidationRecoveryStore.View ready() {
        var v=service.prepare(sessionId,actor);service.process(v.id());return store.get(v.id(),false).view();
    }
    @Test void readsDiagnoseWithoutCreatingIntentRunOrValidation() {
        assertTrue(service.observe(sessionId,actor).canPrepareRepair());assertTrue(service.observe(sessionId,actor).canPrepareRepair());
        assertEquals(0,store.list(sessionId).size());verify(gateway,times(2)).call(any(),eq(Action.DIAGNOSE));
        verifyNoInteractions(worker,agentRuns,coordinator);
    }
    @Test void diagnosticReadDoesNotCreateRunValidationOrRecoveryIntent() {
        assertEquals("RECORDED",service.diagnostic(sessionId,actor).path("failureDetailsState").asText());
        assertEquals(0,store.list(sessionId).size());verifyNoInteractions(agentRuns,coordinator);
        verify(worker).validationDiagnostic(any(),eq(validationId.toString()));
    }
    @Test void mismatchedDiagnosticCannotCreateInvestigation() {
        jdbc.update("UPDATE validation_operation SET artifact_manifest_sha256=? WHERE id=?","8".repeat(64),validationId);
        assertThrows(RemoteWorkerException.class,()->service.investigate(sessionId,actor));
        assertEquals(0,store.list(sessionId).size());verifyNoInteractions(agentRuns,coordinator);
    }
    @Test void prepareAndApplyAreSeparateIdempotentAndInvalidateHistoricalResults() {
        var v=service.prepare(sessionId,actor);assertEquals(v.id(),service.prepare(sessionId,actor).id());
        verify(gateway,never()).call(any(),eq(Action.APPLY));service.process(v.id());assertEquals("READY",store.get(v.id(),false).state());
        service.apply(sessionId,v.id(),actor);service.apply(sessionId,v.id(),actor);service.process(v.id());service.process(v.id());
        var current=store.get(v.id(),false);assertEquals("APPLIED",current.state());assertEquals(after,current.resultFingerprint());
        tx.executeWithoutResult(ignored->{var c=changes.findById(changeId).orElseThrow();assertEquals(2,c.getSourceRevision());
            assertEquals(after,c.getSourceFingerprintSha256());assertEquals(DevelopmentChangeProjectionState.STALE,c.getValidationState());
            assertEquals(WorkSessionAcceptanceState.DRAFT,sessions.findById(sessionId).orElseThrow().getAcceptanceState());});
        assertEquals("BLOCKED",jdbc.queryForObject("SELECT status FROM validation_operation WHERE id=?",String.class,validationId));
        assertEquals(1,store.list(sessionId).size());verify(gateway,times(1)).call(any(),eq(Action.APPLY));verifyNoInteractions(agentRuns,coordinator);
    }
    @Test void changedRevisionRefusesApplyBeforeRemoteEffect() {
        var v=ready();tx.executeWithoutResult(ignored->{var c=changes.findById(changeId).orElseThrow();c.setSourceRevision(2);c.setSourceFingerprintSha256("7".repeat(64));changes.saveAndFlush(c);});
        assertThrows(DeliveryRejectedException.class,()->service.apply(sessionId,v.id(),actor));verify(gateway,never()).call(any(),eq(Action.APPLY));
    }
    @Test void uncertainEffectRequiresExplicitRecoveryAndPreservesSameIntent() {
        var v=ready();service.apply(sessionId,v.id(),actor);
        when(gateway.call(any(),eq(Action.APPLY))).thenThrow(new RemoteWorkerException("lost response",504));
        assertThrows(RemoteWorkerException.class,()->service.process(v.id()));
        tx.executeWithoutResult(ignored->store.state(v.id(),"ATTENTION","VALIDATION_REPAIR_NEEDS_INSPECTION"));
        var original=store.get(v.id(),false).command();when(gateway.call(any(),eq(Action.INSPECT))).thenReturn(proof("APPLIED",after));
        assertEquals(v.id(),service.recover(sessionId,v.id(),actor).id());assertEquals(original,store.get(v.id(),false).command());
        when(gateway.call(any(),eq(Action.APPLY))).thenReturn(proof("APPLIED",after));service.process(v.id());
        assertEquals("APPLIED",store.get(v.id(),false).state());assertEquals(1,store.list(sessionId).size());
    }
    @Test void postconditionMismatchNeverPromotesSourceOrValidation() {
        var v=ready();service.apply(sessionId,v.id(),actor);
        doReturn(new RemoteWorkerClient.SourceTreeFingerprint("observed","foreign","foreign","atenea",head,after,1,0,0,false))
                .when(worker).fingerprintSourceTree(any());
        assertThrows(DeliveryRejectedException.class,()->service.process(v.id()));
        assertEquals(before,jdbc.queryForObject("SELECT source_fingerprint_sha256 FROM development_change WHERE id=?",String.class,changeId));
    }
    @Test void foreignProjectOrUnobservedFingerprintNeverPromotesSource() {
        var v=ready();service.apply(sessionId,v.id(),actor);
        var s=sessions.findById(sessionId).orElseThrow();
        for (var identity:List.of(new String[]{"observed","foreign"},new String[]{"pending","atenea"})) {
            doReturn(new RemoteWorkerClient.SourceTreeFingerprint(identity[0],s.getRemoteSessionId().toString(),
                s.getWorkspaceIdentity(),identity[1],head,after,1,0,0,false)).when(worker).fingerprintSourceTree(any());
            assertThrows(DeliveryRejectedException.class,()->service.process(v.id()));
        }
        assertEquals(before,jdbc.queryForObject("SELECT source_fingerprint_sha256 FROM development_change WHERE id=?",String.class,changeId));
    }
    @Test void activeRepairRejectsNewValidationUntilSafeCancellation() {
        var v=ready();service.apply(sessionId,v.id(),actor);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("""
            INSERT INTO validation_operation(id,work_session_id,operation,status,source_tree_fingerprint_sha256,
                definition_revision,identity_sha256,summary,started_at,created_at,updated_at)
            VALUES(?,?,'ANDROID_BUILD','RUNNING',?,'atenea-android-build-v2',?,'Synthetic concurrent work',now(),now(),now())
            """,UUID.randomUUID(),sessionId,before,"9".repeat(64)));
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM validation_operation WHERE work_session_id=?",Long.class,sessionId));
        tx.executeWithoutResult(ignored->store.state(v.id(),"ATTENTION","VALIDATION_REPAIR_NEEDS_INSPECTION"));
        when(gateway.call(any(),eq(Action.INSPECT))).thenReturn(proof("READY",null));
        assertEquals("CANCELLED",service.cancel(sessionId,v.id(),actor).state());
    }
    @Test void unknownFailureCannotUseKnownRecipeButInvestigationCreatesOnlyOneBoundRun() {
        jdbc.update("UPDATE validation_operation SET summary='[NEW_UNKNOWN_FAILURE] detail' WHERE id=?",validationId);
        assertThrows(DeliveryRejectedException.class,()->service.prepare(sessionId,actor));
        stubInvestigationRun();
        doAnswer(call->{
            var independent=new TransactionTemplate(manager);
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Long visible=independent.execute(ignored->jdbc.queryForObject(
                "SELECT count(*) FROM agent_run WHERE id=?",Long.class,call.getArgument(0,Long.class)));
            assertEquals(1L,visible.longValue());
            return null;
        }).when(coordinator).dispatchAfterCommit(anyLong());
        assertFalse(service.observe(sessionId,actor).canPrepareRepair());
        var first=service.investigate(sessionId,actor);var second=service.investigate(sessionId,actor);
        assertEquals(first.id(),second.id());assertEquals(first.runId(),second.runId());assertNotNull(first.runId());
        assertEquals("QUEUED",service.observe(sessionId,actor).investigation().runStatus());
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE session_id=?",Long.class,sessionId));
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM session_turn WHERE session_id=?",Long.class,sessionId));
        verify(agentRuns,times(1)).createRemoteQueuedRun(any(),any(),eq(WorkloadClass.NORMAL));
        verify(coordinator,times(1)).dispatchAfterCommit(first.runId());
        var prompt=jdbc.queryForObject("SELECT message_text FROM session_turn WHERE session_id=?",String.class,sessionId);
        assertTrue(prompt.contains("closed-validation-diagnostic/v1"));assertTrue(prompt.contains("testFailures"));
        assertTrue(prompt.contains("NO demuestra cuáles fallaron"));
        verify(worker,times(1)).validationDiagnostic(any(),eq(validationId.toString()));
        verify(gateway,never()).call(any(),eq(Action.PREPARE));
    }
    private void stubInvestigationRun() {
        when(agentRuns.createRemoteQueuedRun(any(),any(),eq(WorkloadClass.NORMAL))).thenAnswer(call->{
            // The existing factory owns profile/admission tests. Persist its
            // synthetic result here to exercise the real run FK and deduplication.
            var session=call.getArgument(0,WorkSessionEntity.class);var now=Instant.now();
            var run=new AgentRunEntity();run.setSession(session);run.setOriginTurn(call.getArgument(1));
            run.setStatus(AgentRunStatus.QUEUED);run.setExecutionTarget(ExecutionTarget.REMOTE);
            run.setTargetRepoPath("/synthetic/atenea");run.setWorkspaceIdentity(session.getWorkspaceIdentity());
            run.setSelectedWorkerId("ax42-01");run.setRemoteSessionId(session.getRemoteSessionId());
            run.setWorkloadKind(ProjectCodexIdentity.CHANGE_WORKLOAD_KIND);run.setProjectIdentity("atenea");
            run.setRepositoryUrl(ProjectCodexIdentity.REPOSITORY);run.setRepositoryBranch("main");run.setRepositoryCommit(head);
            run.setManifestSha256(ProjectCodexIdentity.MANIFEST_SHA256);
            run.setDevelopmentChangeKey(session.getDevelopmentChange().getChangeKey());run.setChangeBaseCommit(head);
            run.setChangeExpectedCanonicalCommit(head);run.setChangeSourceRevision(1L);run.setChangeSourceFingerprintSha256(before);
            run.setChangeWorkspaceOwnershipFingerprintSha256(before);run.setDispatchId(UUID.randomUUID());
            run.setStartedAt(now);run.setCreatedAt(now);return runs.saveAndFlush(run);
        });
    }
    @Test void investigationRolledBackBeforeCommitIsNeverDispatched() {
        stubInvestigationRun();
        tx.executeWithoutResult(status->{
            service.investigate(sessionId,actor);
            verifyNoInteractions(coordinator);
            status.setRollbackOnly();
        });
        verifyNoInteractions(coordinator);
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE session_id=?",Long.class,sessionId));
        assertEquals(0,store.list(sessionId).size());
    }
    @Test void lostDispatchCallbackResumesSameBoundQueuedRunWithoutCreatingWork() {
        stubInvestigationRun();var original=service.investigate(sessionId,actor);clearInvocations(coordinator,agentRuns);
        service.reconcile();
        verify(coordinator).dispatchAfterCommit(original.runId());verifyNoInteractions(agentRuns);
        assertEquals(original.runId(),store.get(original.id(),false).runId());
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM session_turn WHERE session_id=?",Long.class,sessionId));
        jdbc.update("UPDATE agent_run SET status='SUCCEEDED',finished_at=now() WHERE id=?",original.runId());
        clearInvocations(coordinator);service.reconcile();verifyNoInteractions(coordinator);
        assertEquals("SUCCEEDED",service.observe(sessionId,actor).investigation().runStatus());
        jdbc.update("UPDATE development_change SET source_revision=2,source_fingerprint_sha256=? WHERE id=?",after,changeId);
        assertEquals(original.runId(),service.observe(sessionId,actor).investigation().runId());
    }
    @Test void queuedInvestigationWithChangedOwnershipOrRevisionIsNeverResumed() {
        stubInvestigationRun();var original=service.investigate(sessionId,actor);clearInvocations(coordinator,agentRuns);
        jdbc.update("UPDATE agent_run SET change_source_fingerprint_sha256=? WHERE id=?","7".repeat(64),original.runId());
        service.reconcile();verifyNoInteractions(coordinator,agentRuns);
        assertNull(service.observe(sessionId,actor).investigation().runStatus());
        jdbc.update("UPDATE agent_run SET change_source_fingerprint_sha256=? WHERE id=?",before,original.runId());
        jdbc.update("UPDATE development_change SET source_revision=2 WHERE id=?",changeId);
        service.reconcile();verifyNoInteractions(coordinator,agentRuns);
    }
    @Test void callerRoleSessionAndOperationOwnershipAreRechecked() {
        var v=ready();assertThrows(RuntimeException.class,()->service.apply(sessionId+10000,v.id(),actor));
        tx.executeWithoutResult(ignored->{var op=operators.findById(operatorId).orElseThrow();op.setCodexOperationsRole(CodexOperationsRole.ROUTINE_OPERATOR);operators.saveAndFlush(op);});
        assertEquals("PLATFORM_ADMINISTRATOR_REQUIRED",assertThrows(DeliveryRejectedException.class,()->service.apply(sessionId,v.id(),actor)).code());
        verify(gateway,never()).call(any(),eq(Action.APPLY));
    }
    @Test void preparationCanBeDiscardedButPartiallyAppliedRepairCannot() {
        var v=ready();when(gateway.call(any(),eq(Action.INSPECT))).thenReturn(proof("APPLYING",null));
        assertThrows(DeliveryRejectedException.class,()->service.cancel(sessionId,v.id(),actor));
        when(gateway.call(any(),eq(Action.INSPECT))).thenReturn(proof("READY",null));assertEquals("CANCELLED",service.cancel(sessionId,v.id(),actor).state());
        verify(gateway,never()).call(any(),eq(Action.APPLY));
    }
    @Test void immutableIntentCannotBeRetargetedAndMigrationIsSuccessful() {
        var v=ready();assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE mobile_validation_recovery SET source_revision=99 WHERE id=?",v.id()));
        // V99 must be installed successfully; later expand migrations are legitimate.
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='99' AND success",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success",Long.class));
    }
}
