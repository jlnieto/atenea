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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MobileChangeCompletionServiceTest {
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
}
