package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.codexoperations.CodexExecutionProfileSnapshotService;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubMergeState;
import com.atenea.persistence.auth.*;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.*;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.*;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateCommand.Action;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateGateway.Preparation;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateGateway.State;
import com.atenea.service.developmentchange.DevelopmentChangeAgentRunSourceAdvanceService;
import com.atenea.service.worksession.AgentRunService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** All rows/credentials/workers here are synthetic in the caller-owned ephemeral PG16 DB. */
@SpringBootTest(properties={"atenea.auth.bootstrap.enabled=false", "atenea.remote-worker.enabled=true"})
@AutoConfigureMockMvc
class SourceUpdateIntegrationTest {
    @Autowired SourceUpdateService service;
    @Autowired SourceUpdateStore store;
    @Autowired WorkSessionRepository sessions;
    @Autowired DevelopmentChangeRepository changes;
    @Autowired ProjectRepository projects;
    @Autowired OperatorRepository operators;
    @Autowired AgentRunRepository runs;
    @Autowired SessionTurnRepository turns;
    @Autowired AgentRunService agentRuns;
    @Autowired DevelopmentChangeAgentRunSourceAdvanceService advance;
    @Autowired com.atenea.service.worksession.ClosedValidationOperationService validations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc mvc;
    @MockBean DevelopmentChangeSourceUpdateGateway gateway;
    @MockBean RemoteRoutingSelector routing;
    @MockBean RemoteWorkerClient remoteClient;
    @MockBean RemoteAgentRunCoordinator coordinator;
    @MockBean CodexExecutionProfileSnapshotService profiles;
    @MockBean GitHubClient github;
    @MockBean ReleaseControlClient executor;
    @MockBean(name="mobileDeliveryScheduler") ThreadPoolTaskScheduler deliveryScheduler;
    Long sessionId, changeId, projectId, operatorId;
    UUID validationId;
    AuthenticatedOperator actor;
    TransactionTemplate tx;
    boolean createdWorker;
    final String base="1".repeat(40), published="2".repeat(40), main="3".repeat(40);
    final String oldFingerprint="4".repeat(64), preparedFingerprint="5".repeat(64), receipt="6".repeat(64);

    @BeforeEach void fixture() {
        tx=new TransactionTemplate(manager);
        when(executor.enabled()).thenReturn(true);
        when(routing.refreshKnownWorker(eq("ax42-01"), anyString())).thenReturn(true);
        when(github.extractPullRequestNumber(anyString())).thenReturn(47L);
        when(github.observeMergeState(any(), eq(47L), anyString(), eq(published))).thenReturn(GitHubMergeState.CONFLICTS);
        when(github.canonicalMain(any())).thenReturn(main);
        tx.executeWithoutResult(ignored -> {
            var now=Instant.now();
            createdWorker=jdbc.update("""
                    INSERT INTO worker_node(id,protocol_version,endpoint,enabled,healthy,normal_capacity,heavy_capacity,capabilities)
                    VALUES ('ax42-01','agent-run-worker/v1','http://synthetic.invalid',true,true,1,1,'project-codex-v4')
                    ON CONFLICT (id) DO NOTHING
                    """) == 1;
            var operator=new OperatorEntity(); operator.setEmail(UUID.randomUUID()+"@atenea.test");
            operator.setDisplayName("Synthetic admin"); operator.setPasswordHash("synthetic"); operator.setActive(true);
            operator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
            operator.setCreatedAt(now); operator.setUpdatedAt(now); operators.saveAndFlush(operator); operatorId=operator.getId();
            actor=new AuthenticatedOperator(operatorId, operator.getEmail(), operator.getDisplayName());
            var project=new ProjectEntity(); project.setName("Atenea"); project.setRepoPath("/synthetic/atenea");
            project.setDefaultBaseBranch("main"); project.setCreatedAt(now); project.setUpdatedAt(now);
            projects.saveAndFlush(project); projectId=project.getId();
            var change=new DevelopmentChangeEntity(); change.setChangeKey(UUID.randomUUID()); change.setProject(project);
            change.setTitle("Synthetic conflict recovery"); change.setBaseRef("refs/heads/main"); change.setBaseCommit(base);
            change.setProjectPolicyRevision(1);
            change.setWorkspaceBranch("atenea/change-"+change.getChangeKey());
            change.setWorkspaceIdentity("remote:ax42-01:change:"+change.getChangeKey()); change.setSelectedWorkerId("ax42-01");
            change.setSourceRevision(3); change.setObservedCanonicalCommit(base); change.setSourceFingerprintSha256(oldFingerprint);
            change.setSourceState(DevelopmentChangeSourceState.DIRTY); change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
            change.setWorkspaceOperationRevision(1); change.setWorkspaceUpdatedAt(now);
            change.setWorkspaceObservationSha256("a".repeat(64)); change.setWorkspaceOwnershipFingerprintSha256(oldFingerprint);
            change.setValidationState(DevelopmentChangeProjectionState.CURRENT); change.setReviewState(DevelopmentChangeProjectionState.CURRENT);
            change.setIntegrationState(DevelopmentChangeProjectionState.CURRENT); change.setReleaseState(DevelopmentChangeProjectionState.CURRENT);
            change.setCreatedAt(now); change.setUpdatedAt(now); changes.saveAndFlush(change); changeId=change.getId();
            var session=new WorkSessionEntity(); session.setProject(project); session.setDevelopmentChange(change);
            session.setStatus(WorkSessionStatus.OPEN); session.setTitle(change.getTitle()); session.setBaseBranch("main");
            session.setWorkspaceBranch(change.getWorkspaceBranch()); session.setWorkspaceIdentity(change.getWorkspaceIdentity());
            session.setExecutionTarget(ExecutionTarget.REMOTE); session.setSelectedWorkerId("ax42-01");
            session.setRemoteCloseState(RemoteCloseState.NOT_STARTED);
            session.setRemoteSessionId(UUID.randomUUID()); session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
            session.setAcceptanceState(WorkSessionAcceptanceState.VALIDATED); session.setSourceTreeFingerprintSha256(oldFingerprint);
            session.setValidationProjectionSha256("b".repeat(64)); session.setValidationDefinitionRevision("synthetic-v1");
            session.setValidatedAt(now);
            session.setSourceTreeObservedAt(now); session.setPublishedChangeKey(change.getChangeKey()); session.setPublishedSourceRevision(3L);
            session.setPublishedSourceFingerprintSha256(oldFingerprint); session.setPublishedWorkspaceOwnershipFingerprintSha256(oldFingerprint);
            session.setPublicationReceiptSha256(receipt); session.setPublishedRepository("jlnieto/atenea");
            session.setPublishedBaseBranch("main"); session.setPublishedHeadBranch(change.getWorkspaceBranch());
            session.setFinalCommitSha(published); session.setPullRequestUrl("https://github.com/jlnieto/atenea/pull/47");
            session.setPullRequestStatus(WorkSessionPullRequestStatus.OPEN);
            session.setOpenedAt(now); session.setLastActivityAt(now); session.setCreatedAt(now); session.setUpdatedAt(now);
            sessions.saveAndFlush(session); sessionId=session.getId();
            validationId=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO validation_operation (id,work_session_id,operation,status,source_tree_fingerprint_sha256,
                        definition_revision,identity_sha256,exit_code,duration_millis,started_at,finished_at,created_at,updated_at)
                    VALUES (?,?,'BACKEND_TEST','SUCCEEDED',?,'synthetic',?,0,1,now(),now(),now(),now())
                    """, validationId, sessionId, oldFingerprint, UUID.randomUUID().toString().replace("-", "").repeat(2));
        });
    }
    @AfterEach void cleanup() {
        tx.executeWithoutResult(ignored -> {
            jdbc.update("DELETE FROM mobile_source_update_operation WHERE session_id=?",sessionId);
            jdbc.update("DELETE FROM validation_operation WHERE work_session_id=?",sessionId);
            jdbc.update("DELETE FROM agent_run WHERE session_id=?",sessionId);
            jdbc.update("DELETE FROM session_turn WHERE session_id=?",sessionId);
            if (sessionId != null) { sessions.deleteById(sessionId); sessions.flush(); }
            if (changeId != null) { changes.deleteById(changeId); changes.flush(); }
            if (projectId != null) projects.deleteById(projectId);
            if (operatorId != null) operators.deleteById(operatorId);
            if (createdWorker) {
                jdbc.update("DELETE FROM worker_codex_activation_barrier WHERE worker_id='ax42-01'");
                jdbc.update("DELETE FROM worker_node WHERE id='ax42-01'");
            }
        });
    }
    private SourceUpdateOperation queued() {
        var view=service.request(sessionId, actor);
        return store.get(view.id(), false);
    }
    private Preparation prepared() {
        return new Preparation(State.NEEDS_RESOLUTION,"7".repeat(40),List.of("src/test/java/Example.java"),preparedFingerprint,"8".repeat(64));
    }
    private SourceUpdateOperation prepare() {
        var op=queued(); when(gateway.exchange(op.command(),Action.PREPARE)).thenReturn(prepared());
        service.reconcile(op.id()); return store.get(op.id(),false);
    }
    private SourceUpdateOperation resolving() {
        var op=prepare(); service.reconcile(op.id()); return store.get(op.id(),false);
    }
    private void expire(UUID id) {
        jdbc.update("UPDATE mobile_source_update_operation SET lease_until=now()-interval '1 second' WHERE id=?",id);
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor auth() {
        return authentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }
    @Test void queuesOnePinnedIntentAndInvalidatesAcceptanceWithoutCallingWorker() {
        var op=queued(); var duplicate=service.request(sessionId,actor);
        assertEquals(op.id(),duplicate.id()); assertEquals(main,op.command().targetMainCommit());
        assertEquals(published,op.command().owner().sourceCommit()); assertEquals(base,op.command().owner().baseCommit());
        assertNull(op.command().owner().sourceFingerprintSha256());
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM mobile_source_update_operation WHERE session_id=?",Long.class,sessionId));
        assertEquals(3L,jdbc.queryForObject("SELECT source_revision FROM development_change WHERE id=?",Long.class,changeId));
        assertEquals("STALE",jdbc.queryForObject("SELECT validation_state FROM development_change WHERE id=?",String.class,changeId));
        assertEquals("DRAFT",jdbc.queryForObject("SELECT acceptance_state FROM work_session WHERE id=?",String.class,sessionId));
        verifyNoInteractions(gateway); verify(coordinator,never()).dispatchAfterCommit(anyLong());
    }
    @Test void preparationAndResolverStayInSameSessionPreservePublicationAndHistoricalValidation() {
        var op=resolving();
        assertEquals("RESOLVING",op.state()); assertNotNull(op.resolverRunId()); assertEquals(4L,op.preparedRevision());
        tx.executeWithoutResult(ignored -> {
            var run=runs.findById(op.resolverRunId()).orElseThrow();
            assertEquals(sessionId,run.getSession().getId()); assertEquals(SessionTurnActor.ATENEA,run.getOriginTurn().getActor());
            assertFalse(run.getOriginTurn().isInternal()); assertTrue(run.getOriginTurn().getMessageText().contains(main));
            assertTrue(run.getOriginTurn().getMessageText().contains("Example.java"));
            assertEquals(4L,run.getChangeSourceRevision()); assertEquals(published,run.getRepositoryCommit());
            assertEquals(base,run.getChangeBaseCommit()); assertEquals(preparedFingerprint,run.getChangeSourceFingerprintSha256());
        });
        assertEquals(published,jdbc.queryForObject("SELECT final_commit_sha FROM work_session WHERE id=?",String.class,sessionId));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM validation_operation WHERE id=?",String.class,validationId));
        service.reconcile(op.id()); expire(op.id()); service.reconcile(op.id());
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE session_id=?",Long.class,sessionId));
        verify(coordinator,times(1)).dispatchAfterCommit(op.resolverRunId());
    }
    @Test void lostResponseIsInspectedWithSameIntentBeforeAnyNewEffectAndPollingIsReadOnly() {
        var op=queued();
        when(gateway.exchange(op.command(),Action.PREPARE)).thenThrow(new RemoteWorkerException("synthetic lost reply",new java.io.IOException()));
        service.reconcile(op.id()); assertEquals("UNCERTAIN",store.get(op.id(),false).state());
        service.observe(sessionId,actor); service.observe(sessionId,actor);
        verify(gateway,times(1)).exchange(any(),any());
        when(gateway.exchange(op.command(),Action.INSPECT)).thenReturn(prepared());
        expire(op.id()); service.reconcile(op.id());
        assertEquals("READY_TO_RESOLVE",store.get(op.id(),false).state());
        var order=inOrder(gateway); order.verify(gateway).exchange(op.command(),Action.PREPARE);
        order.verify(gateway).exchange(op.command(),Action.INSPECT);
        verify(gateway,times(1)).exchange(op.command(),Action.PREPARE);
    }
    @Test void partiallyPreparedEvidenceUsesReconcileNotReplacement() {
        var op=queued();
        when(gateway.exchange(op.command(),Action.PREPARE)).thenReturn(new Preparation(State.PREPARED,"7".repeat(40),List.of("x"),null,"8".repeat(64)));
        when(gateway.exchange(op.command(),Action.RECONCILE)).thenReturn(prepared());
        service.reconcile(op.id()); assertEquals("READY_TO_RESOLVE",store.get(op.id(),false).state());
        verify(gateway).exchange(op.command(),Action.RECONCILE);
    }
    @Test void deterministicMovedRefRejectionRequiresAbsentProofThenStopsWithoutNewPrepare() {
        var op=queued();
        var error=new RemoteWorkerException("synthetic rejection",409,"SOURCE_UPDATE_REF_MOVED",RemoteWorkerFailureCategory.OWNERSHIP,
                false,AgentRunRecoveryNextAction.REQUEST_RECONCILIATION,null);
        when(gateway.exchange(op.command(),Action.PREPARE)).thenThrow(error);
        service.reconcile(op.id());
        when(gateway.exchange(op.command(),Action.INSPECT)).thenReturn(new Preparation(State.ABSENT,null,List.of(),null,null));
        expire(op.id()); service.reconcile(op.id());
        assertEquals("BLOCKED",store.get(op.id(),false).state());
        verify(gateway,times(1)).exchange(op.command(),Action.PREPARE); verify(coordinator,never()).dispatchAfterCommit(anyLong());
    }
    @Test void noConflictPreparationDoesNotInventResolverOrValidation() {
        var op=queued();
        when(gateway.exchange(op.command(),Action.PREPARE)).thenReturn(new Preparation(State.READY_TO_FINALIZE,"7".repeat(40),List.of(),preparedFingerprint,"8".repeat(64)));
        service.reconcile(op.id()); assertEquals("READY_TO_FINALIZE",store.get(op.id(),false).state());
        assertNull(store.get(op.id(),false).resolverRunId()); verify(coordinator,never()).dispatchAfterCommit(anyLong());
        assertEquals("STALE",jdbc.queryForObject("SELECT validation_state FROM development_change WHERE id=?",String.class,changeId));
    }
    @Test void completionAdvancesSourceButDoesNotValidatePublishOrMerge() {
        var op=resolving();
        tx.executeWithoutResult(ignored -> {
            var run=runs.findById(op.resolverRunId()).orElseThrow(); run.setRemoteExecutionId(UUID.randomUUID().toString());
            advance.advance(run,new RemoteWorkerClient.SourceIdentity(run.getDevelopmentChangeKey().toString(),sessionId,
                    run.getRemoteSessionId().toString(),run.getWorkspaceIdentity(),run.getRemoteExecutionId(),published,"9".repeat(64),true),Instant.now());
            run.setStatus(AgentRunStatus.SUCCEEDED); run.setProcessOutcome(AgentRunProcessOutcome.SUCCEEDED);
            run.setFinishedAt(Instant.now()); runs.saveAndFlush(run);
        });
        expire(op.id()); service.reconcile(op.id());
        assertEquals("RESOLVER_COMPLETED",store.get(op.id(),false).state());
        assertEquals(5L,service.observe(sessionId,actor).sourceRevision());
        assertEquals("9".repeat(64),store.get(op.id(),false).resultFingerprintSha256());
        assertEquals(5L,jdbc.queryForObject("SELECT source_revision FROM development_change WHERE id=?",Long.class,changeId));
        assertEquals("STALE",jdbc.queryForObject("SELECT validation_state FROM development_change WHERE id=?",String.class,changeId));
        assertEquals(3L,jdbc.queryForObject("SELECT published_source_revision FROM work_session WHERE id=?",Long.class,sessionId));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM mobile_delivery_operation WHERE session_id=?",Long.class,sessionId));
    }
    @Test void failedResolverIsRetainedAndNeverAutomaticallyDuplicated() {
        var op=resolving();
        tx.executeWithoutResult(ignored -> {
            var run=runs.findById(op.resolverRunId()).orElseThrow(); run.setStatus(AgentRunStatus.FAILED);
            run.setProcessOutcome(AgentRunProcessOutcome.FAILED);
            run.setFinishedAt(Instant.now()); runs.saveAndFlush(run);
        });
        expire(op.id()); service.reconcile(op.id()); service.reconcile(op.id());
        assertEquals("FAILED",store.get(op.id(),false).state());
        assertEquals(op.id(),service.request(sessionId,actor).id());
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE session_id=?",Long.class,sessionId));
    }
    @Test void revokedRoleStopsResolverWithoutCreatingVisibleTurnOrRun() {
        var op=prepare(); jdbc.update("UPDATE operator_account SET codex_operations_role='ROUTINE_OPERATOR' WHERE id=?",operatorId);
        service.reconcile(op.id()); assertEquals("ATTENTION",store.get(op.id(),false).state());
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM session_turn WHERE session_id=?",Long.class,sessionId));
        verify(coordinator,never()).dispatchAfterCommit(anyLong());
    }
    @Test void databaseGuardsBlockOrdinaryRunsValidationAndIntentTampering() {
        var op=queued();
        var error=assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update(
                "UPDATE validation_operation SET status='RUNNING',finished_at=NULL,exit_code=NULL,duration_millis=NULL WHERE id=?",validationId));
        assertTrue(error.getMessage().contains("SOURCE_UPDATE_IN_PROGRESS"));
        error=assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update(
                "UPDATE mobile_source_update_operation SET command_json='{}'::jsonb WHERE id=?",op.id()));
        assertTrue(error.getMessage().contains("SOURCE_UPDATE_IMMUTABLE_EVIDENCE"));
        tx.executeWithoutResult(ignored -> {
            var session=sessions.findById(sessionId).orElseThrow(); var turn=new SessionTurnEntity();
            turn.setSession(session); turn.setActor(SessionTurnActor.OPERATOR); turn.setMessageText("synthetic history"); turn.setCreatedAt(Instant.now());
            turns.saveAndFlush(turn);
            jdbc.update("""
                    INSERT INTO agent_run(session_id,origin_turn_id,status,process_outcome,target_repo_path,workspace_identity,started_at,finished_at)
                    VALUES (?,?,'FAILED','FAILED','/synthetic','local:synthetic',now(),now())
                    """,sessionId,turn.getId());
        });
        error=assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update(
                "UPDATE agent_run SET status='RUNNING',finished_at=NULL WHERE session_id=?",sessionId));
        assertTrue(error.getMessage().contains("SOURCE_UPDATE_IN_PROGRESS"));
        error=assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update(
                "UPDATE work_session SET status='CLOSED',closed_at=now() WHERE id=?",sessionId));
        assertTrue(error.getMessage().contains("SOURCE_UPDATE_IN_PROGRESS"));
        error=assertThrows(org.springframework.dao.DataAccessException.class,() -> jdbc.update("""
                INSERT INTO development_change_workspace_operation(operation_id,operator_id,project_id,development_change_id,
                    idempotency_key,operation_kind,request_fingerprint_sha256,target_fingerprint_sha256,
                    expected_source_revision,expected_source_fingerprint_sha256,expected_canonical_commit,requested_at,updated_at)
                VALUES (?,?,?,?,?,'INSPECT',?,?,3,?,?,now(),now())
                """,UUID.randomUUID(),operatorId,projectId,changeId,UUID.randomUUID(),receipt,receipt,oldFingerprint,base));
        assertTrue(error.getMessage().contains("SOURCE_UPDATE_IN_PROGRESS"));
    }
    @Test void mobileActionRejectsCallerCommandsPathsAndVersions() throws Exception {
        for (String json:List.of("{\"command\":\"git merge\"}","{\"path\":\"/tmp\"}","{\"targetMainCommit\":\"arbitrary\"}")) {
            mvc.perform(post("/api/mobile/sessions/{id}/delivery/resolve-conflicts",sessionId).with(auth())
                    .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isConflict());
        }
        verifyNoInteractions(gateway);
    }
    @Test void movedOwnerIsStoppedBeforeWorkerEffectAndRetainsSameIntent() {
        var op=queued();
        jdbc.update("UPDATE work_session SET final_commit_sha=? WHERE id=?","f".repeat(40),sessionId);
        service.reconcilePending();
        assertEquals("ATTENTION",store.get(op.id(),false).state()); verifyNoInteractions(gateway);
    }
    @Test void cleanPreparationRetainsRequiredSourceHashAndNeverFabricatesSuccessfulValidation() {
        var op=queued();
        when(gateway.exchange(op.command(),Action.PREPARE)).thenReturn(new Preparation(State.READY_TO_FINALIZE,"7".repeat(40),List.of(),null,"8".repeat(64)));
        service.reconcile(op.id());
        assertEquals("READY_TO_FINALIZE",store.get(op.id(),false).state());
        assertEquals("CLEAN",jdbc.queryForObject("SELECT source_state FROM development_change WHERE id=?",String.class,changeId));
        assertEquals(oldFingerprint,jdbc.queryForObject("SELECT source_fingerprint_sha256 FROM development_change WHERE id=?",String.class,changeId));
        assertEquals("STALE",jdbc.queryForObject("SELECT validation_state FROM development_change WHERE id=?",String.class,changeId));
    }
    @Test void preparationClaimIsCommittedBeforeAuthenticatedWorkerEffect() {
        var op=queued();
        when(gateway.exchange(op.command(),Action.PREPARE)).thenAnswer(ignored -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("PREPARE_CLAIMED",store.get(op.id(),false).state());
            return prepared();
        });
        service.reconcile(op.id()); assertEquals("READY_TO_RESOLVE",store.get(op.id(),false).state());
    }
    @Test void historicalValidationCannotBePromotedWhilePreparationIsPending() {
        queued();
        assertThrows(com.atenea.service.worksession.WorkSessionOperationBlockedException.class,
                () -> validations.advanceDevelopmentChange(sessionId));
        verifyNoInteractions(remoteClient);
        assertEquals("STALE",jdbc.queryForObject("SELECT validation_state FROM development_change WHERE id=?",String.class,changeId));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM validation_operation WHERE id=?",String.class,validationId));
    }
    @Test void attentionDoesNotPreventExistingResolverLifecycleFromReachingTerminal() {
        var op=resolving();
        store.state(op.id(),"ATTENTION","SOURCE_UPDATE_OWNER_OR_STATE_CHANGED");
        tx.executeWithoutResult(ignored -> {
            var run=runs.findById(op.resolverRunId()).orElseThrow();
            run.setStatus(AgentRunStatus.RECONCILING); runs.saveAndFlush(run);
            run.setStatus(AgentRunStatus.CANCELLED); run.setProcessOutcome(AgentRunProcessOutcome.CANCELLED);
            run.setFinishedAt(Instant.now()); runs.saveAndFlush(run);
        });
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM agent_run WHERE id=?",String.class,op.resolverRunId()));
        assertEquals("ATTENTION",store.get(op.id(),false).state());
        verify(coordinator,times(1)).dispatchAfterCommit(op.resolverRunId());
    }
    @Test void readOnlyDeliveryExposesSameOperationAndRoutineRoleIsRejected() throws Exception {
        var op=queued();
        mvc.perform(get("/api/mobile/sessions/{id}/delivery",sessionId).with(auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceUpdate.id").value(op.id().toString()))
                .andExpect(jsonPath("$.sourceUpdate.sessionId").value(sessionId));
        jdbc.update("UPDATE operator_account SET codex_operations_role='ROUTINE_OPERATOR' WHERE id=?",operatorId);
        mvc.perform(post("/api/mobile/sessions/{id}/delivery/resolve-conflicts",sessionId).with(auth())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(gateway);
    }
}
