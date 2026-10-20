package com.atenea.service.worksession;

import com.atenea.api.worksession.ChangeValidationWorkflowResponse;
import com.atenea.api.worksession.DevelopmentChangeValidationResponse;
import com.atenea.delivery.DeliveryRejectedException;
import com.atenea.delivery.SourceUpdateBaseGuard;
import com.atenea.persistence.developmentchange.DevelopmentChangeProjectionState;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerException;
import com.atenea.service.worksession.ChangeValidationWorkflowStore.Workflow;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One explicit intent owns all four checks. A committed operation UUID is an
 * outbox: dispatch/transport recovery always uses it, never a new test identity.
 * Each scheduler invocation owns one short, admission/session/workflow-ordered
 * transaction. No mobile request is needed to continue or finish the workflow.
 */
@Service
public class ChangeValidationCoordinator {
    private final ChangeValidationWorkflowStore store;
    private final WorkSessionRepository sessions;
    private final ValidationOperationRepository operations;
    private final ClosedValidationOperationService validation;
    private final RemoteWorkerClient worker;
    private final SourceUpdateBaseGuard base;
    private final JdbcTemplate jdbc;

    public ChangeValidationCoordinator(ChangeValidationWorkflowStore store, WorkSessionRepository sessions,
            ValidationOperationRepository operations, ClosedValidationOperationService validation,
            RemoteWorkerClient worker, SourceUpdateBaseGuard base, JdbcTemplate jdbc) {
        this.store=store;this.sessions=sessions;this.operations=operations;this.validation=validation;
        this.worker=worker;this.base=base;this.jdbc=jdbc;
    }

    @Transactional(timeout=30)
    public ChangeValidationWorkflowResponse request(Long sessionId, UUID requestId) {
        return requestLocked(sessionId,requestId).response();
    }

    /** Old clients may still POST while polling. A deterministic key makes
     * those requests observations of the same intent, never automatic retries. */
    @Transactional(timeout=30)
    public DevelopmentChangeValidationResponse legacyRequest(Long sessionId) {
        Workflow workflow=requestLocked(sessionId,null);
        var evidence=validation.getDevelopmentChangeEvidence(sessionId);
        String current=evidence.operations().stream().filter(o->o.status()==ValidationOperationStatus.RUNNING)
                .map(o->o.operation().name()).findFirst().orElse(null);
        return new DevelopmentChangeValidationResponse(evidence.changeKey(),evidence.sourceRevision(),
                evidence.sourceFingerprintSha256(),evidence.validationState(),workflow.state(),current,
                evidence.passedOperations(),4,workflow.terminal()?"Validación finalizada. Consulta los resultados.":
                "El servidor está validando esta revisión. Puedes salir de Atenea.");
    }

    private Workflow requestLocked(Long sessionId, UUID requestedId) {
        WorkSessionEntity session=lockedSession(sessionId);
        if (requestedId==null) {
            var legacy=store.latestSession(sessionId);
            if (legacy.isPresent()) {
                if (!bound(legacy.get(),session)) throw blocked("VALIDATION_CLIENT_UPDATE_REQUIRED");
                return legacy.get();
            }
        }
        // A replay is bound to its original session/revision, even after failure.
        if (requestedId!=null) {
            var retained=store.find(requestedId);
            if (retained.isPresent()) {
                if (!Objects.equals(retained.get().sessionId(),sessionId)) throw blocked("VALIDATION_REQUEST_OWNER_MISMATCH");
                return retained.get();
            }
        }
        validation.requireExactDevelopmentChange(session);
        var c=session.getDevelopmentChange();
        if (c.getSourceFingerprintSha256()==null) throw blocked("VALIDATION_SOURCE_NOT_OBSERVED");
        UUID id=requestedId==null?UUID.nameUUIDFromBytes(("server-validation-v1\0"+sessionId+"\0"+
                c.getSourceRevision()+"\0"+c.getSourceFingerprintSha256()).getBytes(StandardCharsets.UTF_8)):requestedId;
        var replay=store.find(id);
        if (replay.isPresent()) return replay.get();
        var active=store.active(sessionId);
        if (active.isPresent()) {
            if (!bound(active.get(),session)) throw blocked("VALIDATION_REVISION_IN_PROGRESS");
            throw blocked("VALIDATION_ALREADY_RUNNING");
        }
        var source=confirmSource(session);
        base.requireCurrent(sessionId);
        if (operations.findByWorkSessionIdAndStatus(sessionId,ValidationOperationStatus.RUNNING).stream().anyMatch(o->
                !c.getSourceFingerprintSha256().equals(o.getSourceTreeFingerprintSha256())
                || !o.getOperation().definitionRevision().equals(o.getDefinitionRevision())))
            throw blocked("VALIDATION_PREVIOUS_CHECK_IN_PROGRESS");
        var latest=validation.latestByOperation(results(session));
        UUID failed=latest.values().stream().filter(o->o.getStatus()==ValidationOperationStatus.FAILED
                ||o.getStatus()==ValidationOperationStatus.BLOCKED).map(ValidationOperationEntity::getId).findFirst().orElse(null);
        validation.beginManagedProjection(session,source);
        return store.create(id,session,failed);
    }

    @Transactional(timeout=30)
    public void progress(UUID id) {
        Workflow observed=store.find(id).orElseThrow(()->blocked("VALIDATION_WORKFLOW_NOT_FOUND"));
        WorkSessionEntity session=lockedSession(observed.sessionId());
        Workflow w=store.locked(id);
        if (w.terminal()) return;
        if (!bound(w,session)) { store.finish(w,"STALE","VALIDATION_SOURCE_OR_OWNER_CHANGED");return; }
        try {
            validation.requireExactDevelopmentChange(session);
        } catch (WorkSessionOperationBlockedException stopped) {
            store.finish(w,"BLOCKED","VALIDATION_SESSION_NOT_IDLE");return;
        }
        if (w.activeOperationId()==null) {
            prepare(w,session);
        } else {
            monitorOrDispatch(w,session);
        }
    }

    private void prepare(Workflow w,WorkSessionEntity session) {
        // Observation/base gates precede every admission and final promotion.
        if (!currentSourceAndBase(w,session)) return;
        var latest=validation.latestByOperation(results(session));
        var running=latest.values().stream().filter(o->o.getStatus()==ValidationOperationStatus.RUNNING).findFirst();
        if (running.isPresent()) {
            // Explicit request can resume a historical in-flight check, but
            // migration/scheduler alone never adopts a legacy partial sequence.
            store.progress(w,"DISPATCHING",running.get().getId(),w.retryConsumed(),null);return;
        }
        var failed=latest.values().stream().filter(o->o.getStatus()==ValidationOperationStatus.FAILED
                ||o.getStatus()==ValidationOperationStatus.BLOCKED).findFirst();
        if (failed.isPresent() && (w.retryConsumed() || !failed.get().getId().equals(w.retryOfOperationId()))) {
            failCheck(w,session,failed.get());return;
        }
        ValidationOperationKind next=failed.map(ValidationOperationEntity::getOperation).orElseGet(()->{
            for (var kind:ValidationOperationKind.values()) if (!latest.containsKey(kind)) return kind;
            return null;
        });
        if (next==null) {
            validation.projectAcceptance(session.getId(),w.fingerprint());
            session.getDevelopmentChange().setValidationState(DevelopmentChangeProjectionState.CURRENT);
            store.finish(w,"SUCCEEDED",null);return;
        }
        var candidate=validation.newValidationEntity(session,next,w.fingerprint(),failed.map(ValidationOperationEntity::getId).orElse(null));
        var operation=operations.findByIdentitySha256(candidate.getIdentitySha256()).orElse(candidate);
        if (operation==candidate) operations.saveAndFlush(operation);
        // This transaction commits BEFORE any startValidation call. A crash
        // before/after remote admission cannot lose or regenerate this UUID.
        store.progress(w,"DISPATCHING",operation.getId(),w.retryConsumed()||failed.isPresent(),null);
    }

    private void monitorOrDispatch(Workflow w,WorkSessionEntity session) {
        var operation=operations.findById(w.activeOperationId()).orElseThrow(()->blocked("VALIDATION_OPERATION_MISSING"));
        if (!Objects.equals(operation.getWorkSession().getId(),session.getId())
                ||!w.fingerprint().equals(operation.getSourceTreeFingerprintSha256())
                ||!operation.getOperation().definitionRevision().equals(operation.getDefinitionRevision())) {
            store.finish(w,"BLOCKED","VALIDATION_OPERATION_OWNER_MISMATCH");return;
        }
        if (operation.getStatus()==ValidationOperationStatus.RUNNING) {
            try {
                RemoteWorkerClient.DurableValidationResult result;
                try {
                    result=worker.inspectValidation(session,operation.getId().toString());
                } catch (RemoteWorkerException absent) {
                    // Only an authoritative NOT_FOUND permits dispatch. An
                    // unavailable/ambiguous inspection never admits another test.
                    if (absent.getStatusCode()!=404 || !"VALIDATION_NOT_FOUND".equals(absent.getFailureCode())
                            || absent.getCategory()!=com.atenea.remoteworker.RemoteWorkerFailureCategory.VALIDATION
                            || absent.isRetryable() || absent.getNextAction()!=AgentRunRecoveryNextAction.NONE
                            || !"DISPATCHING".equals(w.phase())) throw absent;
                    // Source/base authority was checked when this UUID was
                    // durably admitted. Finish/replay that same admission even
                    // if main subsequently moves; gate the NEXT check afresh.
                    // The worker still verifies the exact owned tree on start.
                    result=worker.startValidation(session,operation.getOperation(),w.fingerprint(),operation.getId().toString());
                }
                if (result==null || !w.canonicalCommit().equals(result.sourceRevision()))
                    throw new RemoteWorkerException("Validation source commit is conflicting",409);
                validation.applyDurableResult(operation,session,result);
            } catch (RemoteWorkerException unavailable) {
                if (unavailable.getStatusCode()==0 || unavailable.getStatusCode()>=500) {
                    store.progress(w,w.phase(),w.activeOperationId(),w.retryConsumed(),"VALIDATION_WORKER_UNCONFIRMED");
                } else {
                    store.finish(w,"BLOCKED","VALIDATION_WORKER_EVIDENCE_REJECTED");
                }
                return;
            }
        }
        if (operation.getStatus()==ValidationOperationStatus.SUCCEEDED) {
            store.progress(w,"QUEUED",null,w.retryConsumed(),null);
        } else if (operation.getStatus()==ValidationOperationStatus.RUNNING) {
            store.progress(w,"MONITORING",operation.getId(),w.retryConsumed(),null);
        } else {
            failCheck(w,session,operation);
        }
    }

    private void failCheck(Workflow w,WorkSessionEntity session,ValidationOperationEntity operation) {
        validation.projectAcceptance(session.getId(),w.fingerprint());
        session.getDevelopmentChange().setValidationState(DevelopmentChangeProjectionState.BLOCKED);
        store.finish(w,operation.getStatus()==ValidationOperationStatus.FAILED?"FAILED":"BLOCKED","VALIDATION_CHECK_FAILED");
    }
    private boolean currentSourceAndBase(Workflow w,WorkSessionEntity session) {
        try {
            confirmSource(session);
            base.requireCurrent(session.getId());
            return true;
        } catch (RemoteWorkerException unavailable) {
            if (unavailable.getStatusCode()>=400 && unavailable.getStatusCode()<500)
                store.finish(w,"BLOCKED","VALIDATION_SOURCE_AUTHORITY_REJECTED");
            else store.progress(w,w.phase(),w.activeOperationId(),w.retryConsumed(),"VALIDATION_SOURCE_UNCONFIRMED");
        } catch (WorkSessionOperationBlockedException moved) {
            store.finish(w,"STALE","VALIDATION_SOURCE_CHANGED");
        } catch (DeliveryRejectedException moved) {
            store.finish(w,"BLOCKED",moved.code());
        }
        return false;
    }
    private RemoteWorkerClient.SourceTreeFingerprint confirmSource(WorkSessionEntity session) {
        var source=worker.fingerprintSourceTree(session);
        validation.validateSourceObservation(session,source);
        if (!source.fingerprintSha256().equals(session.getDevelopmentChange().getSourceFingerprintSha256()))
            throw blocked("VALIDATION_SOURCE_CHANGED");
        return source;
    }
    private java.util.List<ValidationOperationEntity> results(WorkSessionEntity s) {
        return operations.findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(
                s.getId(),s.getDevelopmentChange().getSourceFingerprintSha256());
    }
    private WorkSessionEntity lockedSession(Long id) {
        jdbc.execute("SELECT pg_advisory_xact_lock(814205002)");
        return sessions.findLockedWithProjectAndDevelopmentChangeById(id).orElseThrow(()->new WorkSessionNotFoundException(id));
    }
    private boolean bound(Workflow w,WorkSessionEntity s) {
        var c=s.getDevelopmentChange();
        return c!=null && s.getProject()!=null && c.getProject()!=null
                && Objects.equals(w.projectId(),s.getProject().getId()) && Objects.equals(w.projectId(),c.getProject().getId())
                && "atenea-required-validation-v2".equals(w.profileRevision())
                && Objects.equals(w.changeKey(),c.getChangeKey()) && w.revision()==c.getSourceRevision()
                && Objects.equals(w.fingerprint(),c.getSourceFingerprintSha256())
                && Objects.equals(w.remoteSessionId(),s.getRemoteSessionId()) && Objects.equals(w.workerId(),s.getSelectedWorkerId())
                && Objects.equals(w.workspaceIdentity(),s.getWorkspaceIdentity()) && Objects.equals(w.workspaceBranch(),s.getWorkspaceBranch())
                && Objects.equals(w.canonicalCommit(),s.getCanonicalSourceCommit());
    }
    private WorkSessionOperationBlockedException blocked(String code) { return new WorkSessionOperationBlockedException(code); }
}
