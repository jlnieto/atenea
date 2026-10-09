package com.atenea.delivery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.auth.AuthenticatedSession;
import com.atenea.auth.action.PrivilegedActionAuthorizationService;
import com.atenea.auth.recovery.OperatorRecoveryService;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubIntegrationException;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionAcceptanceState;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.DevelopmentChangeBranchPublicationService;
import com.atenea.service.worksession.WorkSessionAcceptanceService;
import com.atenea.service.worksession.WorkSessionGitHubService;
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

class MobileDeliveryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeliveryStore store = mock(DeliveryStore.class);
    private final WorkSessionRepository sessions = mock(WorkSessionRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final OperatorRepository operators = mock(OperatorRepository.class);
    private final DevelopmentChangeBranchPublicationService owner = mock(DevelopmentChangeBranchPublicationService.class);
    private final WorkSessionGitHubService publication = mock(WorkSessionGitHubService.class);
    private final WorkSessionAcceptanceService acceptance = new WorkSessionAcceptanceService(sessions);
    private final GitHubClient github = mock(GitHubClient.class);
    private final ReleaseControlClient executor = mock(ReleaseControlClient.class);
    private final OperatorRecoveryService factors = mock(OperatorRecoveryService.class);
    private final PrivilegedActionAuthorizationService grants = mock(PrivilegedActionAuthorizationService.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final AuthenticatedOperator actor = new AuthenticatedOperator(7L, "delivery@atenea.test", "Test");
    private final UUID id = UUID.randomUUID(), execution = UUID.randomUUID();
    private MobileDeliveryService service;
    private WorkSessionEntity session;
    private OperatorEntity operator;
    @BeforeEach void setup() {
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(executor.enabled()).thenReturn(true);
        operator = new OperatorEntity(); operator.setId(7L); operator.setActive(true);
        operator.setCodexOperationsRole(CodexOperationsRole.PLATFORM_ADMINISTRATOR);
        when(operators.findById(7L)).thenReturn(Optional.of(operator));
        session = new WorkSessionEntity(); session.setId(21L);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        when(sessions.findLockedWithProjectAndDevelopmentChangeById(21L)).thenReturn(Optional.of(session));
        when(sessions.findWithProjectAndDevelopmentChangeById(21L)).thenReturn(Optional.of(session));
        when(sessions.findLockedWithProjectById(21L)).thenReturn(Optional.of(session));
        when(sessions.save(any())).thenAnswer(call -> call.getArgument(0));
        service = new MobileDeliveryService(store,sessions,runs,operators,owner,publication,acceptance,github,executor,factors,grants,mapper,tm);
    }
    private DeliveryOperation operation(String kind, String state) {
        return new DeliveryOperation(id,21L,7L,kind,DeliveryTarget.APP_PROD,"1".repeat(40),execution,state,
                "2".repeat(64),mapper.createObjectNode().put("expiresAt",Long.MAX_VALUE),null,Instant.now(),Instant.now());
    }
    @Test void currentRoleNotTokenRoleGovernsEveryAction() {
        operator.setCodexOperationsRole(CodexOperationsRole.ROUTINE_OPERATOR);
        assertEquals("PLATFORM_ADMINISTRATOR_REQUIRED", assertThrows(DeliveryRejectedException.class,
                () -> service.request(21L,actor,"PUBLISH_PR",DeliveryTarget.APP_PROD)).code());
        verifyNoInteractions(store);
    }
    @Test void existingActiveIntentIsReturnedWithoutAnotherOperationOrExternalEffect() {
        var op = operation("PUBLISH_PR","QUEUED"); when(store.list(21L)).thenReturn(List.of(op));
        assertEquals(id,service.request(21L,actor,"PUBLISH_PR",DeliveryTarget.APP_PROD).id());
        verify(store,never()).create(any(),any(),any(),any(),any(),any(),any());
        verify(executor,never()).execute(any(),any(),any());
    }
    @Test void activeAgentRunBlocksNewIntent() {
        when(runs.existsBySessionIdAndStatusIn(eq(21L),any())).thenReturn(true);
        assertEquals("ACTIVE_AGENT_RUN",assertThrows(DeliveryRejectedException.class,
                () -> service.request(21L,actor,"PUBLISH_PR",DeliveryTarget.APP_PROD)).code());
    }
    @Test void publicationWaitsForUfdWithoutCreatingAnotherPromptOrPr() {
        var op = operation("PUBLISH_PR","QUEUED"); when(store.pending()).thenReturn(List.of(id));
        when(store.get(id,true)).thenReturn(op);
        when(publication.publishForDelivery(21L)).thenThrow(new GitHubIntegrationException("UFD_PENDING: exact commit"));
        service.reconcile();
        verify(store).update(op,"WAITING_CI","CI_PENDING",null,op.evidence());
        verifyNoInteractions(github,factors,grants);
    }
    @Test void ambiguousExecuteRetainsSameDurableIdentityForNextPoll() {
        var op = operation("RELEASE","CONFIRMED"); when(store.pending()).thenReturn(List.of(id));
        when(store.get(id,true)).thenReturn(op);
        when(executor.execute(id,op.planSha256(),execution)).thenThrow(new DeliveryRejectedException("RELEASE_TRANSPORT_UNAVAILABLE"));
        service.reconcile(); service.reconcile();
        verify(executor,times(2)).execute(id,op.planSha256(),execution);
        verify(store,times(2)).update(op,"CONFIRMED","RELEASE_TRANSPORT_UNAVAILABLE",op.planSha256(),op.evidence());
    }
    @Test void lostConfirmationReplyReturnsReceiptWithoutReconsumingGrant() {
        var op = operation("RELEASE","ACCEPTED"); when(store.get(id,true)).thenReturn(op);
        var auth = new AuthenticatedSession(actor,UUID.randomUUID(),Instant.now(),List.of());
        assertEquals(id,service.confirm(id,auth,UUID.randomUUID()).id());
        verifyNoInteractions(grants,factors);
        verify(executor,never()).execute(any(),any(),any());
    }
    @Test void factorCannotAuthorizeExpiredOrForeignPlan() {
        var op = operation("RELEASE","PREPARING"); when(store.get(id,false)).thenReturn(op);
        assertThrows(DeliveryRejectedException.class, () -> service.authorize(id,
                new AuthenticatedSession(actor,UUID.randomUUID(),Instant.now(),List.of()),"123456"));
        verifyNoInteractions(factors,grants);
    }
    @Test void appCannotChooseUnintegratedSourceForProduction() {
        publishedFixture();
        assertEquals("EXACT_CHANGE_NOT_INTEGRATED", assertThrows(DeliveryRejectedException.class,
                () -> service.request(21L,actor,"RELEASE",DeliveryTarget.APP_PROD)).code());
        verify(executor,never()).plan(any(),any(),any());
    }
    @Test void existingOwnedPrIsAdoptedWithoutAnotherPublication() {
        publishedFixture(); session.setPullRequestStatus(WorkSessionPullRequestStatus.OPEN);
        var receipt=operation("PUBLISH_PR","SUCCEEDED");
        when(store.create(eq(21L),eq(7L),eq("PUBLISH_PR"),eq(DeliveryTarget.APP_PROD),
                eq(session.getFinalCommitSha()),eq("SUCCEEDED"),any())).thenReturn(receipt);
        assertEquals(id,service.request(21L,actor,"PUBLISH_PR",DeliveryTarget.APP_PROD).id());
        verifyNoInteractions(publication,github,factors,grants);
    }
    @Test void failedCiIsDurablyBlockedRatherThanReportedAsStillWaiting() {
        var op=operation("PUBLISH_PR","WAITING_CI"); when(store.pending()).thenReturn(List.of(id));
        when(store.get(id,true)).thenReturn(op);
        when(publication.publishForDelivery(21L)).thenThrow(new GitHubIntegrationException("UFD_FAILED: failed tests"));
        service.reconcile();
        verify(store).update(op,"BLOCKED","GITHUB_CHECKS_FAILED",null,op.evidence());
    }

    @Test void mergeConflictBlocksSameIntegrationOperationInsteadOfWaitingForCi() {
        publishedFixture();
        var op = operation("INTEGRATE", "QUEUED"); var saved = durableOutbox(op);
        when(github.extractPullRequestNumber(session.getPullRequestUrl())).thenReturn(42L);
        when(github.integrateExact(any(), eq(42L), eq(session.getPublishedHeadBranch()), eq(session.getFinalCommitSha())))
                .thenThrow(new GitHubIntegrationException("PR_MERGE_CONFLICTS: five tests"));
        service.reconcile();
        assertEquals("BLOCKED", saved.get().state());
        assertEquals("PR_MERGE_CONFLICTS", saved.get().errorCode());
        assertEquals(op.id(), saved.get().id());
        verify(store, never()).create(any(), any(), any(), any(), any(), any(), any());
    }
    @Test void mergePromotesExactAcceptanceBeforeFlushingAndCompletesSameOperation() {
        publishedFixture();
        var op = operation("INTEGRATE", "WAITING_CI"); var saved = durableOutbox(op);
        when(github.extractPullRequestNumber(session.getPullRequestUrl())).thenReturn(42L);
        when(github.integrateExact(any(), eq(42L), eq(session.getPublishedHeadBranch()), eq(session.getFinalCommitSha())))
                .thenReturn("3".repeat(40));
        doAnswer(call -> {
            assertEquals(WorkSessionAcceptanceState.INTEGRATION_READY, session.getAcceptanceState());
            assertNotNull(session.getIntegrationReadyAt());
            assertEquals("6".repeat(64), session.getValidationProjectionSha256());
            assertEquals(WorkSessionPullRequestStatus.MERGED, session.getPullRequestStatus());
            return session;
        }).when(sessions).saveAndFlush(session);
        service.reconcile(); service.reconcile();
        assertEquals(op.id(), saved.get().id());
        assertEquals("SUCCEEDED", saved.get().state());
        assertEquals("3".repeat(40), saved.get().evidence().path("mergeCommit").asText());
        verify(github, times(1)).integrateExact(any(), anyLong(), anyString(), anyString());
        verify(sessions, times(1)).saveAndFlush(session);
        verify(store, never()).create(any(), any(), any(), any(), any(), any(), any());
        verify(executor, never()).execute(any(), any(), any());
        verify(executor, never()).plan(any(), any(), any());
        verifyNoInteractions(factors, grants);
    }
    @Test void integratedReadinessKeepsExactOwnerAndCanonicalMainGuardsForRelease() {
        publishedFixture();
        session.setAcceptanceState(WorkSessionAcceptanceState.INTEGRATION_READY);
        session.setIntegrationReadyAt(Instant.now());
        session.setPullRequestStatus(WorkSessionPullRequestStatus.MERGED);
        var receipt = operation("INTEGRATE", "SUCCEEDED");
        ((com.fasterxml.jackson.databind.node.ObjectNode) receipt.evidence()).put("mergeCommit", "3".repeat(40));
        when(store.integrated(21L, session.getFinalCommitSha())).thenReturn(Optional.of(receipt));
        when(github.canonicalMain(any())).thenReturn("4".repeat(40));
        assertEquals("CANONICAL_MAIN_MOVED", assertThrows(DeliveryRejectedException.class,
                () -> service.request(21L, actor, "RELEASE", DeliveryTarget.APP_PROD)).code());
        verify(owner).requireExactIntegratedOwner(session);
        verify(owner, never()).requireExactOwner(session);
        verify(store, never()).create(any(), any(), any(), any(), any(), any(), any());
        verify(executor, never()).execute(any(), any(), any());
        verify(executor, never()).plan(any(), any(), any());
        verifyNoInteractions(factors, grants);
    }
    @Test void readOnlyMergeObservationKeepsPublicationAndFactorsUntouched() {
        publishedFixture();
        when(github.extractPullRequestNumber(session.getPullRequestUrl())).thenReturn(42L);
        when(github.observeMergeState(any(), eq(42L), eq(session.getPublishedHeadBranch()), eq(session.getFinalCommitSha())))
                .thenReturn(com.atenea.github.GitHubMergeState.CONFLICTS);
        var result = service.observeIntegration(21L, actor);
        assertEquals("CONFLICTS", result.mergeState());
        assertFalse(result.canRequestIntegration());
        assertEquals(session.getFinalCommitSha(), result.sourceCommit());
        verifyNoInteractions(store, publication, executor, factors, grants);
    }
    @Test void movedSourceDisablesIntegrationBeforeAnyGithubRead() {
        publishedFixture(); session.getDevelopmentChange().setSourceRevision(3L);
        var result = service.observeIntegration(21L, actor);
        assertEquals("STALE_SOURCE", result.mergeState()); assertFalse(result.canRequestIntegration());
        verifyNoInteractions(github, store, publication, executor);
    }
    @Test void unavailableGithubCannotEnableIntegrationOrExposeItsErrorBody() {
        publishedFixture();
        when(github.extractPullRequestNumber(session.getPullRequestUrl())).thenReturn(42L);
        when(github.observeMergeState(any(), anyLong(), anyString(), anyString()))
                .thenThrow(new GitHubIntegrationException("synthetic body must stay private"));
        var result = service.observeIntegration(21L, actor);
        assertEquals("UNAVAILABLE", result.mergeState());
        assertEquals("GITHUB_UNAVAILABLE", result.errorCode()); assertFalse(result.canRequestIntegration());
    }
    @Test void routineOperatorCannotObservePrivilegedPublicationState() {
        operator.setCodexOperationsRole(CodexOperationsRole.ROUTINE_OPERATOR);
        assertThrows(DeliveryRejectedException.class, () -> service.observeIntegration(21L, actor));
        verifyNoInteractions(github, store, owner);
    }

    private AtomicReference<DeliveryOperation> durableOutbox(DeliveryOperation initial) {
        var saved = new AtomicReference<>(initial);
        when(store.pending()).thenReturn(List.of(id));
        when(store.get(eq(id), anyBoolean())).thenAnswer(ignored -> saved.get());
        doAnswer(call -> {
            DeliveryOperation previous = call.getArgument(0);
            saved.set(new DeliveryOperation(previous.id(),previous.sessionId(),previous.operatorId(),previous.kind(),
                    previous.target(),previous.sourceCommit(),previous.executionId(),call.getArgument(1),
                    call.getArgument(3),call.getArgument(4),call.getArgument(2),previous.createdAt(),Instant.now()));
            return null;
        }).when(store).update(any(), anyString(), nullable(String.class), nullable(String.class), any());
        return saved;
    }
    private GitHubClient.UfdDispatchRequest dispatchFixture() {
        publishedFixture();
        String head = session.getFinalCommitSha(), branch = session.getPublishedHeadBranch();
        var request = new GitHubClient.UfdDispatchRequest(GitHubClient.ufdRequestId(head,branch),head,branch,"3".repeat(40));
        when(github.prepareOwnedHeadUfd(head,branch)).thenReturn(request);
        when(publication.publishForDelivery(21L)).thenThrow(new GitHubIntegrationException("UFD_WORKFLOW_MISSING: legacy head"));
        return request;
    }
    @Test void legacyPublicationCommitsIntentAndClaimBeforeOneDispatchAndNeverResendsOnPoll() {
        var request = dispatchFixture();
        var saved = durableOutbox(operation("PUBLISH_PR","WAITING_CI"));
        var claimCommitted = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(ignored -> { claimCommitted.set(true); return null; }).when(tm).commit(any());
        doAnswer(ignored -> {
            assertTrue(claimCommitted.get(), "HTTP must be outside the committed claim transaction");
            assertEquals("CLAIMED", saved.get().evidence().path("ufdDispatch").path("status").asText());
            assertEquals(request.requestId().toString(), saved.get().evidence().path("ufdDispatch").path("requestId").asText());
            return null;
        }).when(github).dispatchOwnedHeadUfd(request);
        service.reconcile(); // Prepare is durable, but it has not sent HTTP.
        assertEquals("PREPARED",saved.get().evidence().path("ufdDispatch").path("status").asText());
        verify(github,never()).dispatchOwnedHeadUfd(any());
        claimCommitted.set(false);
        service.reconcile(); // Claim commits before HTTP, then record acceptance.
        assertEquals("ACCEPTED",saved.get().evidence().path("ufdDispatch").path("status").asText());
        service.reconcile(); service.reconcile();
        verify(github,times(1)).prepareOwnedHeadUfd(anyString(),anyString());
        verify(github,times(1)).dispatchOwnedHeadUfd(request);
        assertEquals("WAITING_CI",saved.get().state());
        verifyNoInteractions(factors,grants);
    }
    @Test void lostDispatchResponseAndRestartRetainIdentityWithoutSecondSend() {
        var request=dispatchFixture();
        var saved=durableOutbox(operation("PUBLISH_PR","WAITING_CI"));
        doThrow(new GitHubIntegrationException("lost response")).when(github).dispatchOwnedHeadUfd(request);
        service.reconcile(); service.reconcile();
        assertEquals("UNCONFIRMED",saved.get().evidence().path("ufdDispatch").path("status").asText());
        // Reconstruct service as after an App restart, keeping the durable outbox.
        service=new MobileDeliveryService(store,sessions,runs,operators,owner,publication,acceptance,github,executor,factors,grants,mapper,tm);
        service.reconcile(); service.reconcile();
        verify(github,times(1)).dispatchOwnedHeadUfd(request);
        assertEquals(request.requestId().toString(),saved.get().evidence().path("ufdDispatch").path("requestId").asText());
    }
    @Test void claimedBeforeCrashButNoReceiptCannotResendAndEventuallyBlocks() {
        var request=dispatchFixture();
        var initial=operation("PUBLISH_PR","WAITING_CI");
        var evidence=mapper.createObjectNode();
        evidence.set("ufdDispatch",mapper.valueToTree(request));
        ((com.fasterxml.jackson.databind.node.ObjectNode)evidence.path("ufdDispatch"))
                .put("status","CLAIMED").put("claimedAt",Instant.now().minusSeconds(601).getEpochSecond());
        var saved=durableOutbox(new DeliveryOperation(initial.id(),21L,7L,initial.kind(),initial.target(),initial.sourceCommit(),
                execution,initial.state(),null,evidence,null,initial.createdAt(),initial.updatedAt()));
        service.reconcile();
        assertEquals("BLOCKED",saved.get().state()); assertEquals("UFD_NOT_STARTED",saved.get().errorCode());
        verify(github,never()).dispatchOwnedHeadUfd(any());
    }
    @Test void candidateChangedAfterPrepareIsBlockedBeforeDispatch() {
        dispatchFixture(); var saved=durableOutbox(operation("PUBLISH_PR","WAITING_CI"));
        service.reconcile(); session.setFinalCommitSha("9".repeat(40)); service.reconcile();
        assertEquals("BLOCKED",saved.get().state()); assertEquals("UFD_IDENTITY_REJECTED",saved.get().errorCode());
        verify(github,never()).dispatchOwnedHeadUfd(any());
    }
    @Test void missingMainControllerDoesNotWaitForNonexistentCi() {
        dispatchFixture();
        when(github.prepareOwnedHeadUfd(anyString(),anyString())).thenThrow(new GitHubIntegrationException("UFD_CONTROLLER_UNAVAILABLE"));
        var saved=durableOutbox(operation("PUBLISH_PR","WAITING_CI"));
        service.reconcile();
        assertEquals("BLOCKED",saved.get().state()); assertEquals("UFD_CONTROLLER_UNAVAILABLE",saved.get().errorCode());
        verify(github,never()).dispatchOwnedHeadUfd(any());
    }
    @Test void failedClaimCommitCannotSendHttp() {
        dispatchFixture(); durableOutbox(operation("PUBLISH_PR","WAITING_CI"));
        service.reconcile();
        doThrow(new RuntimeException("commit failed")).when(tm).commit(any());
        service.reconcile();
        verify(github,never()).dispatchOwnedHeadUfd(any());
    }
    @Test void confirmedUfdCompletesOriginalOperationAndKeepsDispatchAudit() {
        dispatchFixture(); var saved=durableOutbox(operation("PUBLISH_PR","WAITING_CI"));
        service.reconcile(); service.reconcile();
        var response=mock(com.atenea.api.worksession.WorkSessionResponse.class);
        when(response.pullRequestUrl()).thenReturn(session.getPullRequestUrl());
        when(response.finalCommitSha()).thenReturn(session.getFinalCommitSha());
        doReturn(response).when(publication).publishForDelivery(21L);
        service.reconcile();
        assertEquals(id,saved.get().id()); assertEquals("SUCCEEDED",saved.get().state());
        assertEquals("ACCEPTED",saved.get().evidence().path("ufdDispatch").path("status").asText());
        assertEquals(session.getPullRequestUrl(),saved.get().evidence().path("pullRequestUrl").asText());
        verify(store,never()).create(any(),any(),any(),any(),any(),any(),any());
        verify(github,times(1)).dispatchOwnedHeadUfd(any());
    }
    @Test void sourceRevisionMovedAfterSendIsBlockedBeforePublishingPr() {
        dispatchFixture(); var saved=durableOutbox(operation("PUBLISH_PR","WAITING_CI"));
        service.reconcile(); service.reconcile();
        session.getDevelopmentChange().setSourceRevision(3L);
        clearInvocations(publication);
        service.reconcile();
        assertEquals("BLOCKED",saved.get().state());
        assertEquals("PUBLISHED_OWNERSHIP_MISMATCH",saved.get().errorCode());
        verifyNoInteractions(publication);
        verify(github,times(1)).dispatchOwnedHeadUfd(any());
    }
    @Test void newIntentForSameHeadAdoptsEarlierClaimInsteadOfRedispatching() {
        var request=dispatchFixture();
        var earlier=operation("PUBLISH_PR","BLOCKED");
        var evidence=mapper.createObjectNode(); evidence.set("ufdDispatch",mapper.valueToTree(request));
        ((com.fasterxml.jackson.databind.node.ObjectNode)evidence.path("ufdDispatch"))
                .put("status","UNCONFIRMED").put("claimedAt",Instant.now().minusSeconds(601).getEpochSecond());
        earlier=new DeliveryOperation(UUID.randomUUID(),21L,7L,earlier.kind(),earlier.target(),null,UUID.randomUUID(),
                "BLOCKED",null,evidence,"UFD_NOT_STARTED",earlier.createdAt(),earlier.updatedAt());
        when(store.ownedHeadUfdRequest(21L,request.headSha(),request.headBranch())).thenReturn(Optional.of(earlier));
        var saved=durableOutbox(operation("PUBLISH_PR","QUEUED"));
        service.reconcile(); service.reconcile();
        assertEquals("BLOCKED",saved.get().state());
        assertEquals(request.requestId().toString(),saved.get().evidence().path("ufdDispatch").path("requestId").asText());
        verify(github,never()).prepareOwnedHeadUfd(anyString(),anyString());
        verify(github,never()).dispatchOwnedHeadUfd(any());
    }
    private void publishedFixture() {
        DevelopmentChangeEntity change = new DevelopmentChangeEntity(); change.setChangeKey(UUID.randomUUID());
        change.setSourceFingerprintSha256("5".repeat(64)); change.setSourceRevision(2L);
        session.setDevelopmentChange(change); session.setPublishedChangeKey(change.getChangeKey());
        session.setPublishedSourceRevision(2L); session.setPublishedSourceFingerprintSha256(change.getSourceFingerprintSha256());
        session.setSourceTreeFingerprintSha256(change.getSourceFingerprintSha256());
        session.setSourceTreeObservedAt(Instant.now()); session.setAcceptanceState(WorkSessionAcceptanceState.VALIDATED);
        session.setValidationProjectionSha256("6".repeat(64)); session.setValidationDefinitionRevision("test-v1");
        session.setValidatedAt(Instant.now());
        session.setPublishedRepository("jlnieto/atenea"); session.setPublishedBaseBranch("main");
        session.setWorkspaceBranch("atenea/change-"+change.getChangeKey()); session.setPublishedHeadBranch(session.getWorkspaceBranch());
        session.setFinalCommitSha("1".repeat(40)); session.setPullRequestUrl("https://github.com/jlnieto/atenea/pull/42");
    }
}
