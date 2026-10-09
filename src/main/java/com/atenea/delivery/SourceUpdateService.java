package com.atenea.delivery;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.github.GitHubClient;
import com.atenea.github.GitHubRepositoryRef;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeEntity;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeSourceState;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationState;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceState;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.DevelopmentChangeBranchPublicationCommand;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateCommand;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateGateway;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteAgentRunCoordinator;
import com.atenea.remoteworker.RemoteRoutingSelector;
import com.atenea.remoteworker.RemoteWorkerException;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateCommand.Action;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateGateway.Preparation;
import com.atenea.remoteworker.DevelopmentChangeSourceUpdateGateway.State;
import com.atenea.service.worksession.AgentRunService;
import com.atenea.service.worksession.DevelopmentChangeBranchPublicationService;
import com.atenea.service.worksession.WorkSessionAcceptanceService;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable preparation outbox; HTTP observation never starts or retries a resolver. */
@Service
public class SourceUpdateService {
    private static final GitHubRepositoryRef APP = new GitHubRepositoryRef("jlnieto", "atenea");
    private final SourceUpdateStore store;
    private final WorkSessionRepository sessions;
    private final DevelopmentChangeRepository changes;
    private final DevelopmentChangeWorkspaceOperationRepository workspaceOperations;
    private final OperatorRepository operators;
    private final AgentRunRepository runs;
    private final SessionTurnRepository turns;
    private final AgentRunService agentRuns;
    private final RemoteAgentRunCoordinator coordinator;
    private final WorkSessionAcceptanceService acceptance;
    private final DevelopmentChangeBranchPublicationService ownership;
    private final MobileDeliveryService delivery;
    private final RemoteRoutingSelector routing;
    private final DevelopmentChangeSourceUpdateGateway gateway;
    private final GitHubClient github;
    private final RemoteWorkerProperties worker;
    private final com.atenea.remoteworker.RemoteWorkerClient sourceObserver;
    private final TransactionTemplate transaction;

    public SourceUpdateService(SourceUpdateStore store, WorkSessionRepository sessions,
            DevelopmentChangeRepository changes, DevelopmentChangeWorkspaceOperationRepository workspaceOperations,
            OperatorRepository operators, AgentRunRepository runs, SessionTurnRepository turns,
            AgentRunService agentRuns, RemoteAgentRunCoordinator coordinator, WorkSessionAcceptanceService acceptance,
            DevelopmentChangeBranchPublicationService ownership, MobileDeliveryService delivery,
            RemoteRoutingSelector routing, DevelopmentChangeSourceUpdateGateway gateway, GitHubClient github,
            RemoteWorkerProperties worker, com.atenea.remoteworker.RemoteWorkerClient sourceObserver, PlatformTransactionManager manager) {
        this.store=store; this.sessions=sessions; this.changes=changes; this.workspaceOperations=workspaceOperations;
        this.operators=operators; this.runs=runs; this.turns=turns; this.agentRuns=agentRuns;
        this.coordinator=coordinator; this.acceptance=acceptance; this.ownership=ownership; this.delivery=delivery;
        this.routing=routing; this.gateway=gateway; this.github=github; this.worker=worker;
        this.sourceObserver=sourceObserver;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public SourceUpdateOperation.View observe(Long sessionId, AuthenticatedOperator actor) {
        administrator(actor.operatorId());
        session(sessionId, false);
        return store.latest(sessionId).map(SourceUpdateOperation::view).orElse(null);
    }

    public boolean isEnabled() { return delivery.isEnabled() && worker.isEnabled(); }

    /** A repeated request for the same failed attempt never admits another run. */
    public SourceUpdateOperation.View retryResolver(Long sessionId, UUID operationId, Long sourceRunId, AuthenticatedOperator actor) {
        administrator(actor.operatorId());
        if (!isEnabled()) throw rejected("SOURCE_UPDATE_DISABLED");
        Long admitted=transaction.execute(ignored -> {
            store.lockSessionBeforeReadingSource(sessionId);
            var session=session(sessionId,true);
            var op=store.get(operationId,true);
            if (!Objects.equals(op.sessionId(),sessionId) || !Objects.equals(store.latest(sessionId).orElseThrow().id(),operationId)) {
                throw rejected("SOURCE_UPDATE_OWNER_MISMATCH");
            }
            var retained=store.retryRun(operationId,sourceRunId);
            if (retained.isPresent()) return null;
            var change=requireStructure(session,op.command());
            if (!op.state().equals("FAILED") || !Objects.equals(op.resolverRunId(),sourceRunId)
                    || op.preparation()==null || op.resolverTurnId()==null
                    || change.getSourceRevision()<op.preparedRevision()
                    || !Objects.equals(change.getObservedCanonicalCommit(),op.command().owner().sourceCommit())
                    || !java.util.Set.of(DevelopmentChangeSourceState.DIRTY,DevelopmentChangeSourceState.CLEAN).contains(change.getSourceState())) {
                throw rejected("SOURCE_UPDATE_RETRY_EVIDENCE_MISMATCH");
            }
            var failed=runs.findByIdForUpdate(sourceRunId).orElseThrow(() -> rejected("SOURCE_UPDATE_RESOLVER_MISSING"));
            if (failed.getStatus()!=AgentRunStatus.FAILED || !Objects.equals(failed.getSession().getId(),sessionId)
                    || !Objects.equals(failed.getOriginTurn().getId(),op.resolverTurnId())
                    || !store.matchesResolver(op,failed)
                    || !Objects.equals(failed.getDevelopmentChangeKey(),op.command().owner().changeKey())
                    || !Objects.equals(failed.getChangeBaseCommit(),op.command().owner().baseCommit())
                    || !Objects.equals(failed.getSelectedWorkerId(),op.command().owner().workerId())
                    || !Objects.equals(failed.getWorkspaceIdentity(),op.command().owner().workspaceIdentity())
                    || !Objects.equals(failed.getRepositoryCommit(),op.command().owner().sourceCommit())
                    || failed.getExecutionTarget()!=ExecutionTarget.REMOTE
                    || !ProjectCodexIdentity.CHANGE_WORKLOAD_KIND.equals(failed.getWorkloadKind())) throw rejected("SOURCE_UPDATE_RETRY_EVIDENCE_MISMATCH");
            store.requireIdle();
            agentRuns.requireRemoteRetryEligible(failed);
            if (!routing.refreshKnownWorker(ProjectCodexIdentity.WORKER_ID,"development-change-source-update/v1")) {
                throw rejected("SOURCE_UPDATE_CAPABILITY_UNAVAILABLE");
            }
            // Observation is bounded and read-only. Never restore/reset partially edited files.
            var observed=sourceObserver.fingerprintSourceTree(session);
            if (observed==null || !"observed".equals(observed.state()) || observed.valuesExposed()
                    || !Objects.equals(observed.sessionId(),session.getRemoteSessionId().toString())
                    || !Objects.equals(observed.workspaceIdentity(),session.getWorkspaceIdentity())
                    || !Objects.equals(observed.projectId(),ProjectCodexIdentity.PROJECT_IDENTITY)
                    || !Objects.equals(observed.headCommit(),failed.getRepositoryCommit())
                    || observed.fingerprintSha256()==null || !observed.fingerprintSha256().matches("[0-9a-f]{64}")
                    || observed.stagedChangeCount()<0 || observed.unstagedChangeCount()<0 || observed.untrackedChangeCount()<0) {
                throw rejected("SOURCE_UPDATE_RETRY_OBSERVATION_MISMATCH");
            }
            boolean dirty=observed.stagedChangeCount()>0 || observed.unstagedChangeCount()>0 || observed.untrackedChangeCount()>0;
            var sourceState=dirty ? DevelopmentChangeSourceState.DIRTY : DevelopmentChangeSourceState.CLEAN;
            if (!Objects.equals(change.getSourceFingerprintSha256(),observed.fingerprintSha256()) || change.getSourceState()!=sourceState) {
                change.setSourceRevision(Math.addExact(change.getSourceRevision(),1));
                change.setSourceFingerprintSha256(observed.fingerprintSha256());
                change.setSourceState(sourceState);change.setWorkspaceUpdatedAt(Instant.now());
                invalidate(change,session);changes.saveAndFlush(change);
            }
            var retryId=store.authorizeRetry(operationId,sourceRunId,actor.operatorId(),change.getSourceRevision(),dirty,observed);
            var run=agentRuns.createSourceUpdateResolverRetryRun(sourceRunId,operationId);
            store.retried(retryId,run.getId(),operationId);
            return run.getId();
        });
        // Lost callback is recovered by the normal queued-run coordinator.
        if (admitted!=null) coordinator.dispatchAfterCommit(admitted);
        return store.get(operationId,false).view();
    }

    public SourceUpdateOperation.View request(Long sessionId, AuthenticatedOperator actor) {
        administrator(actor.operatorId());
        if (!delivery.isEnabled() || !worker.isEnabled()) throw rejected("SOURCE_UPDATE_DISABLED");
        return transaction.execute(ignored -> {
            store.lockSessionBeforeReadingSource(sessionId);
            var session = session(sessionId, true);
            var prior = store.latest(sessionId);
            if (prior.isPresent()) {
                // A duplicate action adopts its intent even after acceptance became stale.
                if (!java.util.Set.of("RESOLVER_COMPLETED","READY_TO_FINALIZE","PUBLISHED").contains(prior.get().state())) {
                    if (!Objects.equals(prior.get().command().publicationReceiptSha256(),session.getPublicationReceiptSha256())) {
                        throw rejected("SOURCE_UPDATE_PREDECESSOR_CHANGED");
                    }
                    return prior.get().view();
                }
            }
            String main = github.canonicalMain(APP);
            if (prior.isPresent() && Objects.equals(main,prior.get().command().targetMainCommit())) return prior.get().view();
            var change = ownership.requireExactOwner(session);
            if (prior.isEmpty() && (!"CONFLICTS".equals(delivery.observeIntegration(sessionId, actor).mergeState())
                    || !Objects.equals(change.getSourceRevision(), session.getPublishedSourceRevision())
                    || session.getPublicationReceiptSha256() == null || session.getRemoteSessionId() == null)) {
                throw rejected("SOURCE_UPDATE_EXACT_CONFLICT_REQUIRED");
            }
            var predecessor=prior.orElse(null);
            if (predecessor!=null && (predecessor.preparation()==null
                    || (!predecessor.state().equals("PUBLISHED") && (change.getSourceRevision()!=(predecessor.resultRevision()==null
                        ? predecessor.preparedRevision() : predecessor.resultRevision())
                        || !Objects.equals(change.getObservedCanonicalCommit(),predecessor.command().owner().sourceCommit())))
                    || session.getPullRequestStatus()!=com.atenea.persistence.worksession.WorkSessionPullRequestStatus.OPEN
                    || session.getPublicationReceiptSha256()==null || session.getRemoteSessionId()==null)) {
                throw rejected("SOURCE_UPDATE_CONTINUATION_EVIDENCE_MISMATCH");
            }
            store.requireIdle();
            String capability=predecessor==null ? "development-change-source-update/v1" : "development-change-source-update/v2";
            if (!routing.refreshKnownWorker(ProjectCodexIdentity.WORKER_ID, capability)) {
                throw rejected("SOURCE_UPDATE_CAPABILITY_UNAVAILABLE");
            }
            var command = new DevelopmentChangeSourceUpdateCommand(new DevelopmentChangeBranchPublicationCommand(
                    UUID.randomUUID(), UUID.randomUUID(), change.getChangeKey(), change.getProject().getId(),
                    ProjectCodexIdentity.PROJECT_IDENTITY, ProjectCodexIdentity.REPOSITORY, ProjectCodexIdentity.BRANCH,
                    change.getBaseCommit(), session.getFinalCommitSha(), change.getWorkspaceBranch(),
                    change.getWorkspaceIdentity(), change.getSelectedWorkerId(), change.getSourceRevision(),
                    predecessor!=null && !predecessor.state().equals("PUBLISHED") && change.getSourceState()==DevelopmentChangeSourceState.DIRTY
                        ? change.getSourceFingerprintSha256() : null),
                    main, session.getPublicationReceiptSha256(),predecessor==null ? null : predecessor.id(),
                    predecessor==null ? null : predecessor.preparation().receiptSha256(),
                    predecessor==null ? null : session.getPublishedSourceRevision());
            requireStructure(session, command);
            var operation = store.create(sessionId, actor.operatorId(), command,
                    change.getObservedCanonicalCommit(), change.getSourceFingerprintSha256());
            invalidate(change, session);
            return operation.view();
        });
    }

    @Scheduled(scheduler = "mobileDeliveryScheduler", fixedDelay = 3000)
    public void reconcilePending() {
        if (!isEnabled()) return;
        for (UUID id : store.pending()) {
            try { reconcile(id); }
            catch (RuntimeException failure) {
                try { mark(store.get(id, false), "ATTENTION", "SOURCE_UPDATE_OWNER_OR_STATE_CHANGED"); }
                catch (RuntimeException unavailable) {
                    // DB interruption leaves the durable intent for inspection after expiry.
                }
            }
        }
    }

    public void reconcile(UUID id) {
        var candidate = store.get(id, false);
        var claimed = transaction.execute(ignored -> {
            var session = session(candidate.sessionId(), true);
            var op = store.get(id, true);
            if (SourceUpdateOperation.TERMINAL.contains(op.state()) || op.state().equals("ATTENTION")) return null;
            var change = requireStructure(session, op.command());
            if (op.preparation() == null && (change.getSourceRevision() != op.command().owner().sourceRevision()
                    || !Objects.equals(change.getObservedCanonicalCommit(), op.originalSourceCommit())
                    || !Objects.equals(change.getSourceFingerprintSha256(), op.originalFingerprintSha256()))) {
                throw rejected("SOURCE_UPDATE_EVIDENCE_MISMATCH");
            }
            if (!store.lease(id, op.state(), worker.getWorkspaceProvisionTimeout().multipliedBy(3).plusSeconds(60))) return null;
            return op;
        });
        if (claimed == null) return;
        if (claimed.state().equals("RESOLVING")) { finish(claimed); return; }
        if (claimed.state().equals("READY_TO_RESOLVE")) { startResolver(claimed); return; }
        try {
            Preparation preparation = gateway.exchange(claimed.command(), claimed.state().equals("QUEUED")
                    ? Action.PREPARE : Action.INSPECT);
            if (preparation.state() == State.ABSENT) {
                if (claimed.errorCode() != null && !uncertain(claimed.errorCode())) {
                    mark(claimed, "BLOCKED", claimed.errorCode());
                    return;
                }
                preparation = gateway.exchange(claimed.command(), Action.PREPARE);
            }
            if (preparation.state() == State.PREPARED) {
                preparation = gateway.exchange(claimed.command(), Action.RECONCILE);
            }
            if (preparation.state() != State.NEEDS_RESOLUTION && preparation.state() != State.READY_TO_FINALIZE) {
                mark(claimed, "ATTENTION", "SOURCE_UPDATE_PREPARATION_INCOMPLETE");
                return;
            }
            persistPreparation(claimed, preparation);
        } catch (RemoteWorkerException failure) {
            String code = safeCode(failure.getFailureCode());
            boolean retainedBlocker = claimed.state().equals("UNCERTAIN")
                    && (!uncertain(code) || code.equals("SOURCE_UPDATE_PROTOCOL_FAILURE"));
            mark(claimed, retainedBlocker ? "ATTENTION" : "UNCERTAIN", code);
        } catch (RuntimeException failure) {
            mark(claimed, "ATTENTION", "SOURCE_UPDATE_EVIDENCE_MISMATCH");
        }
    }

    private void persistPreparation(SourceUpdateOperation claimed, Preparation preparation) {
        transaction.executeWithoutResult(ignored -> {
            var session = session(claimed.sessionId(), true);
            var op = store.get(claimed.id(), true);
            if (op.preparation() != null) return;
            var change = requireStructure(session, op.command());
            store.requireIdle();
            if (change.getSourceRevision() != op.command().owner().sourceRevision()
                    || !Objects.equals(change.getObservedCanonicalCommit(), op.originalSourceCommit())
                    || !Objects.equals(change.getSourceFingerprintSha256(), op.originalFingerprintSha256())) {
                throw rejected("SOURCE_UPDATE_EVIDENCE_MISMATCH");
            }
            change.setSourceRevision(Math.addExact(change.getSourceRevision(), 1));
            change.setObservedCanonicalCommit(op.command().owner().sourceCommit());
            session.setCanonicalSourceRef("refs/heads/main");
            session.setCanonicalSourceCommit(op.command().owner().sourceCommit());
            session.setCanonicalSourceObservationSha256(preparation.receiptSha256());
            session.setCanonicalSourceObservedAt(Instant.now());
            change.setSourceState(preparation.preparedFingerprintSha256() == null
                    ? DevelopmentChangeSourceState.CLEAN : DevelopmentChangeSourceState.DIRTY);
            if (preparation.preparedFingerprintSha256() != null) {
                change.setSourceFingerprintSha256(preparation.preparedFingerprintSha256());
            } else {
                var observed=sourceObserver.fingerprintSourceTree(session);
                if (observed==null || !"observed".equals(observed.state()) || observed.valuesExposed()
                        || !Objects.equals(observed.sessionId(),session.getRemoteSessionId().toString())
                        || !Objects.equals(observed.workspaceIdentity(),session.getWorkspaceIdentity())
                        || !Objects.equals(observed.projectId(),ProjectCodexIdentity.PROJECT_IDENTITY)
                        || !Objects.equals(observed.headCommit(),op.command().owner().sourceCommit())
                        || observed.fingerprintSha256()==null || !observed.fingerprintSha256().matches("[0-9a-f]{64}")
                        || observed.stagedChangeCount()!=0 || observed.unstagedChangeCount()!=0 || observed.untrackedChangeCount()!=0) {
                    throw rejected("SOURCE_UPDATE_CLEAN_OBSERVATION_MISMATCH");
                }
                change.setSourceFingerprintSha256(observed.fingerprintSha256());
            }
            change.setWorkspaceUpdatedAt(Instant.now());
            invalidate(change, session);
            changes.saveAndFlush(change);
            store.prepared(op.id(), preparation, change.getSourceRevision(), preparation.state() == State.NEEDS_RESOLUTION
                    ? "READY_TO_RESOLVE" : "READY_TO_FINALIZE");
        });
    }


    private void startResolver(SourceUpdateOperation claimed) {
        Long runId;
        try {
            runId = transaction.execute(ignored -> {
                var session = session(claimed.sessionId(), true);
                var op = store.get(claimed.id(), true);
                administrator(op.operatorId());
                var change = requireStructure(session, op.command());
                store.requireIdle();
                if (!op.state().equals("READY_TO_RESOLVE") || op.resolverRunId() != null
                        || op.resolverTurnId() != null || change.getSourceRevision() != op.preparedRevision()
                        || !Objects.equals(change.getObservedCanonicalCommit(), op.command().owner().sourceCommit())
                        || !Objects.equals(change.getSourceFingerprintSha256(), op.preparation().preparedFingerprintSha256())) {
                    throw rejected("SOURCE_UPDATE_EVIDENCE_MISMATCH");
                }
                var turn = new SessionTurnEntity();
                turn.setSession(session); turn.setActor(SessionTurnActor.ATENEA); turn.setInternal(false);
                turn.setCreatedAt(Instant.now()); turn.setMessageText(resolverPrompt(op));
                turns.saveAndFlush(turn);
                store.turn(op.id(), turn.getId());
                var run = agentRuns.createSourceUpdateResolverRun(session, turn, op.id());
                store.run(op.id(), run.getId());
                return run.getId();
            });
        } catch (RuntimeException failure) {
            mark(claimed, "ATTENTION", "SOURCE_UPDATE_RESOLVER_ADMISSION_FAILED");
            return;
        }
        // The normal coordinator recovers queued runs on startup if this callback is lost.
        coordinator.dispatchAfterCommit(runId);
    }

    private void finish(SourceUpdateOperation claimed) {
        transaction.executeWithoutResult(ignored -> {
            var session = session(claimed.sessionId(), true);
            var op = store.get(claimed.id(), true);
            var run = runs.findById(op.resolverRunId()).orElseThrow(() -> rejected("SOURCE_UPDATE_RESOLVER_MISSING"));
            var change = requireStructure(session, op.command());
            if (!Objects.equals(run.getSession().getId(), op.sessionId())
                    || !Objects.equals(run.getOriginTurn().getId(), op.resolverTurnId())
                    || !store.matchesResolver(op,run)
                    || !Objects.equals(run.getDevelopmentChangeKey(), op.command().owner().changeKey())
                    || !Objects.equals(run.getChangeBaseCommit(), op.command().owner().baseCommit())
                    || !Objects.equals(run.getRepositoryCommit(), op.command().owner().sourceCommit())
                    || !Objects.equals(run.getSelectedWorkerId(), op.command().owner().workerId())
                    || !Objects.equals(run.getWorkspaceIdentity(), op.command().owner().workspaceIdentity())
                    || run.getExecutionTarget() != ExecutionTarget.REMOTE
                    || !ProjectCodexIdentity.CHANGE_WORKLOAD_KIND.equals(run.getWorkloadKind())) {
                throw rejected("SOURCE_UPDATE_EVIDENCE_MISMATCH");
            }
            if (AgentRunStatus.nonTerminalStatuses().contains(run.getStatus())) {
                store.state(op.id(), "RESOLVING", null);
                return;
            }
            if (run.getStatus() != AgentRunStatus.SUCCEEDED) {
                store.state(op.id(), "FAILED", "SOURCE_UPDATE_RESOLVER_FAILED");
            } else if (!Objects.equals(change.getObservedCanonicalCommit(), op.command().owner().sourceCommit())) {
                store.state(op.id(), "FAILED", "SOURCE_UPDATE_RESOLVER_CHANGED_HEAD");
            } else if (change.getSourceRevision() <= op.preparedRevision()) {
                store.state(op.id(), "BLOCKED", "SOURCE_UPDATE_RESOLVER_NO_SOURCE_CHANGE");
            } else {
                // Codex completion is deliberately not proof of resolution or validation.
                store.completed(op.id(), change.getSourceRevision(), change.getSourceFingerprintSha256());
            }
        });
    }

    private void mark(SourceUpdateOperation claimed, String state, String code) {
        transaction.executeWithoutResult(ignored -> {
            session(claimed.sessionId(), true);
            var current = store.get(claimed.id(), true);
            if (!SourceUpdateOperation.TERMINAL.contains(current.state())
                    && (current.preparation() == null || current.state().equals(claimed.state()))) {
                store.state(current.id(), state, code);
            }
        });
    }

    private DevelopmentChangeEntity requireStructure(WorkSessionEntity session, DevelopmentChangeSourceUpdateCommand command) {
        var owner = command.owner();
        var change = changes.findByChangeKeyForUpdate(owner.changeKey()).orElseThrow(() -> rejected("SOURCE_UPDATE_OWNER_MISMATCH"));
        List<WorkSessionEntity> bound = sessions.findAllByDevelopmentChangeIdOrderByOpenedAtAscIdAsc(change.getId());
        if (session.getDevelopmentChange() == null || !Objects.equals(session.getDevelopmentChange().getId(), change.getId())
                || bound.size() != 1 || !Objects.equals(bound.getFirst().getId(), session.getId())
                || session.getStatus() != WorkSessionStatus.OPEN || session.getExecutionTarget() != ExecutionTarget.REMOTE
                || session.getRemoteCloseState() != RemoteCloseState.NOT_STARTED
                || !ProjectCodexIdentity.matches(session) || !ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())
                || session.getRemoteSessionId() == null || change.getStatus() != DevelopmentChangeStatus.OPEN
                || change.getWorkspaceState() != DevelopmentChangeWorkspaceState.READY || change.getProject() == null
                || change.getSourceState() == DevelopmentChangeSourceState.STALE
                || change.getSourceState() == DevelopmentChangeSourceState.BLOCKED
                || !Objects.equals(change.getProject().getId(), session.getProject().getId())
                || change.getProject().getId() != owner.databaseProjectId()
                || !Objects.equals(change.getChangeKey(), session.getPublishedChangeKey())
                || !Objects.equals(session.getPublishedSourceRevision(), command.publicationRevision())
                || !Objects.equals(change.getBaseCommit(), owner.baseCommit())
                || !Objects.equals(change.getBaseRef(), "refs/heads/main")
                || !Objects.equals(change.getWorkspaceBranch(), owner.workspaceBranch())
                || !Objects.equals(session.getWorkspaceBranch(), owner.workspaceBranch())
                || !Objects.equals(session.getPublishedHeadBranch(), owner.workspaceBranch())
                || !Objects.equals(change.getWorkspaceIdentity(), owner.workspaceIdentity())
                || !Objects.equals(session.getWorkspaceIdentity(), owner.workspaceIdentity())
                || !Objects.equals(change.getSelectedWorkerId(), owner.workerId())
                || !Objects.equals(session.getSelectedWorkerId(), owner.workerId())
                || !Objects.equals(session.getFinalCommitSha(), owner.sourceCommit())
                || !Objects.equals(session.getPublicationReceiptSha256(), command.publicationReceiptSha256())
                || !"jlnieto/atenea".equals(session.getPublishedRepository())
                || !"main".equals(session.getPublishedBaseBranch())
                || workspaceOperations.existsByDevelopmentChangeIdAndStateIn(change.getId(),
                    java.util.Set.of(DevelopmentChangeWorkspaceOperationState.REQUESTED, DevelopmentChangeWorkspaceOperationState.DISPATCHED))) {
            throw rejected("SOURCE_UPDATE_OWNER_MISMATCH");
        }
        return change;
    }

    private void invalidate(DevelopmentChangeEntity change, WorkSessionEntity session) {
        change.setValidationState(stale(change.getValidationState())); change.setReviewState(stale(change.getReviewState()));
        change.setIntegrationState(stale(change.getIntegrationState())); change.setReleaseState(stale(change.getReleaseState()));
        change.setUpdatedAt(Instant.now()); acceptance.invalidateForSourceUpdate(session);
    }
    private DevelopmentChangeProjectionState stale(DevelopmentChangeProjectionState state) {
        return state == DevelopmentChangeProjectionState.CURRENT ? DevelopmentChangeProjectionState.STALE : state;
    }
    private WorkSessionEntity session(Long id, boolean lock) {
        return (lock ? sessions.findLockedWithProjectAndDevelopmentChangeById(id) : sessions.findWithProjectById(id))
                .orElseThrow(() -> new WorkSessionNotFoundException(id));
    }
    private void administrator(Long id) {
        operators.findById(id).filter(op -> op.isActive() && op.getCodexOperationsRole() == CodexOperationsRole.PLATFORM_ADMINISTRATOR)
                .orElseThrow(() -> rejected("PLATFORM_ADMINISTRATOR_REQUIRED"));
    }
    private DeliveryRejectedException rejected(String code) { return new DeliveryRejectedException(code); }
    private String safeCode(String code) {
        return code != null && code.matches("SOURCE_UPDATE_[A-Z_]{1,60}") ? code : "SOURCE_UPDATE_RESPONSE_UNCERTAIN";
    }
    private boolean uncertain(String code) {
        return code.equals("SOURCE_UPDATE_RESPONSE_UNCERTAIN") || code.equals("SOURCE_UPDATE_PROTOCOL_FAILURE");
    }
    static String resolverPrompt(SourceUpdateOperation op) {
        return "Resuelve únicamente los conflictos de la PR de este mismo cambio y WorkSession. "
                + "Atenea ha preparado main fijado en " + op.command().targetMainCommit()
                + " sobre el HEAD publicado " + op.command().owner().sourceCommit()
                + ". Conserva la funcionalidad del ticket y ambas intenciones cuando sean compatibles. "
                + "No crees otro ticket ni otra WorkSession. No hagas commit, rebase, reset, push, integración ni despliegue. "
                + "Modifica sólo lo necesario para resolver estos conflictos y ejecuta tests focales. "
                + "Si hay una ambigüedad funcional real, detente y explícala en esta conversación. "
                + "Terminar no valida ni publica el cambio. Ficheros con conflictos:\n"
                + String.join("\n", op.preparation().conflictFiles().stream().map(file -> "- " + file).toList());
    }
}
