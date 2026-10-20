package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.*;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.*;
import com.atenea.service.worksession.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MobileChangeCompletionServiceTest {
    @Test void pendingAndroidCannotCloseTheSameTicketButPublishedApkUnlocksCompletion() {
        var pending=new ChangeDeliveryReadiness(21L,"CONFIRMED",session.getFinalCommitSha(),"4".repeat(40),
                List.of(DeliveryTarget.APP_PROD,DeliveryTarget.ANDROID_STABLE),false,deployed,null,"NOT_INCLUDED",null,Instant.now().getEpochSecond());
        when(delivery.observeDelivery(eq(21L),any())).thenReturn(pending);
        assertEquals("ANDROID_DELIVERY_REQUIRED",assertThrows(DeliveryRejectedException.class,() -> service.request(21L,actor,false)).code());
        assertEquals(WorkSessionStatus.OPEN,session.getStatus());assertNull(durable.get());verifyNoInteractions(close);
        var apk=new ReleaseControlClient.AndroidObservation("8".repeat(40),"0.6.12",1000012,"a".repeat(64),"b".repeat(64),
                Instant.now().getEpochSecond(),UUID.randomUUID(),UUID.randomUUID(),"c".repeat(64));
        when(delivery.observeDelivery(eq(21L),any())).thenReturn(new ChangeDeliveryReadiness(21L,"CONFIRMED",session.getFinalCommitSha(),"4".repeat(40),
                pending.requiredTargets(),true,deployed,apk,"INCLUDED",null,Instant.now().getEpochSecond()));
        service.request(21L,actor,false);service.advance(id);
        assertEquals("SUCCEEDED",durable.get().state());assertEquals(WorkSessionStatus.CLOSED,session.getStatus());
        assertEquals(1000012,durable.get().evidence().path("requiredDeliveryAfter").path("android").path("versionCode").asInt());
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeliveryStore store = mock(DeliveryStore.class);
    private final WorkSessionRepository sessions = mock(WorkSessionRepository.class);
    private final DevelopmentChangeRepository changes = mock(DevelopmentChangeRepository.class);
    private final OperatorRepository operators = mock(OperatorRepository.class);
    private final DevelopmentChangeBranchPublicationService ownership = mock(DevelopmentChangeBranchPublicationService.class);
    private final MobileDeliveryService delivery = mock(MobileDeliveryService.class);
    private final DevelopmentChangeWorkspaceGateway workspaces = mock(DevelopmentChangeWorkspaceGateway.class);
    private final WorkSessionService close = mock(WorkSessionService.class);
    private final RemoteWorkerProperties properties = mock(RemoteWorkerProperties.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final AuthenticatedOperator actor = new AuthenticatedOperator(7L,"completion@example.test","Test");
    private final AtomicReference<DeliveryOperation> durable = new AtomicReference<>();
    private final UUID id = UUID.randomUUID(), execution = UUID.randomUUID(), changeKey = UUID.randomUUID();
    private WorkSessionEntity session;
    private DevelopmentChangeEntity change;
    private OperatorEntity operator;
    private MobileChangeCompletionService service;
    private MobileDeliveryService.DeploymentObservation deployed;

    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(delivery.isEnabled()).thenReturn(true);
        when(properties.isRemoteCloseReleaseEnabledFor("atenea")).thenReturn(true);
        operator=new OperatorEntity(); operator.setId(7L); operator.setActive(true);
        operator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        when(operators.findById(7L)).thenReturn(Optional.of(operator));
        ProjectEntity project=new ProjectEntity(); project.setId(1L); project.setName("Atenea");
        project.setDefaultBaseBranch("main"); project.setRepoPath("/workspace/repos/internal/atenea");
        change=new DevelopmentChangeEntity(); change.setId(42L); change.setProject(project); change.setChangeKey(changeKey);
        change.setStatus(DevelopmentChangeStatus.OPEN); change.setSourceRevision(5L); change.setSourceFingerprintSha256("5".repeat(64));
        change.setBaseCommit("1".repeat(40)); change.setObservedCanonicalCommit("2".repeat(40));
        change.setWorkspaceBranch("atenea/change-"+changeKey); change.setWorkspaceIdentity("remote:ax42-01:change:"+changeKey);
        change.setSelectedWorkerId("ax42-01"); change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        change.setValidationState(DevelopmentChangeProjectionState.CURRENT); change.setSourceState(DevelopmentChangeSourceState.DIRTY);
        session=new WorkSessionEntity(); session.setId(21L); session.setProject(project); session.setDevelopmentChange(change);
        session.setStatus(WorkSessionStatus.OPEN); session.setRemoteSessionId(UUID.randomUUID()); session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId("ax42-01"); session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setWorkspaceBranch(change.getWorkspaceBranch()); session.setWorkspaceIdentity(change.getWorkspaceIdentity());
        session.setBaseBranch("main"); session.setPublishedChangeKey(changeKey); session.setPublishedSourceRevision(5L);
        session.setPublishedSourceFingerprintSha256("5".repeat(64)); session.setSourceTreeFingerprintSha256("5".repeat(64));
        session.setPublishedRepository("jlnieto/atenea"); session.setPublishedBaseBranch("main"); session.setPublishedHeadBranch(change.getWorkspaceBranch());
        session.setPublicationReceiptSha256("6".repeat(64)); session.setFinalCommitSha("3".repeat(40));
        session.setAcceptanceState(WorkSessionAcceptanceState.INTEGRATION_READY); session.setPullRequestStatus(WorkSessionPullRequestStatus.MERGED);
        session.setPullRequestUrl("https://github.com/jlnieto/atenea/pull/47"); session.setIntegrationReadyAt(Instant.now());
        session.setValidatedAt(Instant.now()); session.setSourceTreeObservedAt(Instant.now());
        session.setValidationProjectionSha256("7".repeat(64)); session.setValidationDefinitionRevision("closed-v1");
        session.setRemoteCloseState(RemoteCloseState.NOT_STARTED);
        when(sessions.findLockedWithProjectAndDevelopmentChangeById(21L)).thenReturn(Optional.of(session));
        when(sessions.findWithProjectAndDevelopmentChangeById(21L)).thenReturn(Optional.of(session));
        when(changes.findByChangeKeyForUpdate(changeKey)).thenReturn(Optional.of(change));
        DeliveryOperation integrated=new DeliveryOperation(UUID.randomUUID(),21L,7L,"INTEGRATE",DeliveryTarget.APP_PROD,
                session.getFinalCommitSha(),UUID.randomUUID(),"SUCCEEDED",null,
                mapper.createObjectNode().put("mergeCommit","4".repeat(40)),null,Instant.now(),Instant.now());
        when(store.integrated(21L,session.getFinalCommitSha())).thenReturn(Optional.of(integrated));
        when(store.completion(21L)).thenAnswer(call -> Optional.ofNullable(durable.get()));
        when(store.create(eq(21L),eq(7L),eq("FINALIZE"),eq(DeliveryTarget.APP_PROD),anyString(),eq("QUEUED"),any()))
            .thenAnswer(call -> {
                var op=new DeliveryOperation(id,21L,7L,"FINALIZE",DeliveryTarget.APP_PROD,call.getArgument(4),execution,
                        "QUEUED",null,call.getArgument(6),null,Instant.now(),Instant.now());
                durable.set(op); return op;
            });
        when(store.get(eq(id),anyBoolean())).thenAnswer(call -> durable.get());
        doAnswer(call -> {
            DeliveryOperation op=call.getArgument(0);
            durable.set(new DeliveryOperation(op.id(),op.sessionId(),op.operatorId(),op.kind(),op.target(),op.sourceCommit(),
                    op.executionId(),call.getArgument(1),call.getArgument(3),call.getArgument(4),call.getArgument(2),op.createdAt(),Instant.now()));
            return null;
        }).when(store).update(any(),anyString(),any(),any(),any());
        deployed=new MobileDeliveryService.DeploymentObservation(21L,"DEPLOYED","OPERATOR","8".repeat(40),"4".repeat(40),
                true,Instant.now().getEpochSecond(),UUID.randomUUID(),UUID.randomUUID(),"9".repeat(64),null);
        when(delivery.observeDeployment(eq(21L),any())).thenReturn(deployed);
        when(delivery.observeDelivery(eq(21L),any())).thenAnswer(call ->
                DeliveryReadinessFixture.backend(delivery.observeDeployment(21L,actor),session.getFinalCommitSha()));
        when(workspaces.execute(any())).thenReturn(clean());
        when(close.closeSession(21L)).thenAnswer(call -> { release(); return null; });
        service=new MobileChangeCompletionService(store,sessions,changes,operators,ownership,delivery,workspaces,close,properties,mapper,manager);
    }
    private DevelopmentChangeWorkspaceObservation clean() {
        return new DevelopmentChangeWorkspaceObservation(DevelopmentChangeWorkspaceObservation.Disposition.OWNED,
                session.getFinalCommitSha(),null,false,false,"a".repeat(64));
    }
    private void release() {
        session.setStatus(WorkSessionStatus.CLOSED); session.setRemoteCloseState(RemoteCloseState.RELEASED);
        session.setRemoteCloseOperationId(UUID.randomUUID()); session.setRemoteCloseReceiptSha256("b".repeat(64));
        session.setRemoteCloseRevision(6); session.setRemoteCloseReleasedAt(Instant.now()); session.setClosedAt(Instant.now());
    }
    @Test void requestCommitsSingleIntentBeforeAnyCrossHostClose() {
        var first=service.request(21L,actor,false); var repeated=service.request(21L,actor,false);
        assertEquals(first.id(),repeated.id()); assertEquals(WorkSessionStatus.CLOSING,session.getStatus());
        assertEquals(DevelopmentChangeStatus.OPEN,change.getStatus());
        verify(store,times(1)).create(any(),any(),any(),any(),any(),any(),any());
        verifyNoInteractions(close);
        verify(workspaces,times(1)).execute(argThat(command -> command.effect().equals("OBSERVE_ONLY")));
    }
    @Test void verifiedDeliveryClosesSameSessionAndCompletesTicketWithoutRepublishing() {
        service.request(21L,actor,false); service.advance(id);
        assertEquals("SUCCEEDED",durable.get().state()); assertEquals(DevelopmentChangeStatus.COMPLETED,change.getStatus());
        assertEquals(WorkSessionStatus.CLOSED,session.getStatus()); assertEquals(DevelopmentChangeProjectionState.CURRENT,change.getReleaseState());
        assertEquals("b".repeat(64),durable.get().evidence().path("remoteCloseReceiptSha256").asText());
        assertTrue(durable.get().evidence().has("deploymentBefore")); assertTrue(durable.get().evidence().has("deploymentAfter"));
        assertEquals(id,service.request(21L,actor,false).id()); service.advance(id);
        verify(close,times(1)).closeSession(21L);
        verify(workspaces,times(2)).execute(argThat(command -> command.operationId().equals(execution)
                && command.operationKind()==DevelopmentChangeWorkspaceOperationKind.INSPECT && command.effect().equals("OBSERVE_ONLY")));
        verify(delivery,never()).request(any(),any(),any(),any());
    }
    @Test void incompleteValidationOrChangedSourceNeverCloses() {
        service.request(21L,actor,false); change.setSourceRevision(6L); service.advance(id);
        assertEquals("BLOCKED",durable.get().state()); assertEquals("COMPLETION_EVIDENCE_MISMATCH",durable.get().errorCode());
        verifyNoInteractions(close); verify(workspaces,times(1)).execute(any());
        assertEquals(DevelopmentChangeStatus.OPEN,change.getStatus());
    }
    @Test void missingHealthyDeploymentRejectsBeforeIntentOrSessionMutation() {
        when(delivery.observeDeployment(anyLong(),any())).thenReturn(null);
        assertEquals("HEALTHY_DELIVERED_CHANGE_REQUIRED",assertThrows(DeliveryRejectedException.class,
                () -> service.request(21L,actor,false)).code());
        assertNull(durable.get()); assertEquals(WorkSessionStatus.OPEN,session.getStatus()); verifyNoInteractions(close,workspaces);
    }
    @Test void dirtyOrForeignWorkspaceIsRetainedAndNeverCompleted() {
        service.request(21L,actor,false);
        when(workspaces.execute(any())).thenReturn(new DevelopmentChangeWorkspaceObservation(
                DevelopmentChangeWorkspaceObservation.Disposition.OWNED,session.getFinalCommitSha(),"f".repeat(64),true,true,"a".repeat(64)));
        service.advance(id); assertEquals("BLOCKED",durable.get().state()); verifyNoInteractions(close);
        assertEquals(DevelopmentChangeStatus.OPEN,change.getStatus());
    }
    @Test void dirtyWorkspacePreflightRejectsWithoutLockingTheConversationOrCreatingAnIntent() {
        when(workspaces.execute(any())).thenReturn(new DevelopmentChangeWorkspaceObservation(
                DevelopmentChangeWorkspaceObservation.Disposition.OWNED,session.getFinalCommitSha(),"f".repeat(64),true,true,"a".repeat(64)));
        assertEquals("COMPLETION_WORKSPACE_NOT_CLEAN_OR_EXACT",assertThrows(DeliveryRejectedException.class,
                () -> service.request(21L,actor,false)).code());
        assertNull(durable.get()); assertEquals(WorkSessionStatus.OPEN,session.getStatus());
        assertEquals(DevelopmentChangeStatus.OPEN,change.getStatus()); verifyNoInteractions(close);
    }
    @Test void unexpectedWorkspaceHeadOrAbsentWorkspaceBlocks() {
        service.request(21L,actor,false);
        when(workspaces.execute(any())).thenReturn(new DevelopmentChangeWorkspaceObservation(
                DevelopmentChangeWorkspaceObservation.Disposition.ABSENT,null,null,false,false,"a".repeat(64)));
        service.advance(id); assertEquals("BLOCKED",durable.get().state()); verifyNoInteractions(close);
    }
    @Test void lostRemoteCloseResponseRecoversSameDurableIntent() {
        service.request(21L,actor,false);
        doAnswer(call -> {
            session.setRemoteCloseOperationId(execution); session.setRemoteCloseState(RemoteCloseState.RECONCILING);
            throw new WorkSessionCloseBlockedException("transport","REMOTE_WORKER_TIMEOUT","unknown","reconcile",true,List.of());
        }).doAnswer(call -> { release(); return null; }).when(close).closeSession(21L);
        service.advance(id); assertEquals("APPLYING",durable.get().state());
        assertEquals(DevelopmentChangeStatus.OPEN,change.getStatus()); service.advance(id);
        assertEquals(id,durable.get().id()); assertEquals(execution,durable.get().executionId()); assertEquals("SUCCEEDED",durable.get().state());
        verify(store,times(1)).create(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void interruptionAfterReleasedCloseReconcilesWithoutAnotherRelease() {
        service.request(21L,actor,false);
        doAnswer(call -> { release(); throw new IllegalStateException("simulated process interruption"); }).when(close).closeSession(21L);
        assertThrows(IllegalStateException.class,() -> service.advance(id));
        assertEquals(DevelopmentChangeStatus.OPEN,change.getStatus()); assertEquals("APPLYING",durable.get().state());
        service.advance(id);
        assertEquals("SUCCEEDED",durable.get().state()); assertEquals(DevelopmentChangeStatus.COMPLETED,change.getStatus());
        verify(close,times(1)).closeSession(21L);
    }
    @Test void postCloseDeploymentFailureRetainsClosedSessionAndNeedsSameIntentReconciliation() {
        service.request(21L,actor,false);
        when(delivery.observeDeployment(anyLong(),any())).thenReturn(deployed,null);
        service.advance(id); assertEquals("BLOCKED",durable.get().state());
        assertEquals(WorkSessionStatus.CLOSED,session.getStatus()); assertEquals(DevelopmentChangeStatus.OPEN,change.getStatus());
        when(delivery.observeDeployment(anyLong(),any())).thenReturn(deployed);
        assertEquals(id,service.request(21L,actor,true).id()); service.advance(id);
        assertEquals("SUCCEEDED",durable.get().state()); assertEquals(1,durable.get().evidence().path("blockedAttempts").size());
    }
    @Test void currentDatabaseRoleAndIdleAdmissionRemainRequired() {
        operator.setCodexOperationsRole(CodexOperationsRole.ROUTINE_OPERATOR);
        assertEquals("PLATFORM_ADMINISTRATOR_REQUIRED",assertThrows(DeliveryRejectedException.class,
                () -> service.request(21L,actor,false)).code());
        operator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        doThrow(new DeliveryRejectedException("COMPLETION_WORK_IN_PROGRESS")).when(store).lockCompletionAdmissionAndRequireIdle(21L);
        assertThrows(DeliveryRejectedException.class,() -> service.request(21L,actor,false)); assertNull(durable.get());
    }
    @Test void changedWorkspaceAfterReleaseNeverMarksTheTicketCompleted() {
        service.request(21L,actor,false);
        when(workspaces.execute(any())).thenReturn(clean(),new DevelopmentChangeWorkspaceObservation(
                DevelopmentChangeWorkspaceObservation.Disposition.OWNED,"e".repeat(40),null,false,false,"a".repeat(64)));
        service.advance(id);
        assertEquals("BLOCKED",durable.get().state()); assertEquals("COMPLETION_WORKSPACE_NOT_CLEAN_OR_EXACT",durable.get().errorCode());
        assertEquals(WorkSessionStatus.CLOSED,session.getStatus()); assertEquals(DevelopmentChangeStatus.OPEN,change.getStatus());
    }
    @Test void staleDeploymentProofNeverAuthorizesCompletion() {
        when(delivery.observeDeployment(anyLong(),any())).thenReturn(new MobileDeliveryService.DeploymentObservation(21L,"DEPLOYED","OPERATOR",
                deployed.sourceCommit(),deployed.integratedMergeCommit(),true,Instant.now().getEpochSecond()-61,
                deployed.planId(),deployed.operationId(),deployed.receiptSha256(),null));
        assertThrows(DeliveryRejectedException.class,() -> service.request(21L,actor,false)); assertNull(durable.get());
    }
    private MobileDeliveryService.DeploymentObservation unavailable(String code) {
        return MobileDeliveryService.DeploymentObservation.unavailable(21L,"UNAVAILABLE",code);
    }
    @Test void occupiedObservationBeforeIntentNeverLocksOrClosesTheSession() {
        when(delivery.observeDeployment(anyLong(),any())).thenReturn(unavailable("APP_OBSERVATION_BUSY"));
        assertEquals("APP_OBSERVATION_BUSY",assertThrows(DeliveryRejectedException.class,
                () -> service.request(21L,actor,false)).code());
        assertNull(durable.get()); assertEquals(WorkSessionStatus.OPEN,session.getStatus());
        verifyNoInteractions(close,workspaces);
    }
    @Test void occupiedObservationBeforeCloseWaitsAndRecoversSameIntentWithFreshProof() {
        service.request(21L,actor,false);
        when(delivery.observeDeployment(anyLong(),any())).thenReturn(unavailable("APP_OBSERVATION_BUSY"));
        service.advance(id); service.advance(id);
        assertEquals("APPLYING",durable.get().state());
        assertEquals("APP_OBSERVATION_BUSY",durable.get().errorCode());
        assertEquals(2,durable.get().evidence().path("deploymentObservationWait").path("attempts").asLong());
        assertFalse(durable.get().evidence().has("blockedAttempts"));
        assertEquals(DevelopmentChangeStatus.OPEN,change.getStatus()); verifyNoInteractions(close);
        assertEquals(WorkSessionStatus.CLOSING,session.getStatus());
        when(delivery.observeDeployment(anyLong(),any())).thenReturn(deployed);
        service.advance(id);
        assertEquals("SUCCEEDED",durable.get().state()); assertNull(durable.get().errorCode());
        assertEquals(id,durable.get().id()); assertEquals(execution,durable.get().executionId());
        verify(close,times(1)).closeSession(21L);
        verify(store,times(1)).create(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void occupiedPostCloseObservationNeverCompletesOrRepeatsRemoteClose() {
        service.request(21L,actor,false);
        when(delivery.observeDeployment(anyLong(),any())).thenReturn(deployed,unavailable("APP_OBSERVATION_BUSY"));
        service.advance(id);
        UUID closeId=session.getRemoteCloseOperationId();
        assertEquals("APPLYING",durable.get().state());
        assertEquals(WorkSessionStatus.CLOSED,session.getStatus());
        assertEquals(DevelopmentChangeStatus.OPEN,change.getStatus());
        when(delivery.observeDeployment(anyLong(),any())).thenReturn(deployed);
        service.advance(id);
        assertEquals("SUCCEEDED",durable.get().state());
        assertEquals(closeId,session.getRemoteCloseOperationId());
        verify(close,times(1)).closeSession(21L);
    }
    @Test void realObservationFailuresPreserveTheirCauseAndRemainTerminal() {
        service.request(21L,actor,false);
        when(delivery.observeDeployment(anyLong(),any())).thenReturn(unavailable("APP_OBSERVATION_EVIDENCE_MISMATCH"));
        service.advance(id);
        assertEquals("BLOCKED",durable.get().state());
        assertEquals("APP_OBSERVATION_EVIDENCE_MISMATCH",durable.get().errorCode());
        verifyNoInteractions(close);
    }
    @Test void staleOrUnhealthyProofAfterBusyStillBlocksInsteadOfCompleting() {
        service.request(21L,actor,false);
        when(delivery.observeDeployment(anyLong(),any())).thenReturn(unavailable("APP_OBSERVATION_BUSY"));
        service.advance(id);
        when(delivery.observeDeployment(anyLong(),any())).thenReturn(new MobileDeliveryService.DeploymentObservation(
                21L,"UNHEALTHY","OPERATOR",deployed.sourceCommit(),deployed.integratedMergeCommit(),false,
                Instant.now().getEpochSecond(),deployed.planId(),deployed.operationId(),deployed.receiptSha256(),null));
        service.advance(id);
        assertEquals("BLOCKED",durable.get().state());
        assertEquals("HEALTHY_DELIVERED_CHANGE_REQUIRED",durable.get().errorCode()); verifyNoInteractions(close);
    }
    @Test void actualDeliveryObservationPreservesPublisherBusyAcrossClosingAndRecovery() {
        // Exercise both services together: an always-healthy mocked observation
        // hid the production race between mobile reads and completion reads.
        var executor=mock(ReleaseControlClient.class);
        var github=mock(com.atenea.github.GitHubClient.class);
        when(executor.enabled()).thenReturn(true);
        when(github.extractPullRequestNumber(session.getPullRequestUrl())).thenReturn(47L);
        when(github.changedFiles(any(),eq(47L),anyString(),anyString())).thenReturn(List.of("src/main/java/Example.java"));
        var realDelivery=new MobileDeliveryService(store,sessions,mock(AgentRunRepository.class),operators,ownership,
                mock(WorkSessionGitHubService.class),new WorkSessionAcceptanceService(sessions),github,executor,
                mock(com.atenea.auth.recovery.OperatorRecoveryService.class),
                mock(com.atenea.auth.action.PrivilegedActionAuthorizationService.class),mapper,manager,mock(SourceUpdateBaseGuard.class));
        service=new MobileChangeCompletionService(store,sessions,changes,operators,ownership,realDelivery,
                workspaces,close,properties,mapper,manager);
        var observation=mapper.createObjectNode().put("protocol","atenea-app-observation/v1")
                .put("target","APP_PROD").put("state","OBSERVED").put("sourceCommit","8".repeat(40))
                .put("imageSha256","a".repeat(64)).put("healthy",true)
                .put("observedAt",Instant.now().getEpochSecond()).put("planId",deployed.planId().toString())
                .put("operationId",deployed.operationId().toString()).put("receiptSha256",deployed.receiptSha256())
                .put("finishedAt",1L);
        when(executor.observeApp()).thenReturn(observation)
                .thenThrow(new DeliveryRejectedException("APP_OBSERVATION_BUSY"));
        service.request(21L,actor,false); service.advance(id);
        assertEquals("APPLYING",durable.get().state()); assertEquals("APP_OBSERVATION_BUSY",durable.get().errorCode());
        assertEquals(WorkSessionStatus.CLOSING,session.getStatus()); verifyNoInteractions(close);
        doReturn(observation).when(executor).observeApp();
        service.advance(id);
        assertEquals("SUCCEEDED",durable.get().state()); verify(close,times(1)).closeSession(21L);
        verify(executor,never()).execute(any(),any(),any()); verify(executor,never()).plan(any(),any(),any());
    }

    @Test void androidOnlyChangeCompletesUsingItsPublishedApkWithoutRequiringABackendDelivery() {
        var observed=actualAndroidOnlyDelivery();
        service.request(21L,actor,false);service.advance(id);
        assertEquals("SUCCEEDED",durable.get().state());assertEquals(DevelopmentChangeStatus.COMPLETED,change.getStatus());
        assertTrue(durable.get().evidence().path("requiredDeliveryAfter").path("backend").isNull());
        verify(close,times(1)).closeSession(21L);
        verify(observed.executor(),never()).observeApp();
        verify(observed.executor(),never()).execute(any(),any(),any());
        verify(observed.executor(),never()).plan(any(),any(),any());
    }

    @Test void actualAndroidBusyWaitsOnTheSameIntentAndRetainsTheObservationCause() {
        var observed=actualAndroidOnlyDelivery();service.request(21L,actor,false);
        String binding=durable.get().evidence().path("completionBindingSha256").asText();
        doThrow(new DeliveryRejectedException("ANDROID_OBSERVATION_BUSY")).when(observed.executor()).observeAndroid();
        service.advance(id);service.advance(id);
        var waiting=durable.get();
        assertEquals("APPLYING",waiting.state());assertEquals("ANDROID_OBSERVATION_BUSY",waiting.errorCode());
        assertEquals(2,waiting.evidence().path("deploymentObservationWait").path("attempts").asLong());
        var proof=waiting.evidence().path("requiredDeliveryObservation");
        assertEquals("ANDROID_OBSERVATION_BUSY",proof.path("errorCode").asText());
        assertEquals("CONFIRMED",proof.path("requirementsStatus").asText());
        assertEquals("UNAVAILABLE",proof.path("androidStatus").asText());assertTrue(proof.path("backend").isNull());
        assertEquals(WorkSessionStatus.CLOSING,session.getStatus());verifyNoInteractions(close);
        doAnswer(call->androidObservation()).when(observed.executor()).observeAndroid();
        service.advance(id);
        assertEquals("SUCCEEDED",durable.get().state());assertEquals(id,durable.get().id());
        assertEquals(execution,durable.get().executionId());
        assertEquals(binding,durable.get().evidence().path("completionBindingSha256").asText());
        verify(close,times(1)).closeSession(21L);
        verify(store,times(1)).create(any(),any(),any(),any(),any(),any(),any());
        verify(observed.executor(),never()).execute(any(),any(),any());
        verify(observed.executor(),never()).plan(any(),any(),any());
    }

    @Test void androidBusyBeforeAdmissionExposesTheCauseWithoutStartingAClosingSession() {
        var observed=actualAndroidOnlyDelivery();
        doThrow(new DeliveryRejectedException("ANDROID_OBSERVATION_BUSY")).when(observed.executor()).observeAndroid();
        assertEquals("ANDROID_OBSERVATION_BUSY",assertThrows(DeliveryRejectedException.class,
                ()->service.request(21L,actor,false)).code());
        assertNull(durable.get());assertEquals(WorkSessionStatus.OPEN,session.getStatus());
        verifyNoInteractions(close,workspaces);
        verify(store,never()).create(any(),any(),any(),any(),any(),any(),any());
    }

    @Test void androidBusyAfterRemoteReleaseDoesNotRepeatTheCloseEffect() {
        var observed=actualAndroidOnlyDelivery();service.request(21L,actor,false);
        doAnswer(call->androidObservation()).doThrow(new DeliveryRejectedException("ANDROID_OBSERVATION_BUSY"))
                .when(observed.executor()).observeAndroid();
        service.advance(id);
        UUID closeId=session.getRemoteCloseOperationId();
        assertEquals("APPLYING",durable.get().state());assertEquals("ANDROID_OBSERVATION_BUSY",durable.get().errorCode());
        assertEquals(WorkSessionStatus.CLOSED,session.getStatus());assertEquals(DevelopmentChangeStatus.OPEN,change.getStatus());
        doAnswer(call->androidObservation()).when(observed.executor()).observeAndroid();
        service.advance(id);
        assertEquals("SUCCEEDED",durable.get().state());assertEquals(closeId,session.getRemoteCloseOperationId());
        assertEquals(execution,durable.get().executionId());verify(close,times(1)).closeSession(21L);
    }

    @ParameterizedTest
    @ValueSource(strings={"ANDROID_OBSERVATION_EVIDENCE_MISMATCH","ANDROID_OBSERVATION_NOT_VERIFIED",
            "ANDROID_OBSERVATION_MOVED","RELEASE_TRANSPORT_UNAVAILABLE"})
    void realAndroidRefusalsKeepTheirCodeAndDoNotAutoRetry(String code) {
        var observed=actualAndroidOnlyDelivery();service.request(21L,actor,false);
        doThrow(new DeliveryRejectedException(code)).when(observed.executor()).observeAndroid();
        service.advance(id);
        assertEquals("BLOCKED",durable.get().state());assertEquals(code,durable.get().errorCode());
        assertEquals(code,durable.get().evidence().path("requiredDeliveryObservation").path("errorCode").asText());
        assertEquals(code,durable.get().evidence().path("blockedAttempts").get(0).path("code").asText());
        assertEquals(WorkSessionStatus.CLOSING,session.getStatus());verifyNoInteractions(close);
        clearInvocations(observed.executor());service.advance(id);verifyNoInteractions(observed.executor());
    }

    @Test void missingChangeInTheApkIsStillADeterministicDeliveryBlock() {
        var observed=actualAndroidOnlyDelivery();service.request(21L,actor,false);
        doAnswer(call->androidObservation().put("sourceCommit","0".repeat(40))).when(observed.executor()).observeAndroid();
        doThrow(new com.atenea.github.GitHubIntegrationException("RELEASE_CHANGE_NOT_INCLUDED"))
                .when(observed.github()).requireIntegratedChangeInSource(any(),anyLong(),anyString(),anyString(),anyString(),eq("0".repeat(40)));
        service.advance(id);
        assertEquals("BLOCKED",durable.get().state());assertEquals("ANDROID_DELIVERY_REQUIRED",durable.get().errorCode());
        assertEquals("NOT_INCLUDED",durable.get().evidence().path("requiredDeliveryObservation").path("androidStatus").asText());
        verifyNoInteractions(close);
    }

    @ParameterizedTest
    @ValueSource(strings={"SESSION","HEAD","MERGE","STALE","FUTURE","READY","TARGET","DUPLICATE_TARGET"})
    void busyCannotAuthorizeForeignStaleOrContradictoryEvidence(String mismatch) {
        var apk=new ReleaseControlClient.AndroidObservation("8".repeat(40),"0.5.1",1,"a".repeat(64),"b".repeat(64),
                Instant.now().getEpochSecond(),UUID.randomUUID(),UUID.randomUUID(),"c".repeat(64));
        when(delivery.observeDelivery(eq(21L),any())).thenReturn(new ChangeDeliveryReadiness(21L,"CONFIRMED",
                session.getFinalCommitSha(),"4".repeat(40),List.of(DeliveryTarget.ANDROID_STABLE),true,null,apk,"INCLUDED",null,Instant.now().getEpochSecond()));
        service.request(21L,actor,false);
        long now=Instant.now().getEpochSecond();
        when(delivery.observeDelivery(eq(21L),any())).thenReturn(new ChangeDeliveryReadiness(
                mismatch.equals("SESSION")?22L:21L,"CONFIRMED",mismatch.equals("HEAD")?"0".repeat(40):session.getFinalCommitSha(),
                mismatch.equals("MERGE")?"0".repeat(40):"4".repeat(40),
                mismatch.equals("TARGET")?List.of(DeliveryTarget.AX42_PLATFORM):mismatch.equals("DUPLICATE_TARGET")
                        ?List.of(DeliveryTarget.ANDROID_STABLE,DeliveryTarget.ANDROID_STABLE):List.of(DeliveryTarget.ANDROID_STABLE),
                mismatch.equals("READY"),null,null,"UNAVAILABLE","ANDROID_OBSERVATION_BUSY",
                mismatch.equals("STALE")?now-90:mismatch.equals("FUTURE")?now+90:now));
        service.advance(id);
        assertEquals("BLOCKED",durable.get().state());assertEquals("COMPLETION_DELIVERY_EVIDENCE_MISMATCH",durable.get().errorCode());
        assertEquals("ANDROID_OBSERVATION_BUSY",durable.get().evidence().path("requiredDeliveryObservation").path("errorCode").asText());
        verifyNoInteractions(close);
    }

    private record AndroidDelivery(ReleaseControlClient executor,com.atenea.github.GitHubClient github) { }
    private AndroidDelivery actualAndroidOnlyDelivery() {
        var executor=mock(ReleaseControlClient.class);var github=mock(com.atenea.github.GitHubClient.class);
        when(executor.enabled()).thenReturn(true);
        when(github.extractPullRequestNumber(session.getPullRequestUrl())).thenReturn(47L);
        when(github.changedFiles(any(),eq(47L),anyString(),anyString())).thenReturn(List.of("android/core-console/Example.kt"));
        when(executor.observeAndroid()).thenAnswer(call->androidObservation());
        var realDelivery=new MobileDeliveryService(store,sessions,mock(AgentRunRepository.class),operators,ownership,
                mock(WorkSessionGitHubService.class),new WorkSessionAcceptanceService(sessions),github,executor,
                mock(com.atenea.auth.recovery.OperatorRecoveryService.class),mock(com.atenea.auth.action.PrivilegedActionAuthorizationService.class),
                mapper,manager,mock(SourceUpdateBaseGuard.class));
        service=new MobileChangeCompletionService(store,sessions,changes,operators,ownership,realDelivery,
                workspaces,close,properties,mapper,manager);
        return new AndroidDelivery(executor,github);
    }
    private com.fasterxml.jackson.databind.node.ObjectNode androidObservation() {
        return mapper.createObjectNode().put("protocol","atenea-android-observation/v1").put("target","ANDROID_STABLE")
                .put("state","OBSERVED").put("sourceCommit","8".repeat(40)).put("versionCode",1).put("versionName","0.5.1")
                .put("apkSha256","a".repeat(64)).put("certificateSha256","b".repeat(64)).put("observedAt",Instant.now().getEpochSecond())
                .put("planId",deployed.planId().toString()).put("operationId",deployed.operationId().toString())
                .put("receiptSha256","c".repeat(64)).put("finishedAt",1L);
    }
}
