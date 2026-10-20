package com.atenea.delivery;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.developmentchange.DevelopmentChangeWorkspaceOperationKind;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.DevelopmentChangeWorkspaceCommand;
import com.atenea.remoteworker.DevelopmentChangeWorkspaceGateway;
import com.atenea.remoteworker.DevelopmentChangeWorkspaceObservation;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.remoteworker.RemoteWorkerException;
import com.atenea.service.worksession.DevelopmentChangeBranchPublicationService;
import com.atenea.service.worksession.WorkSessionCloseBlockedException;
import com.atenea.service.worksession.WorkSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable completion of an already delivered change; no prompt, merge or publication effects. */
@Service
public class MobileChangeCompletionService {
    private static final Logger log = LoggerFactory.getLogger(MobileChangeCompletionService.class);
    private final DeliveryStore store;
    private final WorkSessionRepository sessions;
    private final DevelopmentChangeRepository changes;
    private final OperatorRepository operators;
    private final DevelopmentChangeBranchPublicationService ownership;
    private final MobileDeliveryService delivery;
    private final DevelopmentChangeWorkspaceGateway workspaces;
    private final WorkSessionService close;
    private final RemoteWorkerProperties properties;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public MobileChangeCompletionService(DeliveryStore store, WorkSessionRepository sessions,
            DevelopmentChangeRepository changes, OperatorRepository operators,
            DevelopmentChangeBranchPublicationService ownership, MobileDeliveryService delivery,
            DevelopmentChangeWorkspaceGateway workspaces, WorkSessionService close, RemoteWorkerProperties properties,
            ObjectMapper mapper, PlatformTransactionManager manager) {
        this.store=store; this.sessions=sessions; this.changes=changes; this.operators=operators;
        this.ownership=ownership; this.delivery=delivery; this.workspaces=workspaces; this.close=close;
        this.properties=properties; this.mapper=mapper; this.transaction=new TransactionTemplate(manager);
    }

    public boolean isEnabled() {
        return delivery.isEnabled() && properties.isRemoteCloseReleaseEnabledFor(ProjectCodexIdentity.PROJECT_IDENTITY);
    }

    public DeliveryOperation.DeliveryView request(Long sessionId, AuthenticatedOperator actor, boolean reconcile) {
        enabled(); administrator(actor.operatorId());
        return Objects.requireNonNull(transaction.execute(ignored -> {
            WorkSessionEntity session = session(sessionId, true);
            var retained = store.completion(sessionId);
            if (retained.isPresent()) {
                DeliveryOperation op = store.get(retained.get().id(), true);
                ChangeCompletionEvidence.require(op, session, mapper);
                if ("SUCCEEDED".equals(op.state()) || !op.terminal() || !reconcile) return op.view();
                if (!"BLOCKED".equals(op.state()) || !List.of(WorkSessionStatus.CLOSING,WorkSessionStatus.CLOSED)
                        .contains(session.getStatus())) throw new DeliveryRejectedException("COMPLETION_STATE_MISMATCH");
                owned(session); store.lockCompletionAdmissionAndRequireIdle(sessionId);
                store.update(op, "APPLYING", null, op.planSha256(), op.evidence());
                return store.get(op.id(), false).view();
            }
            if (reconcile) throw new DeliveryRejectedException("COMPLETION_NOT_REQUESTED");
            if (session.getStatus() != WorkSessionStatus.OPEN) throw new DeliveryRejectedException("OPEN_WORKSESSION_REQUIRED");
            owned(session); store.lockCompletionAdmissionAndRequireIdle(sessionId);
            DeliveryOperation integration = store.integrated(sessionId, session.getFinalCommitSha())
                    .orElseThrow(() -> new DeliveryRejectedException("EXACT_CHANGE_NOT_INTEGRATED"));
            ChangeCompletionEvidence binding = ChangeCompletionEvidence.capture(session, integration);
            var deployed = requireDeployment(sessionId, actor, binding);
            UUID inspectionId = UUID.randomUUID();
            var workspace = requireWorkspace(binding, inspectionId);
            ObjectNode evidence = binding.initialEvidence(mapper);
            evidence.set("deploymentBefore", mapper.valueToTree(deployed.backend()));
            evidence.set("requiredDeliveryBefore", mapper.valueToTree(deployed));
            evidence.put("workspacePreflightId", inspectionId.toString());
            evidence.set("workspacePreflight", mapper.valueToTree(workspace));
            DeliveryOperation operation = store.create(sessionId, actor.operatorId(), "FINALIZE", DeliveryTarget.APP_PROD,
                    binding.publishedHeadCommit(), "QUEUED", evidence);
            session.setStatus(WorkSessionStatus.CLOSING);
            session.setUpdatedAt(Instant.now());
            sessions.saveAndFlush(session);
            return operation.view();
        }));
    }

    @Scheduled(scheduler="mobileDeliveryScheduler", fixedDelayString="${atenea.delivery.completion-delay-ms:3000}")
    public void reconcile() {
        if (!isEnabled()) return;
        for (UUID id : store.pendingCompletions()) {
            try { advance(id); }
            catch (Exception failure) {
                // An interrupted transport/transaction is ambiguous: retain the
                // same intent and remote close UUID, never manufacture success.
                log.warn("Change completion {} interrupted ({})", id, failure.getClass().getSimpleName());
            }
        }
    }

    void advance(UUID id) {
        DeliveryOperation operation;
        try { operation = transaction.execute(ignored -> {
            var original = store.get(id, false);
            WorkSessionEntity session = session(original.sessionId(), true);
            DeliveryOperation op = store.get(id, true);
            if (op.terminal()) return null;
            enabled(); administrator(op.operatorId()); owned(session);
            ChangeCompletionEvidence.require(op, session, mapper);
            store.lockCompletionAdmissionAndRequireIdle(session.getId());
            store.update(op, "APPLYING", null, op.planSha256(), op.evidence());
            return store.get(id, false);
        }); } catch (DeliveryRejectedException rejected) {
            block(id, rejected.code());
            return;
        }
        if (operation == null) return;
        try {
            AuthenticatedOperator actor = new AuthenticatedOperator(operation.operatorId(), null, null);
            WorkSessionEntity session = session(operation.sessionId(), false);
            var binding = ChangeCompletionEvidence.require(operation, session, mapper);
            var deployed = requireDeployment(session.getId(), actor, binding);
            var observed = requireWorkspace(binding, operation.executionId());
            transaction.executeWithoutResult(ignored -> {
                WorkSessionEntity locked = session(operation.sessionId(), true);
                DeliveryOperation op = store.get(id, true);
                ChangeCompletionEvidence.require(op, locked, mapper); owned(locked);
                store.lockCompletionAdmissionAndRequireIdle(locked.getId());
                ObjectNode evidence = op.evidence().deepCopy();
                evidence.set("workspaceInspection", mapper.valueToTree(observed));
                evidence.set("deploymentBeforeClose", mapper.valueToTree(deployed.backend()));
                evidence.set("requiredDeliveryBeforeClose", mapper.valueToTree(deployed));
                store.update(op, "APPLYING", null, op.planSha256(), evidence);
            });
            // No App transaction spans the cross-host effect. This service
            // reuses the supported close journal, including ambiguous retries.
            if (session.getStatus() != WorkSessionStatus.CLOSED) close.closeSession(operation.sessionId());
            var workspaceAfter = requireWorkspace(binding, operation.executionId());
            var after = requireDeployment(operation.sessionId(), actor, binding);
            transaction.executeWithoutResult(ignored -> complete(id, after, workspaceAfter));
        } catch (DeliveryRejectedException rejected) {
            var observation = rejected instanceof DeliveryObservationRejected observed ? observed.observation : null;
            if (observationBusy(rejected.code())) waitForDeploymentObservation(id, rejected.code(), observation);
            else block(id, rejected.code(), observation);
        } catch (WorkSessionCloseBlockedException blocked) {
            WorkSessionEntity retained = session(operation.sessionId(), false);
            if (retained.getRemoteCloseState() == RemoteCloseState.RECONCILING) return;
            block(id, blocked.getState());
        } catch (RemoteWorkerException rejected) {
            if (!rejected.isCompatibleTransportFailure()) block(id, rejected.isCompatibleDeterministicFailure()
                    ? rejected.getFailureCode() : "REMOTE_WORKER_PROTOCOL_FAILURE");
        }
    }

    private void complete(UUID id, ChangeDeliveryReadiness deployed,
            DevelopmentChangeWorkspaceObservation workspaceAfter) {
        DeliveryOperation original = store.get(id, false);
        WorkSessionEntity session = session(original.sessionId(), true);
        DeliveryOperation op = store.get(id, true);
        if ("SUCCEEDED".equals(op.state())) return;
        administrator(op.operatorId()); owned(session);
        var binding = ChangeCompletionEvidence.require(op, session, mapper);
        store.lockCompletionAdmissionAndRequireIdle(session.getId());
        if (session.getStatus() != WorkSessionStatus.CLOSED || session.getRemoteCloseState() != RemoteCloseState.RELEASED
                || session.getRemoteCloseOperationId() == null || session.getRemoteCloseRevision() < 6
                || session.getClosedAt() == null || session.getRemoteCloseReleasedAt() == null
                || session.getRemoteCloseReceiptSha256() == null || !session.getRemoteCloseReceiptSha256().matches("[0-9a-f]{64}")) {
            throw new DeliveryRejectedException("COMPLETION_RELEASE_RECEIPT_REQUIRED");
        }
        requireDeploymentProof(deployed, session.getId(), binding);
        var change = changes.findByChangeKeyForUpdate(binding.changeKey())
                .orElseThrow(() -> new DeliveryRejectedException("COMPLETION_EVIDENCE_MISMATCH"));
        if (change.getStatus() != DevelopmentChangeStatus.OPEN || !Objects.equals(change.getId(), session.getDevelopmentChange().getId())) {
            throw new DeliveryRejectedException("COMPLETION_STATE_MISMATCH");
        }
        change.setStatus(DevelopmentChangeStatus.COMPLETED);
        change.setIntegrationState(DevelopmentChangeProjectionState.CURRENT);
        change.setReleaseState(DevelopmentChangeProjectionState.CURRENT);
        change.setUpdatedAt(Instant.now());
        changes.saveAndFlush(change);
        ObjectNode evidence = op.evidence().deepCopy();
        evidence.set("deploymentAfter", mapper.valueToTree(deployed.backend()));
        evidence.set("requiredDeliveryAfter", mapper.valueToTree(deployed));
        evidence.set("workspaceInspectionAfter", mapper.valueToTree(workspaceAfter));
        evidence.put("remoteCloseOperationId", session.getRemoteCloseOperationId().toString());
        evidence.put("remoteCloseReceiptSha256", session.getRemoteCloseReceiptSha256());
        evidence.put("completedAt", Instant.now().toString());
        evidence.put("pullRequestUrl", binding.pullRequestUrl());
        evidence.put("mergeCommit", binding.integratedMergeCommit());
        store.update(op, "SUCCEEDED", null, op.planSha256(), evidence);
    }

    private void block(UUID id, String code) {
        block(id, code, null);
    }
    private void block(UUID id, String code, ChangeDeliveryReadiness observation) {
        transaction.executeWithoutResult(ignored -> {
            DeliveryOperation op = store.get(id, true);
            if (op.terminal()) return;
            ObjectNode evidence = op.evidence().deepCopy();
            var attempts = evidence.withArray("blockedAttempts");
            attempts.addObject().put("code", code).put("at", Instant.now().toString());
            if (observation != null) evidence.set("requiredDeliveryObservation", mapper.valueToTree(observation));
            store.update(op, "BLOCKED", code, op.planSha256(), evidence);
        });
    }
    private void waitForDeploymentObservation(UUID id, String code, ChangeDeliveryReadiness observation) {
        transaction.executeWithoutResult(ignored -> {
            DeliveryOperation op = store.get(id, true);
            if (op.terminal()) return;
            ObjectNode evidence = op.evidence().deepCopy();
            ObjectNode wait = evidence.withObject("deploymentObservationWait");
            String now = Instant.now().toString();
            if (!wait.has("firstObservedAt")) wait.put("firstObservedAt", now);
            wait.put("lastObservedAt", now).put("code", code)
                    .put("attempts", wait.path("attempts").asLong(0) + 1);
            if (observation != null) evidence.set("requiredDeliveryObservation", mapper.valueToTree(observation));
            // Busy is not healthy evidence, nor a terminal refusal. The existing
            // scheduler rechecks fresh evidence with this exact intent/close UUID.
            store.update(op, "APPLYING", code, op.planSha256(), evidence);
        });
    }
    private DevelopmentChangeWorkspaceObservation requireWorkspace(ChangeCompletionEvidence binding, UUID inspectionId) {
        // Existing OBSERVE_ONLY executes a fresh inspection even with the
        // same request identity; it does not create or release a workspace.
        var observed = workspaces.execute(new DevelopmentChangeWorkspaceCommand(inspectionId, inspectionId,
                DevelopmentChangeWorkspaceOperationKind.INSPECT, null, binding.changeKey(), binding.projectId(),
                ProjectCodexIdentity.PROJECT_IDENTITY, ProjectCodexIdentity.REPOSITORY, ProjectCodexIdentity.BRANCH,
                binding.baseCommit(), binding.canonicalCommit(), binding.workspaceBranch(), binding.workspaceIdentity(),
                binding.workerId(), binding.sourceRevision(), binding.sourceFingerprintSha256()));
        if (observed == null || observed.disposition() != DevelopmentChangeWorkspaceObservation.Disposition.OWNED
                || observed.workspaceDirty() || observed.retainedDraft() || !binding.publishedHeadCommit().equals(observed.sourceCommit())
                || observed.requestFingerprintSha256() == null || !observed.requestFingerprintSha256().matches("[0-9a-f]{64}")) {
            throw new DeliveryRejectedException("COMPLETION_WORKSPACE_NOT_CLEAN_OR_EXACT");
        }
        return observed;
    }
    private ChangeDeliveryReadiness requireDeployment(Long id, AuthenticatedOperator actor, ChangeCompletionEvidence binding) {
        var result = delivery.observeDelivery(id, actor);
        requireDeploymentProof(result, id, binding);
        return result;
    }
    private void requireDeploymentProof(ChangeDeliveryReadiness result, Long id, ChangeCompletionEvidence binding) {
        // Keep the symbolic cause instead of turning a concurrent read into a
        // definitive unhealthy deployment. Only BUSY is retried automatically.
        if (result != null && observationBusy(result.errorCode()) && !exactBusyObservation(result, id, binding))
            throw new DeliveryObservationRejected("COMPLETION_DELIVERY_EVIDENCE_MISMATCH", result);
        if (result != null && id.equals(result.sessionId()) && ("UNAVAILABLE".equals(result.requirementsStatus())
                    || result.backend()!=null && "UNAVAILABLE".equals(result.backend().status())
                    || result.requiredTargets()!=null && result.requiredTargets().contains(DeliveryTarget.ANDROID_STABLE)
                        && "UNAVAILABLE".equals(result.androidStatus()))
                && result.errorCode() != null
                && result.errorCode().matches("[A-Z][A-Z0-9_]{2,79}")) {
            throw new DeliveryObservationRejected(result.errorCode(), result);
        }
        if (result == null || !ChangeDeliveryReadiness.verifiedForClose(mapper.valueToTree(result),id,
                binding.publishedHeadCommit(),binding.integratedMergeCommit())) {
            throw new DeliveryObservationRejected(result != null && result.requiredTargets()!=null
                    && result.requiredTargets().contains(DeliveryTarget.ANDROID_STABLE)
                    && !"INCLUDED".equals(result.androidStatus()) ? "ANDROID_DELIVERY_REQUIRED" : "HEALTHY_DELIVERED_CHANGE_REQUIRED", result);
        }
    }
    private static boolean observationBusy(String code) {
        return "APP_OBSERVATION_BUSY".equals(code) || "ANDROID_OBSERVATION_BUSY".equals(code);
    }
    private static boolean exactBusyObservation(ChangeDeliveryReadiness result, Long id, ChangeCompletionEvidence binding) {
        // Busy permits another read, never foreign/stale evidence or a close.
        long now=Instant.now().getEpochSecond();
        if (!id.equals(result.sessionId()) || !binding.publishedHeadCommit().equals(result.headCommit())
                || !binding.integratedMergeCommit().equals(result.integratedMergeCommit()) || result.ready()
                || !("CONFIRMED".equals(result.requirementsStatus()) || "UNAVAILABLE".equals(result.requirementsStatus()))
                || result.observedAt()<now-60 || result.observedAt()>now+5 || result.requiredTargets()==null
                || result.requiredTargets().isEmpty() || result.requiredTargets().size()>2
                || result.requiredTargets().stream().distinct().count()!=result.requiredTargets().size()
                || result.requiredTargets().stream().anyMatch(t->t!=DeliveryTarget.APP_PROD && t!=DeliveryTarget.ANDROID_STABLE)) return false;
        if ("ANDROID_OBSERVATION_BUSY".equals(result.errorCode()))
            return result.requiredTargets().contains(DeliveryTarget.ANDROID_STABLE) && result.android()==null
                    && "UNAVAILABLE".equals(result.androidStatus());
        return result.requiredTargets().contains(DeliveryTarget.APP_PROD) && result.backend()!=null
                && id.equals(result.backend().sessionId()) && "UNAVAILABLE".equals(result.backend().status())
                && "APP_OBSERVATION_BUSY".equals(result.backend().errorCode());
    }
    /** Fixed typed facts only; no raw transport response, command or credential. */
    private static final class DeliveryObservationRejected extends DeliveryRejectedException {
        private final ChangeDeliveryReadiness observation;
        private DeliveryObservationRejected(String code, ChangeDeliveryReadiness observation) {
            super(code); this.observation=observation;
        }
    }
    private void owned(WorkSessionEntity session) {
        try { ownership.requireExactDeliveredOwner(session); }
        catch (com.atenea.service.worksession.WorkSessionPublishConflictException invalid) {
            throw new DeliveryRejectedException("VALIDATED_OWNERSHIP_REQUIRED");
        }
    }
    private WorkSessionEntity session(Long id, boolean lock) {
        return (lock ? sessions.findLockedWithProjectAndDevelopmentChangeById(id)
                : sessions.findWithProjectAndDevelopmentChangeById(id))
                .orElseThrow(() -> new DeliveryRejectedException("SESSION_NOT_FOUND"));
    }
    private void administrator(Long id) {
        operators.findById(id).filter(operator -> operator.isActive()
                && operator.getCodexOperationsRole() == CodexOperationsRole.PLATFORM_ADMINISTRATOR)
                .orElseThrow(() -> new DeliveryRejectedException("PLATFORM_ADMINISTRATOR_REQUIRED"));
    }
    private void enabled() { if (!isEnabled()) throw new DeliveryRejectedException("CHANGE_COMPLETION_DISABLED"); }
}
