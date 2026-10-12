package com.atenea.delivery;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.persistence.developmentchange.DevelopmentChangeRepository;
import com.atenea.persistence.developmentchange.DevelopmentChangeStatus;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerException;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.service.worksession.WorkSessionCloseBlockedException;
import com.atenea.service.worksession.WorkSessionService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Abandonment is not successful delivery. The worker retains worktree and history. */
@Service
public class WorkSessionRetirementService {
    private final WorkSessionRetirementStore store;
    private final WorkSessionRepository sessions;
    private final DevelopmentChangeRepository changes;
    private final WorkSessionService close;
    private final RemoteWorkerProperties properties;
    private final TransactionTemplate transaction;
    public WorkSessionRetirementService(WorkSessionRetirementStore store, WorkSessionRepository sessions,
            DevelopmentChangeRepository changes, WorkSessionService close, RemoteWorkerProperties properties,
            PlatformTransactionManager manager) {
        this.store=store; this.sessions=sessions; this.changes=changes; this.close=close;
        this.properties=properties; transaction=new TransactionTemplate(manager);
    }
    public record View(UUID operationId, Long sessionId, UUID idempotencyKey, String state, String errorCode,
            boolean sourceAndHistoryRetained) {}
    private View view(WorkSessionRetirementStore.Operation op) {
        return new View(op.operationId(),op.sessionId(),op.idempotencyKey(),op.state(),op.errorCode(),"SUCCEEDED".equals(op.state()));
    }
    public View observe(Long sessionId, AuthenticatedOperator actor) {
        store.administrator(actor.operatorId());
        return store.find(sessionId).map(this::view).orElse(null);
    }
    public View request(Long sessionId, AuthenticatedOperator actor, UUID key, boolean reconcile) {
        enabled(); store.administrator(actor.operatorId());
        if (key==null) throw new DeliveryRejectedException("RETIREMENT_IDEMPOTENCY_KEY_REQUIRED");
        return Objects.requireNonNull(transaction.execute(ignored -> {
            WorkSessionEntity session=session(sessionId,true);
            var existing=store.find(sessionId);
            if (existing.isPresent()) {
                var op=existing.get();
                if (!op.operatorId().equals(actor.operatorId()) || !op.idempotencyKey().equals(key))
                    throw new DeliveryRejectedException("RETIREMENT_IDEMPOTENCY_MISMATCH");
                if ("SUCCEEDED".equals(op.state())) return view(op);
                store.requireBound(op,session);
                if (reconcile && "BLOCKED".equals(op.state())) {
                    store.requireIdle(sessionId); store.update(op,"APPLYING",null,op.evidence());
                }
                return view(store.find(sessionId).orElseThrow());
            }
            if (reconcile) throw new DeliveryRejectedException("RETIREMENT_NOT_REQUESTED");
            if (!List.of(WorkSessionStatus.OPEN,WorkSessionStatus.CLOSING)
                    .contains(session.getStatus()) || session.getRemoteCloseState()!=RemoteCloseState.NOT_STARTED
                    || session.getRemoteCloseOperationId()!=null)
                throw new DeliveryRejectedException("RETIREMENT_SESSION_STATE_MISMATCH");
            var owner=store.owner(session);
            if (session.getDevelopmentChange()!=null && !List.of(DevelopmentChangeStatus.OPEN,DevelopmentChangeStatus.PAUSED)
                    .contains(session.getDevelopmentChange().getStatus()))
                throw new DeliveryRejectedException("RETIREMENT_CHANGE_STATE_MISMATCH");
            store.requireIdle(sessionId);
            var op=store.create(sessionId,actor.operatorId(),key,owner);
            session.setStatus(WorkSessionStatus.CLOSING); session.setUpdatedAt(Instant.now());
            sessions.saveAndFlush(session);
            return view(op);
        }));
    }
    @Scheduled(scheduler="mobileDeliveryScheduler",fixedDelayString="${atenea.delivery.retirement-delay-ms:3000}")
    public void reconcile() {
        if (!isEnabled()) return;
        for (Long id:store.pending()) {
            try { advance(id); }
            catch (Exception ambiguous) {
                LoggerFactory.getLogger(getClass()).warn("Retirement of session {} interrupted ({})",id,ambiguous.getClass().getSimpleName());
            }
        }
    }
    void advance(Long id) {
        try {
            var op=transaction.execute(ignored -> {
                var session=session(id,true); var intent=store.find(id).orElseThrow();
                if (!"APPLYING".equals(intent.state())) return null;
                enabled(); store.requireBound(intent,session); store.requireIdle(id); return intent;
            });
            if (op==null) return;
            // Existing journal retries the same close UUID after ambiguous transport.
            close.closeSession(id);
            transaction.executeWithoutResult(ignored -> {
                var session=session(id,true); var intent=store.find(id).orElseThrow();
                store.requireBound(intent,session); store.requireIdle(id);
                if (session.getStatus()!=WorkSessionStatus.CLOSED || session.getRemoteCloseState()!=RemoteCloseState.RELEASED
                        || !intent.operationId().equals(session.getRemoteCloseOperationId())
                        || session.getRemoteCloseRevision()<6 || session.getRemoteCloseReceiptSha256()==null
                        || !session.getRemoteCloseReceiptSha256().matches("[0-9a-f]{64}")
                        || session.getClosedAt()==null || session.getRemoteCloseReleasedAt()==null)
                    throw new DeliveryRejectedException("RETIREMENT_RELEASE_RECEIPT_REQUIRED");
                if (session.getDevelopmentChange()!=null) {
                    var change=changes.findByChangeKeyForUpdate(session.getDevelopmentChange().getChangeKey()).orElseThrow();
                    change.setStatus(DevelopmentChangeStatus.ABANDONED); change.setUpdatedAt(Instant.now());
                    changes.saveAndFlush(change);
                }
                ObjectNode evidence=intent.evidence().deepCopy();
                evidence.put("disposition","ABANDONED_NOT_DELIVERED"); evidence.put("sourceAndHistoryRetained",true);
                evidence.put("remoteCloseOperationId",session.getRemoteCloseOperationId().toString());
                evidence.put("receiptSha256",session.getRemoteCloseReceiptSha256());
                evidence.put("finishedAt",Instant.now().toString());
                store.update(intent,"SUCCEEDED",null,evidence);
            });
        } catch (DeliveryRejectedException rejected) { block(id,rejected.code()); }
        catch (WorkSessionCloseBlockedException rejected) {
            var s=session(id,false);
            if (s.getRemoteCloseState()!=RemoteCloseState.RECONCILING) block(id,rejected.getState());
        } catch (RemoteWorkerException failure) {
            if (!failure.isCompatibleTransportFailure()) block(id,failure.isCompatibleDeterministicFailure()
                ? failure.getFailureCode():"REMOTE_WORKER_PROTOCOL_FAILURE");
        }
    }
    private void block(Long id,String code) {
        transaction.executeWithoutResult(ignored -> {
            session(id,true); var op=store.find(id).orElseThrow();
            if (!"APPLYING".equals(op.state())) return;
            ObjectNode evidence=op.evidence().deepCopy();
            evidence.withArray("blockedAttempts").addObject().put("code",code).put("at",Instant.now().toString());
            store.update(op,"BLOCKED",code,evidence);
        });
    }
    private WorkSessionEntity session(Long id,boolean lock) {
        return (lock?sessions.findLockedWithProjectAndDevelopmentChangeById(id):sessions.findWithProjectAndDevelopmentChangeById(id))
            .orElseThrow(() -> new DeliveryRejectedException("SESSION_NOT_FOUND"));
    }
    private boolean isEnabled() { return properties.isRemoteCloseReleaseEnabledFor(ProjectCodexIdentity.PROJECT_IDENTITY); }
    private void enabled() { if (!isEnabled()) throw new DeliveryRejectedException("SESSION_RETIREMENT_DISABLED"); }
}
