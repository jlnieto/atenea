package com.atenea.service.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.DeliveryRejectedException;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.developmentchange.*;
import com.atenea.persistence.worksession.*;
import com.atenea.remoteworker.*;
import com.atenea.remoteworker.ValidationRecoveryCommand.Action;
import com.atenea.service.worksession.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, explicit recovery. Reading help or polling never creates a run or retries tests. */
@Service
public class ValidationRecoveryService {
    private final ValidationRecoveryStore store;
    private final WorkSessionRepository sessions;
    private final DevelopmentChangeRepository changes;
    private final ValidationOperationRepository validations;
    private final AgentRunRepository runs;
    private final OperatorRepository operators;
    private final ValidationRecoveryGateway gateway;
    private final RemoteWorkerClient worker;
    private final WorkSessionAcceptanceService acceptance;
    private final AgentRunService agentRuns;
    private final SessionTurnRepository turns;
    private final RemoteAgentRunCoordinator coordinator;
    private final TransactionTemplate tx;
    public ValidationRecoveryService(ValidationRecoveryStore store,WorkSessionRepository sessions,
            DevelopmentChangeRepository changes,ValidationOperationRepository validations,AgentRunRepository runs,
            OperatorRepository operators,ValidationRecoveryGateway gateway,RemoteWorkerClient worker,
            WorkSessionAcceptanceService acceptance,AgentRunService agentRuns,SessionTurnRepository turns,
            RemoteAgentRunCoordinator coordinator,PlatformTransactionManager manager) {
        this.store=store;this.sessions=sessions;this.changes=changes;this.validations=validations;this.runs=runs;
        this.operators=operators;this.gateway=gateway;this.worker=worker;this.acceptance=acceptance;
        this.agentRuns=agentRuns;this.turns=turns;this.coordinator=coordinator;this.tx=new TransactionTemplate(manager);
    }
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public Overview observe(Long sessionId,AuthenticatedOperator actor) {
        administrator(actor);
        var session=session(sessionId,false); var change=owner(session);
        var failed=failure(session,change);
        var entries=store.list(sessionId);
        var repair=entries.stream().filter(e->e.operatorId().equals(actor.operatorId())&&e.kind().equals("REPAIR")
                && (failed!=null ? e.validationId().equals(failed.getId())
                    : e.state().equals("APPLIED") && Objects.equals(e.resultFingerprint(),change.getSourceFingerprintSha256())))
                .findFirst().orElse(null);
        var investigation=entries.stream().filter(e->e.operatorId().equals(actor.operatorId())&&e.kind().equals("INVESTIGATION")
                && failed!=null && e.validationId().equals(failed.getId()))
                .findFirst().orElse(null);
        boolean available=false; String code=failed==null?null:failureCode(failed.getSummary());
        if (failed!=null && knownFailure(failed) && repair==null && store.idle()) {
            try { available="AVAILABLE".equals(gateway.call(command(session,change,failed,UUID.randomUUID()),Action.DIAGNOSE).state()); }
            catch (RemoteWorkerException unavailable) { /* Unavailability is not permission to repair. */ }
        }
        return new Overview(sessionId,change.getChangeKey(),change.getSourceRevision(),code,
                available,failed!=null && !runs.existsBySessionIdAndStatusIn(sessionId,AgentRunStatus.nonTerminalStatuses()),
                repair==null?null:repair.view(),investigation==null?null:investigation.view());
    }
    public ValidationRecoveryStore.View prepare(Long sessionId,AuthenticatedOperator actor) {
        administrator(actor);
        return tx.execute(ignored->{
            store.admissionLock(); var session=session(sessionId,true); var change=owner(session);
            var failed=requireFailure(session,change); requireIdle(session);
            var existing=store.list(sessionId).stream().filter(e->e.kind().equals("REPAIR")&&e.validationId().equals(failed.getId())).findFirst();
            if (existing.isPresent()) { requireActor(existing.get(),actor); return existing.get().view(); }
            if (!knownFailure(failed)) throw rejected("VALIDATION_REPAIR_NOT_SUPPORTED");
            UUID id=UUID.randomUUID(); var command=command(session,change,failed,id);
            if (!"AVAILABLE".equals(gateway.call(command,Action.DIAGNOSE).state())) throw rejected("VALIDATION_REPAIR_NOT_SUPPORTED");
            return store.create(id,sessionId,actor.operatorId(),failed.getId(),"REPAIR",change.getSourceRevision(),
                    change.getSourceFingerprintSha256(),command).view();
        });
    }
    public ValidationRecoveryStore.View apply(Long sessionId,UUID id,AuthenticatedOperator actor) {
        administrator(actor);
        return tx.execute(ignored->{
            store.admissionLock(); var session=session(sessionId,true); var change=owner(session);
            var entry=exactEntry(id,sessionId,actor);
            if (List.of("APPLY_REQUESTED","APPLYING","APPLIED").contains(entry.state())) return entry.view();
            if (!entry.kind().equals("REPAIR") || !List.of("READY","ATTENTION").contains(entry.state())) throw rejected("VALIDATION_REPAIR_NOT_READY");
            requireIdle(session);
            if (entry.sourceRevision()!=change.getSourceRevision() || !entry.sourceFingerprint().equals(change.getSourceFingerprintSha256()))
                throw rejected("VALIDATION_REPAIR_SOURCE_CHANGED");
            store.state(id,"APPLY_REQUESTED",null); return store.get(id,false).view();
        });
    }
    public ValidationRecoveryStore.View investigate(Long sessionId,AuthenticatedOperator actor) {
        administrator(actor);
        return tx.execute(ignored->{
            store.admissionLock(); var session=session(sessionId,true); var change=owner(session);
            var failed=requireFailure(session,change);
            var existing=store.list(sessionId).stream().filter(e->e.kind().equals("INVESTIGATION")&&e.validationId().equals(failed.getId())).findFirst();
            if (existing.isPresent()) { requireActor(existing.get(),actor); return existing.get().view(); }
            requireIdle(session);
            UUID id=UUID.randomUUID();
            var entry=store.create(id,sessionId,actor.operatorId(),failed.getId(),"INVESTIGATION",change.getSourceRevision(),
                    change.getSourceFingerprintSha256(),null);
            var now=Instant.now(); var turn=new SessionTurnEntity(); turn.setSession(session);
            turn.setActor(SessionTurnActor.ATENEA);turn.setInternal(false);turn.setCreatedAt(now);
            turn.setMessageText(investigationPrompt(failed,change.getSourceRevision()));turns.saveAndFlush(turn);
            var run=agentRuns.createRemoteQueuedRun(session,turn,WorkloadClass.NORMAL);
            store.investigated(entry.id(),run.getId());coordinator.dispatchAfterCommit(run.getId());
            return store.get(id,false).view();
        });
    }
    public ValidationRecoveryStore.View recover(Long sessionId,UUID id,AuthenticatedOperator actor) {
        administrator(actor);
        return tx.execute(ignored->{
            store.admissionLock();owner(session(sessionId,true));var entry=exactEntry(id,sessionId,actor);
            if (!entry.kind().equals("REPAIR") || !entry.state().equals("ATTENTION")) return entry.view();
            requireIdle(session(sessionId,false));
            var proof=gateway.call(entry.command(),Action.INSPECT);
            String next=switch(proof.state()) {
                case "ABSENT","READY" -> "PREPARE_REQUESTED";
                case "APPLYING","APPLIED" -> "APPLY_REQUESTED";
                default -> throw rejected("VALIDATION_REPAIR_POSTCONDITION_FAILED");
            };
            // A retained READY receipt means Apply was not accepted by AX42;
            // preparing again cannot silently publish or apply it.
            store.state(id,next,null);return store.get(id,false).view();
        });
    }
    public ValidationRecoveryStore.View cancel(Long sessionId,UUID id,AuthenticatedOperator actor) {
        administrator(actor);
        return tx.execute(ignored->{
            store.admissionLock();owner(session(sessionId,true));var entry=exactEntry(id,sessionId,actor);
            if (entry.state().equals("CANCELLED")) return entry.view();
            if (!entry.kind().equals("REPAIR") || !List.of("READY","ATTENTION").contains(entry.state()))
                throw rejected("VALIDATION_REPAIR_CANNOT_CANCEL");
            var proof=gateway.call(entry.command(),Action.INSPECT);
            if (!List.of("ABSENT","READY").contains(proof.state())) throw rejected("VALIDATION_REPAIR_CANNOT_CANCEL");
            store.state(id,"CANCELLED",null);return store.get(id,false).view();
        });
    }
    @Scheduled(fixedDelayString="${atenea.validation-recovery.poll-ms:1500}")
    public void reconcile() {
        for (var id:store.pending()) {
            try { process(id); }
            catch (RuntimeException failed) {
                tx.executeWithoutResult(ignored->store.state(id,"ATTENTION","VALIDATION_REPAIR_NEEDS_INSPECTION"));
            }
        }
    }
    public void process(UUID id) {
        var entry=tx.execute(ignored->{
            store.admissionLock();var value=store.get(id,true);
            if (!value.kind().equals("REPAIR")) return null;
            if (value.state().equals("PREPARE_REQUESTED")) store.state(id,"PREPARING",null);
            else if (value.state().equals("APPLY_REQUESTED")) store.state(id,"APPLYING",null);
            else if (!List.of("PREPARING","APPLYING").contains(value.state())) return null;
            var actor=new AuthenticatedOperator(value.operatorId(),""," "); administrator(actor);
            owner(session(value.sessionId(),true)); return store.get(id,false);
        });
        if (entry==null) return;
        Action action=entry.state().equals("PREPARING")?Action.PREPARE:Action.APPLY;
        var proof=gateway.call(entry.command(),action);
        if (action==Action.PREPARE && !proof.state().equals("READY") || action==Action.APPLY && !proof.state().equals("APPLIED"))
            throw rejected("VALIDATION_REPAIR_POSTCONDITION_FAILED");
        tx.executeWithoutResult(ignored->{
            store.admissionLock();var current=store.get(id,true);var session=session(current.sessionId(),true);var change=owner(session);
            if (!current.command().equals(entry.command())) throw rejected("VALIDATION_REPAIR_INTENT_CHANGED");
            if (action==Action.APPLY) {
                var source=worker.fingerprintSourceTree(session);
                if (!"observed".equals(source.state()) || !ProjectCodexIdentity.PROJECT_IDENTITY.equals(source.projectId())
                        || !proof.resultFingerprintSha256().equals(source.fingerprintSha256()) || source.valuesExposed()
                        || !session.getWorkspaceIdentity().equals(source.workspaceIdentity())
                        || !session.getRemoteSessionId().toString().equals(source.sessionId())
                        || !entry.command().owner().sourceCommit().equals(source.headCommit())) throw rejected("VALIDATION_REPAIR_SOURCE_CHANGED");
                if (change.getSourceRevision()==entry.sourceRevision() && change.getSourceFingerprintSha256().equals(entry.sourceFingerprint())) {
                    change.setSourceRevision(Math.addExact(change.getSourceRevision(),1));
                    change.setSourceFingerprintSha256(proof.resultFingerprintSha256());
                    change.setSourceState(DevelopmentChangeSourceState.DIRTY);change.setValidationState(DevelopmentChangeProjectionState.STALE);
                    change.setReviewState(DevelopmentChangeProjectionState.STALE);change.setIntegrationState(DevelopmentChangeProjectionState.STALE);
                    change.setReleaseState(DevelopmentChangeProjectionState.STALE);change.setUpdatedAt(Instant.now());changes.saveAndFlush(change);
                    acceptance.observeSourceTree(session.getId(),proof.resultFingerprintSha256());
                } else if (change.getSourceRevision()!=entry.sourceRevision()+1 || !change.getSourceFingerprintSha256().equals(proof.resultFingerprintSha256()))
                    throw rejected("VALIDATION_REPAIR_SOURCE_CHANGED");
            }
            store.proof(id,proof.state(),proof.evidence(),proof.resultFingerprintSha256());
        });
    }
    private WorkSessionEntity session(Long id,boolean lock) {
        return (lock?sessions.findLockedWithProjectAndDevelopmentChangeById(id):sessions.findWithProjectAndDevelopmentChangeById(id))
                .orElseThrow(()->new WorkSessionNotFoundException(id));
    }
    private DevelopmentChangeEntity owner(WorkSessionEntity session) {
        var c=session.getDevelopmentChange();
        if (c==null || c.getChangeKey()==null || c.getProject()==null || session.getProject()==null
                || !Objects.equals(c.getProject().getId(),session.getProject().getId()) || !ProjectCodexIdentity.matches(session)
                || !ProjectCodexIdentity.matches(c.getProject()) || session.getStatus()!=WorkSessionStatus.OPEN
                || session.getExecutionTarget()!=ExecutionTarget.REMOTE || session.getRemoteSessionId()==null
                || !ProjectCodexIdentity.WORKLOAD_KIND.equals(session.getRemoteWorkloadKind())
                || c.getStatus()!=DevelopmentChangeStatus.OPEN || c.getWorkspaceState()!=DevelopmentChangeWorkspaceState.READY
                || c.getSourceState()!=DevelopmentChangeSourceState.DIRTY || c.getSourceFingerprintSha256()==null
                || !c.getSourceFingerprintSha256().matches("[0-9a-f]{64}")
                || !Objects.equals(session.getSelectedWorkerId(),ProjectCodexIdentity.WORKER_ID)
                || !Objects.equals(c.getSelectedWorkerId(),session.getSelectedWorkerId())
                || !Objects.equals(c.getWorkspaceIdentity(),"remote:ax42-01:change:"+c.getChangeKey())
                || !Objects.equals(c.getWorkspaceIdentity(),session.getWorkspaceIdentity())
                || !Objects.equals(c.getWorkspaceBranch(),"atenea/change-"+c.getChangeKey())
                || !Objects.equals(c.getWorkspaceBranch(),session.getWorkspaceBranch())
                || !Objects.equals(c.getObservedCanonicalCommit(),session.getCanonicalSourceCommit())
                || session.getPublishedChangeKey()!=null || sessions.existsActiveSourceUpdateBySessionId(session.getId())
                || sessions.existsActiveSourceFinalizationBySessionId(session.getId())) throw rejected("VALIDATION_RECOVERY_OWNER_OR_STATE_CHANGED");
        return c;
    }
    private ValidationOperationEntity failure(WorkSessionEntity session,DevelopmentChangeEntity change) {
        var latest=new java.util.EnumMap<ValidationOperationKind,ValidationOperationEntity>(ValidationOperationKind.class);
        for (var item:validations.findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(session.getId(),change.getSourceFingerprintSha256()))
            if (item.getOperation().definitionRevision().equals(item.getDefinitionRevision())) latest.put(item.getOperation(),item);
        return latest.values().stream().filter(v->v.getStatus()==ValidationOperationStatus.BLOCKED||v.getStatus()==ValidationOperationStatus.FAILED).findFirst().orElse(null);
    }
    private ValidationOperationEntity requireFailure(WorkSessionEntity session,DevelopmentChangeEntity change) {
        var value=failure(session,change);if (value==null) throw rejected("VALIDATION_RECOVERY_CURRENT_FAILURE_REQUIRED");return value;
    }
    private void requireIdle(WorkSessionEntity session) { if (!store.idle()) throw rejected("VALIDATION_RECOVERY_EXECUTION_ACTIVE"); }
    private ValidationRecoveryStore.Entry exactEntry(UUID id,Long sessionId,AuthenticatedOperator actor) {
        var e=store.get(id,true);if (!e.sessionId().equals(sessionId)) throw rejected("VALIDATION_RECOVERY_OWNER_MISMATCH"); requireActor(e,actor);return e;
    }
    private void requireActor(ValidationRecoveryStore.Entry e,AuthenticatedOperator actor) {
        if (!e.operatorId().equals(actor.operatorId())) throw rejected("VALIDATION_RECOVERY_OWNER_MISMATCH");
    }
    private void administrator(AuthenticatedOperator actor) {
        var operator=actor==null||actor.operatorId()==null?null:operators.findById(actor.operatorId()).orElse(null);
        if (operator==null||!operator.isActive()||operator.getCodexOperationsRole()!=CodexOperationsRole.PLATFORM_ADMINISTRATOR)
            throw rejected("PLATFORM_ADMINISTRATOR_REQUIRED");
    }
    static boolean knownFailure(ValidationOperationEntity failed) { return failed.getOperation()==ValidationOperationKind.ANDROID_BUILD
        && failed.getStatus()==ValidationOperationStatus.BLOCKED && "UNSUPPORTED_DEPENDENCY_MANIFEST".equals(failureCode(failed.getSummary())); }
    static String failureCode(String summary) {
        var matcher=java.util.regex.Pattern.compile("^\\[([A-Z_]{1,80})\\]").matcher(summary==null?"":summary);
        return matcher.find()?matcher.group(1):"VALIDATION_BLOCKED";
    }
    static String investigationPrompt(ValidationOperationEntity failed,long revision) {
        return "Investiga y corrige el bloqueo de validación de este mismo Cambio, revisión "+revision+".\n"
            +"Comprobación: "+failed.getOperation()+". Resultado durable: "+failed.getStatus()+". Código: "+failureCode(failed.getSummary())+".\n"
            +"Resumen sanitizado: "+ChangeHelpContextService.safeText(failed.getSummary())+"\n"
            +"No repitas el prompt funcional ni crees otro Cambio o WorkSession. Diagnostica antes de editar; conserva el trabajo existente. "
            +"No relajes seguridad, no cambies dependencias para eludir una política, no despliegues ni reintentes pruebas de producción. "
            +"Puedes corregir código de este repositorio cuando la evidencia lo justifique. Si falta capacidad de Platform o acceso administrativo, "
            +"identifica exactamente ese límite, sin pedir terminal/SSH ni inventar una reparación. Termina indicando cambios, pruebas realmente ejecutadas "
            +"y el siguiente paso móvil. Un AgentRun terminado no valida ni publica el cambio.";
    }
    private ValidationRecoveryCommand command(WorkSessionEntity s,DevelopmentChangeEntity c,ValidationOperationEntity failed,UUID id) {
        return new ValidationRecoveryCommand(new DevelopmentChangeBranchPublicationCommand(id,id,c.getChangeKey(),c.getProject().getId(),
            ProjectCodexIdentity.PROJECT_IDENTITY,ProjectCodexIdentity.REPOSITORY,ProjectCodexIdentity.BRANCH,c.getBaseCommit(),
            c.getObservedCanonicalCommit(),c.getWorkspaceBranch(),c.getWorkspaceIdentity(),c.getSelectedWorkerId(),c.getSourceRevision(),
            c.getSourceFingerprintSha256()),failed.getId());
    }
    private static DeliveryRejectedException rejected(String code) { return new DeliveryRejectedException(code); }
    public record Overview(Long workSessionId,UUID changeKey,long sourceRevision,String failureCode,
            boolean canPrepareRepair,boolean canInvestigate,ValidationRecoveryStore.View repair,ValidationRecoveryStore.View investigation) {
        public String getProtocolVersion() { return "validation-recovery/v1"; }
    }
}
