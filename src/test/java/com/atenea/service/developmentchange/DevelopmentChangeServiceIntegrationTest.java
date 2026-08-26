package com.atenea.service.developmentchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.api.developmentchange.CreateDevelopmentChangeRequest;
import com.atenea.api.developmentchange.DevelopmentChangeActionKind;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.developmentchange.DevelopmentChangeProperties;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeOperationRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.v2control.V2AuditEventRepository;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateEntity;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateRepository;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyEntity;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyRepository;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkerNodeEntity;
import com.atenea.persistence.worksession.WorkerNodeRepository;
import com.atenea.remoteworker.CanonicalSourceAdmissionService;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.service.git.GitRepositoryOperationException;
import com.atenea.service.git.GitRepositoryService;
import com.atenea.service.worksession.WorkSessionOperationBlockedException;
import com.atenea.v2.control.V2FailureCategory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "atenea.development-change.mutations-enabled=true",
        "atenea.development-change.session-binding-enabled=true"
})
@Transactional
class DevelopmentChangeServiceIntegrationTest {

    private static final String BASE_COMMIT = "1".repeat(40);
    private static final String BASE_TREE = "2".repeat(40);

    @Autowired private DevelopmentChangeService service;
    @Autowired private DevelopmentChangeProperties properties;
    @Autowired private RemoteWorkerProperties remoteWorkerProperties;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private DevelopmentChangeRepository changeRepository;
    @Autowired private DevelopmentChangeOperationRepository operationRepository;
    @Autowired private DevelopmentChangeWorkspaceOperationRepository workspaceOperationRepository;
    @Autowired private WorkSessionRepository workSessionRepository;
    @Autowired private AgentRunRepository agentRunRepository;
    @Autowired private WorkerNodeRepository workerNodeRepository;
    @Autowired private V2AuditEventRepository auditRepository;
    @Autowired private V2GlobalCapabilityGateRepository globalGateRepository;
    @Autowired private V2ProjectCapabilityPolicyRepository projectPolicyRepository;

    @MockBean private GitRepositoryService gitRepositoryService;
    @MockBean private CanonicalSourceAdmissionService canonicalSourceAdmissionService;

    private ProjectEntity project;
    private OperatorEntity operator;
    private AuthenticatedOperator actor;

    @BeforeEach
    void setUp() {
        properties.setMutationsEnabled(true);
        properties.setSessionBindingEnabled(true);
        properties.setWorkspaceOperationsEnabled(true);
        properties.setWorkspaceReconciliationEnabled(true);
        remoteWorkerProperties.setWorkerId("synthetic-worker-01");
        registerWorker();
        String identity = UUID.randomUUID().toString();
        project = projectRepository.saveAndFlush(canonicalProject());
        operator = operator(identity + "@atenea.test");
        actor = new AuthenticatedOperator(
                operator.getId(), operator.getEmail(), operator.getDisplayName());
        enablePolicy(project, 7);
        when(canonicalSourceAdmissionService.observeCanonicalSource(any(ProjectEntity.class)))
                .thenReturn(canonicalObservation());
        when(gitRepositoryService.resolveCommitTree(project.getRepoPath(), BASE_COMMIT))
                .thenReturn(BASE_TREE);
        when(gitRepositoryService.exactLocalHeadExists(
                org.mockito.ArgumentMatchers.eq(project.getRepoPath()), anyString()))
                .thenReturn(false);
    }

    @Test
    void disabledMutationFailsClosedAuditsAndNeverInspectsGit() {
        properties.setMutationsEnabled(false);
        long auditsBefore = auditRepository.count();

        DevelopmentChangeRejectedException failure = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> service.create(
                        actor, project.getId(), UUID.randomUUID(),
                        new CreateDevelopmentChangeRequest("Synthetic disabled change")));

        assertEquals(V2FailureCategory.POLICY, failure.response().failureCategory());
        assertEquals("DEVELOPMENT_CHANGE_MUTATIONS_DISABLED", failure.response().failureCode());
        assertEquals(DevelopmentChangeActionKind.WAIT_FOR_ENABLEMENT,
                failure.response().nextAction().kind());
        assertEquals(auditsBefore + 1, auditRepository.count());
        assertEquals(0, changeRepository.count());
        assertEquals(0, operationRepository.count());
        verify(gitRepositoryService, never()).resolveExactHeadCommit(anyString(), anyString());
    }

    @Test
    void invalidMutationIsAuditedBeforeGitAndCreatesNoOperation() {
        long auditsBefore = auditRepository.count();

        DevelopmentChangeRejectedException failure = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> service.create(
                        actor, project.getId(), UUID.randomUUID(),
                        new CreateDevelopmentChangeRequest("   ")));

        assertEquals(V2FailureCategory.VALIDATION, failure.response().failureCategory());
        assertEquals("DEVELOPMENT_CHANGE_REQUEST_INVALID", failure.response().failureCode());
        assertEquals(auditsBefore + 1, auditRepository.count());
        assertEquals(0, changeRepository.count());
        assertEquals(0, operationRepository.count());
        verify(gitRepositoryService, never()).resolveExactHeadCommit(anyString(), anyString());
    }

    @Test
    void createsServerOwnedIdentityAndReplaysTheSameDurableOperation() {
        UUID key = UUID.randomUUID();

        var created = service.create(
                actor, project.getId(), key,
                new CreateDevelopmentChangeRequest("Synthetic isolated change"));
        var replayed = service.create(
                actor, project.getId(), key,
                new CreateDevelopmentChangeRequest("Synthetic isolated change"));

        assertFalse(created.replayed());
        assertTrue(replayed.replayed());
        assertEquals(created.operationId(), replayed.operationId());
        assertEquals(created.receiptSha256(), replayed.receiptSha256());
        assertEquals(created.developmentChange().changeKey(),
                replayed.developmentChange().changeKey());
        assertEquals("atenea/change-" + created.developmentChange().changeKey(),
                created.developmentChange().workspaceBranch());
        assertEquals("remote:synthetic-worker-01:change:"
                        + created.developmentChange().changeKey(),
                created.developmentChange().workspaceIdentity());
        assertEquals("refs/heads/main", created.developmentChange().baseRef());
        assertEquals(BASE_COMMIT, created.developmentChange().baseCommit());
        assertEquals(7, created.developmentChange().projectPolicyRevision());
        assertEquals(1, changeRepository.count());
        assertEquals(1, operationRepository.count());
        assertEquals(1, auditRepository.findAll().stream()
                .filter(event -> "DEVELOPMENT_CHANGE_CREATED".equals(event.getEventType()))
                .count());
        verify(gitRepositoryService, never()).resolveExactHeadCommit(anyString(), anyString());
    }

    @Test
    void canonicalSourceRejectionCreatesNoChangeOperationWorkspaceSessionOrRun() {
        when(canonicalSourceAdmissionService.observeCanonicalSource(any(ProjectEntity.class)))
                .thenThrow(new WorkSessionOperationBlockedException("synthetic rejection"));

        DevelopmentChangeRejectedException failure = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> service.create(
                        actor, project.getId(), UUID.randomUUID(),
                        new CreateDevelopmentChangeRequest("Rejected canonical source")));

        assertEquals(V2FailureCategory.OWNERSHIP, failure.response().failureCategory());
        assertEquals("DEVELOPMENT_CHANGE_CANONICAL_SOURCE_REJECTED",
                failure.response().failureCode());
        assertEquals(0, changeRepository.count());
        assertEquals(0, operationRepository.count());
        assertEquals(0, workspaceOperationRepository.count());
        assertEquals(0, workSessionRepository.count());
        assertEquals(0, agentRunRepository.count());
    }

    @Test
    void nonCanonicalProjectIdentityCreatesNoChangeOrRelatedSideEffect() {
        ProjectEntity foreign = project("foreign-create-" + UUID.randomUUID());
        enablePolicy(foreign, 7);

        DevelopmentChangeRejectedException failure = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> service.create(
                        actor, foreign.getId(), UUID.randomUUID(),
                        new CreateDevelopmentChangeRequest("Foreign source")));

        assertEquals(V2FailureCategory.OWNERSHIP, failure.response().failureCategory());
        assertEquals("DEVELOPMENT_CHANGE_PROJECT_IDENTITY_MISMATCH",
                failure.response().failureCode());
        assertEquals(0, changeRepository.count());
        assertEquals(0, operationRepository.count());
        assertEquals(0, workspaceOperationRepository.count());
        assertEquals(0, workSessionRepository.count());
        assertEquals(0, agentRunRepository.count());
        verify(canonicalSourceAdmissionService, never()).observeCanonicalSource(foreign);
    }

    @Test
    void completedOperationReplaysWithoutMutationAfterGateIsDisabled() {
        UUID key = UUID.randomUUID();
        var created = service.create(
                actor, project.getId(), key,
                new CreateDevelopmentChangeRequest("Synthetic durable replay"));
        properties.setMutationsEnabled(false);

        var replayed = service.create(
                actor, project.getId(), key,
                new CreateDevelopmentChangeRequest("Synthetic durable replay"));

        assertTrue(replayed.replayed());
        assertEquals(created.operationId(), replayed.operationId());
        assertEquals(created.receiptSha256(), replayed.receiptSha256());
        assertFalse(replayed.developmentChange().mutationsEnabled());
        assertEquals(1, changeRepository.count());
        assertEquals(1, operationRepository.count());
    }

    @Test
    void occupiedOrUnprovableServerBranchFailsClosedWithAudit() {
        long auditsBefore = auditRepository.count();
        when(gitRepositoryService.exactLocalHeadExists(
                org.mockito.ArgumentMatchers.eq(project.getRepoPath()), anyString()))
                .thenReturn(true);

        DevelopmentChangeRejectedException occupied = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> service.create(
                        actor, project.getId(), UUID.randomUUID(),
                        new CreateDevelopmentChangeRequest("Synthetic occupied branch")));
        assertEquals("DEVELOPMENT_CHANGE_IDENTITY_COLLISION",
                occupied.response().failureCode());

        when(gitRepositoryService.exactLocalHeadExists(
                org.mockito.ArgumentMatchers.eq(project.getRepoPath()), anyString()))
                .thenThrow(new GitRepositoryOperationException("synthetic inspection failure"));
        DevelopmentChangeRejectedException unavailable = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> service.create(
                        actor, project.getId(), UUID.randomUUID(),
                        new CreateDevelopmentChangeRequest("Synthetic unavailable branch")));
        assertEquals("DEVELOPMENT_CHANGE_BRANCH_STATE_UNAVAILABLE",
                unavailable.response().failureCode());
        assertEquals(auditsBefore + 2, auditRepository.count());
        assertEquals(0, changeRepository.count());
        assertEquals(0, operationRepository.count());
    }

    @Test
    void rejectsClientOwnedSelectorsAndIdempotencyReuseWithoutCreatingAChange() {
        UUID key = UUID.randomUUID();
        CreateDevelopmentChangeRequest selector =
                new CreateDevelopmentChangeRequest("Synthetic selector rejection");
        selector.rejectUnsupportedField("workspaceBranch", "client/chosen");

        DevelopmentChangeRejectedException selectorFailure = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> service.create(actor, project.getId(), key, selector));
        assertEquals("DEVELOPMENT_CHANGE_INTERNAL_SELECTOR_REJECTED",
                selectorFailure.response().failureCode());
        verify(gitRepositoryService, never()).resolveExactHeadCommit(anyString(), anyString());

        var created = service.create(
                actor, project.getId(), key,
                new CreateDevelopmentChangeRequest("Synthetic accepted title"));
        DevelopmentChangeRejectedException reused = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> service.create(
                        actor, project.getId(), key,
                        new CreateDevelopmentChangeRequest("Different title")));
        assertEquals("DEVELOPMENT_CHANGE_IDEMPOTENCY_CONFLICT",
                reused.response().failureCode());
        assertEquals(created.developmentChange().changeKey(),
                changeRepository.findAll().getFirst().getChangeKey());
        assertEquals(1, changeRepository.count());
    }

    @Test
    void listAndDetailProjectServerOwnedStateAndNextAction() {
        var created = service.create(
                actor, project.getId(), UUID.randomUUID(),
                new CreateDevelopmentChangeRequest("Synthetic read projection"));

        var listed = service.list(project.getId());
        var detail = service.detail(project.getId(), created.developmentChange().changeKey());

        assertEquals(1, listed.size());
        assertEquals(detail, listed.getFirst());
        assertNull(detail.activeSessionId());
        assertTrue(detail.mutationsEnabled());
        assertEquals(DevelopmentChangeActionKind.PROVISION_WORKSPACE,
                detail.primaryAction().kind());

        properties.setMutationsEnabled(false);
        var disabledProjection = service.detail(
                project.getId(), created.developmentChange().changeKey());
        assertFalse(disabledProjection.mutationsEnabled());
        assertEquals(DevelopmentChangeActionKind.WAIT_FOR_ENABLEMENT,
                disabledProjection.primaryAction().kind());
    }

    @Test
    void pausesAndAbandonsOnlyWithoutAnActiveSession() {
        var created = service.create(
                actor, project.getId(), UUID.randomUUID(),
                new CreateDevelopmentChangeRequest("Synthetic lifecycle"));
        UUID changeKey = created.developmentChange().changeKey();

        var paused = service.pause(
                actor, project.getId(), changeKey, UUID.randomUUID());
        var abandoned = service.abandon(
                actor, project.getId(), changeKey, UUID.randomUUID());

        assertEquals(DevelopmentChangeStatus.PAUSED,
                paused.developmentChange().status());
        assertEquals(DevelopmentChangeStatus.ABANDONED,
                abandoned.developmentChange().status());
        assertEquals(3, operationRepository.count());
        assertEquals(3, auditRepository.findAll().stream()
                .filter(event -> event.getEventType().startsWith("DEVELOPMENT_CHANGE_"))
                .count());
    }

    @Test
    void bindsOnlyAnExactServerOwnedSessionAndRejectsCrossProjectOwnership() {
        var created = service.create(
                actor, project.getId(), UUID.randomUUID(),
                new CreateDevelopmentChangeRequest("Synthetic binding"));
        var change = changeRepository
                .findByChangeKey(created.developmentChange().changeKey()).orElseThrow();
        markWorkspaceReady(change);
        WorkSessionEntity session = matchingSession(project, change);

        var bound = service.bindSession(
                actor, project.getId(), change.getChangeKey(), session.getId(), UUID.randomUUID());

        assertEquals(session.getId(), bound.developmentChange().activeSessionId());
        assertEquals(DevelopmentChangeActionKind.CONTINUE_SESSION,
                bound.developmentChange().primaryAction().kind());
        assertEquals(change.getId(), workSessionRepository
                .findWithProjectAndDevelopmentChangeById(session.getId())
                .orElseThrow().getDevelopmentChange().getId());

        ProjectEntity foreignProject = project("foreign-" + UUID.randomUUID());
        enablePolicy(foreignProject, 7);
        DevelopmentChangeRejectedException foreign = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> service.bindSession(
                        actor, foreignProject.getId(), change.getChangeKey(),
                        session.getId(), UUID.randomUUID()));
        assertEquals("DEVELOPMENT_CHANGE_OWNERSHIP_MISMATCH",
                foreign.response().failureCode());
    }

    @Test
    void refusesLegacyShapedSessionAndLeavesItsBindingNull() {
        var created = service.create(
                actor, project.getId(), UUID.randomUUID(),
                new CreateDevelopmentChangeRequest("Synthetic legacy refusal"));
        var change = changeRepository
                .findByChangeKey(created.developmentChange().changeKey()).orElseThrow();
        markWorkspaceReady(change);
        WorkSessionEntity legacy = matchingSession(project, change);
        legacy.setWorkspaceBranch("atenea/session-" + legacy.getId());
        legacy.setWorkspaceIdentity("remote:synthetic-worker-01:work-session:"
                + legacy.getRemoteSessionId());
        workSessionRepository.saveAndFlush(legacy);

        DevelopmentChangeRejectedException failure = assertThrows(
                DevelopmentChangeRejectedException.class,
                () -> service.bindSession(
                        actor, project.getId(), change.getChangeKey(), legacy.getId(),
                        UUID.randomUUID()));

        assertEquals("DEVELOPMENT_CHANGE_SESSION_OWNERSHIP_MISMATCH",
                failure.response().failureCode());
        assertNull(workSessionRepository
                .findWithProjectAndDevelopmentChangeById(legacy.getId())
                .orElseThrow().getDevelopmentChange());
    }

    private ProjectEntity project(String name) {
        ProjectEntity value = new ProjectEntity();
        value.setName(name);
        value.setRepoPath("/tmp/" + name);
        value.setDefaultBaseBranch("main");
        value.setCreatedAt(Instant.now());
        value.setUpdatedAt(value.getCreatedAt());
        return projectRepository.saveAndFlush(value);
    }

    private ProjectEntity canonicalProject() {
        ProjectEntity value = new ProjectEntity();
        value.setName(ProjectCodexIdentity.PROJECT_NAME);
        value.setRepoPath(ProjectCodexIdentity.REPO_PATH);
        value.setDefaultBaseBranch(ProjectCodexIdentity.BRANCH);
        value.setCreatedAt(Instant.now());
        value.setUpdatedAt(value.getCreatedAt());
        return value;
    }

    private CanonicalSourceAdmissionService.CanonicalSourceObservation canonicalObservation() {
        return new CanonicalSourceAdmissionService.CanonicalSourceObservation(
                ProjectCodexIdentity.REPOSITORY,
                "refs/heads/" + ProjectCodexIdentity.BRANCH,
                BASE_COMMIT,
                "a".repeat(64),
                Instant.now());
    }

    private OperatorEntity operator(String email) {
        OperatorEntity value = new OperatorEntity();
        value.setEmail(email);
        value.setDisplayName("Synthetic M2 operator");
        value.setPasswordHash("synthetic-hash");
        value.setActive(true);
        value.setCreatedAt(Instant.now());
        value.setUpdatedAt(value.getCreatedAt());
        return operatorRepository.saveAndFlush(value);
    }

    private void enablePolicy(ProjectEntity target, long revision) {
        Instant now = Instant.now();
        if (!globalGateRepository.existsById(DevelopmentChangePolicy.CAPABILITY)) {
            V2GlobalCapabilityGateEntity global = new V2GlobalCapabilityGateEntity();
            global.setCapability(DevelopmentChangePolicy.CAPABILITY);
            global.setEnabled(true);
            global.setRevision(1);
            global.setCreatedAt(now);
            global.setUpdatedAt(now);
            globalGateRepository.saveAndFlush(global);
        }

        V2ProjectCapabilityPolicyEntity exact = new V2ProjectCapabilityPolicyEntity();
        exact.setProjectId(target.getId());
        exact.setCapability(DevelopmentChangePolicy.CAPABILITY);
        exact.setEnabled(true);
        exact.setPolicyRevision(revision);
        exact.setCreatedAt(now);
        exact.setUpdatedAt(now);
        projectPolicyRepository.saveAndFlush(exact);
    }

    private WorkSessionEntity matchingSession(
            ProjectEntity target,
            com.atenea.persistence.developmentchange.DevelopmentChangeEntity change) {
        Instant now = Instant.now();
        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(target);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Synthetic M2 session");
        session.setBaseBranch("main");
        session.setWorkspaceBranch(change.getWorkspaceBranch());
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(change.getSelectedWorkerId());
        session.setWorkspaceIdentity(change.getWorkspaceIdentity());
        session.setRemoteSessionId(UUID.randomUUID());
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setRemoteCloseState(RemoteCloseState.NOT_STARTED);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now);
        session.setLastActivityAt(now);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session = workSessionRepository.saveAndFlush(session);
        assertNotNull(session.getId());
        return session;
    }

    private void markWorkspaceReady(
            com.atenea.persistence.developmentchange.DevelopmentChangeEntity change) {
        change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        change.setWorkspaceOperationRevision(1);
        change.setWorkspaceObservationSha256("e".repeat(64));
        change.setWorkspaceUpdatedAt(Instant.now());
        change.setUpdatedAt(change.getWorkspaceUpdatedAt());
        changeRepository.saveAndFlush(change);
    }

    private void registerWorker() {
        Instant now = Instant.now();
        WorkerNodeEntity worker = new WorkerNodeEntity();
        worker.setId("synthetic-worker-01");
        worker.setProtocolVersion(RemoteWorkerProperties.PROTOCOL);
        worker.setEndpoint("http://127.0.0.1:1");
        worker.setEnabled(true);
        worker.setHealthy(true);
        worker.setNormalCapacity(4);
        worker.setHeavyCapacity(2);
        worker.setNormalInUse(0);
        worker.setHeavyInUse(0);
        worker.setCapabilities("[]");
        worker.setLastHeartbeatAt(now);
        worker.setCreatedAt(now);
        worker.setUpdatedAt(now);
        workerNodeRepository.saveAndFlush(worker);
    }
}
