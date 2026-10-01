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
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.DevelopmentChangeBranchPublicationService;
import com.atenea.service.worksession.WorkSessionGitHubService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
        service = new MobileDeliveryService(store,sessions,runs,operators,owner,publication,github,executor,factors,grants,mapper,tm);
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
        verify(store).update(op,"BLOCKED","GITHUB_REJECTED",null,op.evidence());
    }
    private void publishedFixture() {
        DevelopmentChangeEntity change = new DevelopmentChangeEntity(); change.setChangeKey(UUID.randomUUID());
        change.setSourceFingerprintSha256("5".repeat(64)); change.setSourceRevision(2L);
        session.setDevelopmentChange(change); session.setPublishedChangeKey(change.getChangeKey());
        session.setPublishedSourceRevision(2L); session.setPublishedSourceFingerprintSha256(change.getSourceFingerprintSha256());
        session.setSourceTreeFingerprintSha256(change.getSourceFingerprintSha256());
        session.setPublishedRepository("jlnieto/atenea"); session.setPublishedBaseBranch("main");
        session.setWorkspaceBranch("atenea/change-"+change.getChangeKey()); session.setPublishedHeadBranch(session.getWorkspaceBranch());
        session.setFinalCommitSha("1".repeat(40)); session.setPullRequestUrl("https://github.com/jlnieto/atenea/pull/42");
    }
}
