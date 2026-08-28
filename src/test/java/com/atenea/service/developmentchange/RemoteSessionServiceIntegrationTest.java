package com.atenea.service.developmentchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atenea.api.developmentchange.OpenOrResolveRemoteSessionRequest;
import com.atenea.api.developmentchange.RemoteSessionResolution;
import com.atenea.auth.AuthenticatedOperator;
import com.atenea.developmentchange.DevelopmentChangeProperties;
import com.atenea.developmentchange.RemoteWorkBetaProperties;
import com.atenea.persistence.auth.OperatorEntity;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.developmentchange.RemoteSessionOperationRepository;
import com.atenea.persistence.developmentchange.RemoteSessionOperationEntity;
import com.atenea.persistence.developmentchange.RemoteSessionOperationKind;
import com.atenea.persistence.developmentchange.RemoteSessionOperationState;
import com.atenea.persistence.project.ProjectEntity;
import com.atenea.persistence.project.ProjectRepository;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateEntity;
import com.atenea.persistence.v2control.V2GlobalCapabilityGateRepository;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyEntity;
import com.atenea.persistence.v2control.V2ProjectCapabilityPolicyRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionPullRequestStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.WorkerNodeEntity;
import com.atenea.persistence.worksession.WorkerNodeRepository;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerProperties;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "atenea.development-change.mutations-enabled=true",
        "atenea.development-change.session-binding-enabled=true",
        "atenea.remote-work-beta.open-or-resolve-enabled=true",
        "atenea.remote-work-beta.recovery-enabled=false",
        "atenea.remote-worker.worker-id=synthetic-worker-01"
})
@Transactional
class RemoteSessionServiceIntegrationTest {

    @Autowired private RemoteSessionService service;
    @Autowired private DevelopmentChangeProperties developmentChangeProperties;
    @Autowired private RemoteWorkBetaProperties betaProperties;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private DevelopmentChangeRepository changeRepository;
    @Autowired private RemoteSessionOperationRepository operationRepository;
    @Autowired private WorkSessionRepository workSessionRepository;
    @Autowired private WorkerNodeRepository workerNodeRepository;
    @Autowired private V2GlobalCapabilityGateRepository globalGateRepository;
    @Autowired private V2ProjectCapabilityPolicyRepository projectPolicyRepository;
    @Autowired private EntityManager entityManager;

    private ProjectEntity project;
    private DevelopmentChangeEntity change;
    private OperatorEntity operator;
    private AuthenticatedOperator actor;

    @BeforeEach
    void setUp() {
        developmentChangeProperties.setMutationsEnabled(true);
        developmentChangeProperties.setSessionBindingEnabled(true);
        betaProperties.setOpenOrResolveEnabled(true);
        betaProperties.setRecoveryEnabled(false);

        String identity = UUID.randomUUID().toString();
        project = project("m25-open-" + identity);
        operator = operator(identity + "@atenea.test");
        actor = new AuthenticatedOperator(
                operator.getId(), operator.getEmail(), operator.getDisplayName());
        registerWorker("synthetic-worker-01");
        enablePolicy(DevelopmentChangePolicy.CAPABILITY, project, 7);
        enablePolicy(RemoteWorkBetaPolicy.CAPABILITY, project, 3);
        change = change(project, DevelopmentChangeStatus.OPEN,
                DevelopmentChangeWorkspaceState.READY);
    }

    @Test
    void createsAndBindsOneServerOwnedRemoteSessionThenReplaysAfterResponseLossAndReload() {
        UUID key = UUID.randomUUID();
        long expectedRevision = change.getVersion();

        var created = service.openOrResolve(
                actor, project.getId(), change.getChangeKey(), key,
                new OpenOrResolveRemoteSessionRequest(expectedRevision));
        entityManager.flush();
        entityManager.clear();
        var replayed = service.openOrResolve(
                actor, project.getId(), change.getChangeKey(), key,
                new OpenOrResolveRemoteSessionRequest(expectedRevision));

        assertEquals(RemoteSessionResolution.CREATED, created.resolution());
        assertEquals(created.operationId(), replayed.operationId());
        assertEquals(created.receiptSha256(), replayed.receiptSha256());
        assertEquals(created.sessionId(), replayed.sessionId());
        assertEquals(created.remoteSessionId(), replayed.remoteSessionId());
        assertFalse(created.replayed());
        assertTrue(replayed.replayed());
        assertEquals(1, workSessionRepository.count());
        assertEquals(1, operationRepository.count());

        WorkSessionEntity persisted = workSessionRepository
                .findWithProjectAndDevelopmentChangeById(created.sessionId()).orElseThrow();
        assertEquals(change.getChangeKey(), persisted.getDevelopmentChange().getChangeKey());
        assertEquals(ExecutionTarget.REMOTE, persisted.getExecutionTarget());
        assertEquals(change.getWorkspaceBranch(), persisted.getWorkspaceBranch());
        assertEquals(change.getWorkspaceIdentity(), persisted.getWorkspaceIdentity());
        assertEquals(change.getSelectedWorkerId(), persisted.getSelectedWorkerId());
        assertEquals(ProjectCodexIdentity.WORKLOAD_KIND, persisted.getRemoteWorkloadKind());
        assertEquals(change.getBaseRef(), persisted.getCanonicalSourceRef());
        assertEquals(change.getBaseCommit(), persisted.getCanonicalSourceCommit());
        assertEquals(change.getSourceFingerprintSha256(), created.sourceFingerprintSha256());
        assertNotNull(created.ownershipFingerprintSha256());
        assertNotNull(created.nextAction());
    }

    @Test
    void resolvesTheExactExistingSessionAndPausedChangeNeverCreates() {
        WorkSessionEntity exact = exactSession(change, true);
        change.setStatus(DevelopmentChangeStatus.PAUSED);
        change.setUpdatedAt(Instant.now());
        change = changeRepository.saveAndFlush(change);

        var resolved = service.openOrResolve(
                actor, project.getId(), change.getChangeKey(), UUID.randomUUID(),
                new OpenOrResolveRemoteSessionRequest(change.getVersion()));

        assertEquals(RemoteSessionResolution.RESOLVED, resolved.resolution());
        assertEquals(exact.getId(), resolved.sessionId());
        assertEquals(1, workSessionRepository.count());

        exact.setDevelopmentChange(null);
        workSessionRepository.saveAndFlush(exact);
        change = changeRepository.findById(change.getId()).orElseThrow();
        RemoteSessionRejectedException pausedWithoutExact = assertThrows(
                RemoteSessionRejectedException.class,
                () -> service.openOrResolve(
                        actor, project.getId(), change.getChangeKey(), UUID.randomUUID(),
                        new OpenOrResolveRemoteSessionRequest(change.getVersion())));
        assertEquals("REMOTE_SESSION_PAUSED_RESOLVE_ONLY",
                pausedWithoutExact.response().failureCode());
        assertEquals(1, workSessionRepository.count());
    }

    @Test
    void hardensRequestAndRejectsStaleDisabledAndCrossKeyReuseWithoutSessionEffects() {
        OpenOrResolveRemoteSessionRequest selector =
                new OpenOrResolveRemoteSessionRequest(change.getVersion());
        selector.rejectUnsupportedField("sessionId", 19);
        RemoteSessionRejectedException hardened = assertThrows(
                RemoteSessionRejectedException.class,
                () -> service.openOrResolve(
                        actor, project.getId(), change.getChangeKey(), UUID.randomUUID(), selector));
        assertEquals("REMOTE_SESSION_CLIENT_SELECTOR_REJECTED",
                hardened.response().failureCode());
        assertNotNull(hardened.response().operationId());
        assertEquals(RemoteSessionOperationState.REJECTED, hardened.response().state());
        assertEquals(1L, hardened.response().revision());
        assertNotNull(hardened.response().receiptSha256());
        assertFalse(hardened.response().replayed());
        assertEquals(change.getChangeKey(), hardened.response().changeKey());
        assertNotNull(hardened.response().sourceFingerprintSha256());
        assertNotNull(hardened.response().ownershipFingerprintSha256());

        UUID staleKey = UUID.randomUUID();
        RemoteSessionRejectedException stale = assertThrows(
                RemoteSessionRejectedException.class,
                () -> service.openOrResolve(
                        actor, project.getId(), change.getChangeKey(), staleKey,
                        new OpenOrResolveRemoteSessionRequest(change.getVersion() + 1)));
        assertEquals("REMOTE_SESSION_CHANGE_REVISION_STALE", stale.response().failureCode());
        RemoteSessionRejectedException staleReplay = assertThrows(
                RemoteSessionRejectedException.class,
                () -> service.openOrResolve(
                        actor, project.getId(), change.getChangeKey(), staleKey,
                        new OpenOrResolveRemoteSessionRequest(change.getVersion() + 1)));
        assertEquals(stale.response().operationId(), staleReplay.response().operationId());
        assertEquals(stale.response().receiptSha256(), staleReplay.response().receiptSha256());
        assertTrue(staleReplay.response().replayed());

        betaProperties.setOpenOrResolveEnabled(false);
        RemoteSessionRejectedException disabled = assertThrows(
                RemoteSessionRejectedException.class,
                () -> service.openOrResolve(
                        actor, project.getId(), change.getChangeKey(), UUID.randomUUID(),
                        new OpenOrResolveRemoteSessionRequest(change.getVersion())));
        assertEquals("REMOTE_WORK_BETA_OPEN_OR_RESOLVE_DISABLED",
                disabled.response().failureCode());
        betaProperties.setOpenOrResolveEnabled(true);

        UUID key = UUID.randomUUID();
        var created = service.openOrResolve(
                actor, project.getId(), change.getChangeKey(), key,
                new OpenOrResolveRemoteSessionRequest(change.getVersion()));
        RemoteSessionRejectedException conflict = assertThrows(
                RemoteSessionRejectedException.class,
                () -> service.openOrResolve(
                        actor, project.getId(), change.getChangeKey(), key,
                        new OpenOrResolveRemoteSessionRequest(created.changeRevision())));
        assertEquals("REMOTE_SESSION_IDEMPOTENCY_CONFLICT", conflict.response().failureCode());
        assertEquals(1, workSessionRepository.count());
    }

    @Test
    void rejectsWorkspaceStateActiveOperationMismatchForeignAndAmbiguousRetention() {
        change.setWorkspaceState(DevelopmentChangeWorkspaceState.BLOCKED);
        change.setUpdatedAt(Instant.now());
        change = changeRepository.saveAndFlush(change);
        assertRejectedWithoutSession("REMOTE_SESSION_WORKSPACE_NOT_READY");

        change.setWorkspaceState(DevelopmentChangeWorkspaceState.READY);
        change.setUpdatedAt(Instant.now());
        change = changeRepository.saveAndFlush(change);
        WorkSessionEntity foreign = exactSession(change, false);
        foreign.setWorkspaceBranch("atenea/foreign-retained");
        workSessionRepository.saveAndFlush(foreign);
        assertRejectedWithoutNewSession("REMOTE_SESSION_FOREIGN_RESOURCE");

        foreign.setWorkspaceBranch(change.getWorkspaceBranch());
        foreign.setWorkspaceIdentity(change.getWorkspaceIdentity());
        workSessionRepository.saveAndFlush(foreign);
        ProjectEntity foreignProject = project("m25-foreign-" + UUID.randomUUID());
        exactSession(change, false, foreignProject);
        assertRejectedWithoutNewSession("REMOTE_SESSION_RETAINED_STATE_AMBIGUOUS");
    }

    @Test
    void createsChangeOwnedSessionDespiteStaleWorkerProjection() {
        WorkerNodeEntity worker = workerNodeRepository.findById("synthetic-worker-01").orElseThrow();
        worker.setEnabled(false);
        worker.setHealthy(false);
        worker.setCapabilities(ProjectCodexIdentity.WORKLOAD_KIND);
        workerNodeRepository.saveAndFlush(worker);

        var created = service.openOrResolve(
                actor, project.getId(), change.getChangeKey(), UUID.randomUUID(),
                new OpenOrResolveRemoteSessionRequest(change.getVersion()));

        assertEquals(RemoteSessionResolution.CREATED, created.resolution());
        assertEquals("synthetic-worker-01", workSessionRepository.findById(created.sessionId())
                .orElseThrow().getSelectedWorkerId());
    }

    @Test
    void linkedSessionCanonicalCopiesDoNotGovernV4Resolution() {
        WorkSessionEntity linked = exactSession(change, true);
        linked.setCanonicalSourceCommit("9".repeat(40));
        linked = workSessionRepository.saveAndFlush(linked);
        Long linkedId = linked.getId();
        UUID remoteId = linked.getRemoteSessionId();

        var resolved = service.openOrResolve(
                actor, project.getId(), change.getChangeKey(), UUID.randomUUID(),
                new OpenOrResolveRemoteSessionRequest(change.getVersion()));

        assertEquals(RemoteSessionResolution.RESOLVED, resolved.resolution());
        assertEquals(linkedId, resolved.sessionId());
        WorkSessionEntity preserved = workSessionRepository.findById(linkedId).orElseThrow();
        assertEquals("9".repeat(40), preserved.getCanonicalSourceCommit());
        assertEquals(remoteId, preserved.getRemoteSessionId());
        assertEquals(change.getId(), preserved.getDevelopmentChange().getId());
    }

    @Test
    void rollbackAndRecoveryFailClosedWithoutFabricatingAdoptingClosingDeletingOrRebinding() {
        long beforeSessions = workSessionRepository.count();
        betaProperties.setOpenOrResolveEnabled(false);
        assertRejectedWithoutSession("REMOTE_WORK_BETA_OPEN_OR_RESOLVE_DISABLED");
        assertEquals(beforeSessions, workSessionRepository.count());

        betaProperties.setOpenOrResolveEnabled(true);
        WorkSessionEntity foreign = exactSession(change, false);
        UUID foreignRemoteId = foreign.getRemoteSessionId();
        Long foreignId = foreign.getId();
        UUID recoveryKey = UUID.randomUUID();
        RemoteSessionOperationEntity requested = new RemoteSessionOperationEntity();
        requested.setOperationId(UUID.randomUUID());
        requested.setOperator(operator);
        requested.setProject(project);
        requested.setDevelopmentChange(change);
        requested.setIdempotencyKey(recoveryKey);
        requested.setOperationKind(RemoteSessionOperationKind.OPEN_OR_RESOLVE_REMOTE_SESSION);
        requested.setExpectedChangeRevision(change.getVersion());
        requested.setRequestFingerprintSha256("1".repeat(64));
        requested.setTargetFingerprintSha256("2".repeat(64));
        requested.setSourceFingerprintSha256(change.getSourceFingerprintSha256());
        requested.setOwnershipFingerprintSha256("3".repeat(64));
        requested.setBetaPolicyRevision(3);
        requested.setState(RemoteSessionOperationState.REQUESTED);
        requested.setRevision(0);
        requested.setRequestedAt(Instant.now());
        requested.setUpdatedAt(requested.getRequestedAt());
        operationRepository.saveAndFlush(requested);
        assertRejectedWithoutNewSession("REMOTE_SESSION_OPERATION_ACTIVE");

        betaProperties.setRecoveryEnabled(true);
        new RemoteSessionStartupReconciler(betaProperties, service).run(null);
        betaProperties.setRecoveryEnabled(false);
        RemoteSessionOperationEntity blocked = operationRepository
                .findByOperatorIdAndOperationKindAndIdempotencyKey(
                        operator.getId(), RemoteSessionOperationKind.OPEN_OR_RESOLVE_REMOTE_SESSION,
                        recoveryKey)
                .orElseThrow();
        assertEquals(RemoteSessionOperationState.BLOCKED, blocked.getState());
        assertEquals("REMOTE_SESSION_RECOVERY_INCOMPLETE", blocked.getFailureCode());
        assertNotNull(blocked.getReceiptSha256());
        WorkSessionEntity preserved = workSessionRepository.findById(foreignId).orElseThrow();
        assertEquals(foreignRemoteId, preserved.getRemoteSessionId());
        assertNull(preserved.getDevelopmentChange());
        assertNotEquals(WorkSessionStatus.CLOSED, preserved.getStatus());
    }

    private void assertRejectedWithoutSession(String code) {
        long before = workSessionRepository.count();
        RemoteSessionRejectedException failure = assertThrows(
                RemoteSessionRejectedException.class,
                () -> service.openOrResolve(
                        actor, project.getId(), change.getChangeKey(), UUID.randomUUID(),
                        new OpenOrResolveRemoteSessionRequest(change.getVersion())));
        assertEquals(code, failure.response().failureCode());
        assertEquals(before, workSessionRepository.count());
    }

    private void assertRejectedWithoutNewSession(String code) {
        long before = workSessionRepository.count();
        assertRejectedWithoutSession(code);
        assertEquals(before, workSessionRepository.count());
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

    private OperatorEntity operator(String email) {
        OperatorEntity value = new OperatorEntity();
        value.setEmail(email);
        value.setDisplayName("Synthetic M2.5 operator");
        value.setPasswordHash("synthetic-hash");
        value.setActive(true);
        value.setCreatedAt(Instant.now());
        value.setUpdatedAt(value.getCreatedAt());
        return operatorRepository.saveAndFlush(value);
    }

    private DevelopmentChangeEntity change(
            ProjectEntity target,
            DevelopmentChangeStatus status,
            DevelopmentChangeWorkspaceState workspaceState) {
        UUID key = UUID.randomUUID();
        Instant now = Instant.now();
        DevelopmentChangeEntity value = new DevelopmentChangeEntity();
        value.setChangeKey(key);
        value.setProject(target);
        value.setTitle("Synthetic remote edit");
        value.setStatus(status);
        value.setBaseRef("refs/heads/main");
        value.setBaseCommit("1".repeat(40));
        value.setObservedCanonicalCommit(value.getBaseCommit());
        value.setWorkspaceBranch("atenea/change-" + key);
        value.setWorkspaceIdentity("remote:synthetic-worker-01:change:" + key);
        value.setSelectedWorkerId("synthetic-worker-01");
        value.setProjectPolicyRevision(7);
        value.setSourceRevision(0);
        value.setSourceFingerprintSha256("a".repeat(64));
        value.setSourceState(DevelopmentChangeSourceState.CLEAN);
        value.setWorkspaceState(workspaceState);
        value.setWorkspaceOperationRevision(workspaceState == DevelopmentChangeWorkspaceState.READY ? 1 : 0);
        value.setWorkspaceObservationSha256(
                workspaceState == DevelopmentChangeWorkspaceState.READY ? "b".repeat(64) : null);
        value.setWorkspaceUpdatedAt(
                workspaceState == DevelopmentChangeWorkspaceState.READY ? now : null);
        value.setValidationState(DevelopmentChangeProjectionState.NOT_STARTED);
        value.setReviewState(DevelopmentChangeProjectionState.NOT_STARTED);
        value.setIntegrationState(DevelopmentChangeProjectionState.NOT_STARTED);
        value.setReleaseState(DevelopmentChangeProjectionState.NOT_STARTED);
        value.setCreatedAt(now);
        value.setUpdatedAt(now);
        return changeRepository.saveAndFlush(value);
    }

    private WorkSessionEntity exactSession(DevelopmentChangeEntity target, boolean linked) {
        return exactSession(target, linked, project);
    }

    private WorkSessionEntity exactSession(
            DevelopmentChangeEntity target,
            boolean linked,
            ProjectEntity owner) {
        Instant now = Instant.now();
        WorkSessionEntity session = new WorkSessionEntity();
        session.setProject(owner);
        session.setDevelopmentChange(linked ? target : null);
        session.setStatus(WorkSessionStatus.OPEN);
        session.setTitle("Synthetic exact remote session");
        session.setBaseBranch("main");
        session.setWorkspaceBranch(target.getWorkspaceBranch());
        session.setExecutionTarget(ExecutionTarget.REMOTE);
        session.setSelectedWorkerId(target.getSelectedWorkerId());
        session.setWorkspaceIdentity(target.getWorkspaceIdentity());
        session.setRemoteSessionId(UUID.randomUUID());
        session.setRemoteWorkloadKind(ProjectCodexIdentity.WORKLOAD_KIND);
        session.setRemoteCloseState(RemoteCloseState.NOT_STARTED);
        session.setCanonicalSourceRef(target.getBaseRef());
        session.setCanonicalSourceCommit(target.getBaseCommit());
        session.setCanonicalSourceObservationSha256(target.getSourceFingerprintSha256());
        session.setCanonicalSourceObservedAt(now);
        session.setPullRequestStatus(WorkSessionPullRequestStatus.NOT_CREATED);
        session.setOpenedAt(now);
        session.setLastActivityAt(now);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return workSessionRepository.saveAndFlush(session);
    }

    private void enablePolicy(String capability, ProjectEntity target, long revision) {
        Instant now = Instant.now();
        V2GlobalCapabilityGateEntity global = new V2GlobalCapabilityGateEntity();
        global.setCapability(capability);
        global.setEnabled(true);
        global.setRevision(1);
        global.setCreatedAt(now);
        global.setUpdatedAt(now);
        globalGateRepository.saveAndFlush(global);

        V2ProjectCapabilityPolicyEntity exact = new V2ProjectCapabilityPolicyEntity();
        exact.setProjectId(target.getId());
        exact.setCapability(capability);
        exact.setEnabled(true);
        exact.setPolicyRevision(revision);
        exact.setCreatedAt(now);
        exact.setUpdatedAt(now);
        projectPolicyRepository.saveAndFlush(exact);
    }

    private void registerWorker(String workerId) {
        Instant now = Instant.now();
        WorkerNodeEntity worker = new WorkerNodeEntity();
        worker.setId(workerId);
        worker.setProtocolVersion(RemoteWorkerProperties.PROTOCOL);
        worker.setEndpoint("http://127.0.0.1:1");
        worker.setEnabled(true);
        worker.setHealthy(true);
        worker.setNormalCapacity(4);
        worker.setHeavyCapacity(2);
        worker.setNormalInUse(0);
        worker.setHeavyInUse(0);
        worker.setCapabilities(ProjectCodexIdentity.WORKLOAD_KIND);
        worker.setLastHeartbeatAt(now);
        worker.setCreatedAt(now);
        worker.setUpdatedAt(now);
        workerNodeRepository.saveAndFlush(worker);
    }
}
