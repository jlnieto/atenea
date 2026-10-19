package com.atenea.service.mobile;

import com.atenea.auth.AuthenticatedOperator;
import com.atenea.delivery.DeliveryStore;
import com.atenea.delivery.DeliveryOperation;
import com.atenea.delivery.DeliveryTarget;
import com.atenea.delivery.MobileDeliveryService;
import com.atenea.delivery.ReleaseRecoverySource;
import com.atenea.delivery.ReleaseControlClient;
import com.atenea.persistence.auth.OperatorRepository;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.ValidationOperationRepository;
import com.atenea.persistence.worksession.ValidationOperationKind;
import com.atenea.persistence.worksession.ValidationOperationStatus;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import com.atenea.service.worksession.WorkSessionOperationBlockedException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChangeHelpContextService {
    private final WorkSessionRepository sessions;
    private final AgentRunRepository runs;
    private final ValidationOperationRepository validations;
    private final OperatorRepository operators;
    private final DeliveryStore deliveries;
    private final ReleaseControlClient releaseControl;
    private final MobileDeliveryService deliveryObservations;
    private final com.atenea.delivery.SourceUpdateStore sourceUpdates;
    private final com.atenea.delivery.SourceUpdateBaseGuard sourceBaseGuard;
    private com.atenea.delivery.ReleaseRepairStore repairs;
    @org.springframework.beans.factory.annotation.Autowired
    void setRepairs(com.atenea.delivery.ReleaseRepairStore value) { repairs=value; }

    public ChangeHelpContextService(WorkSessionRepository sessions, AgentRunRepository runs,
            ValidationOperationRepository validations, OperatorRepository operators, DeliveryStore deliveries,
            ReleaseControlClient releaseControl, MobileDeliveryService deliveryObservations,
            com.atenea.delivery.SourceUpdateStore sourceUpdates,
            com.atenea.delivery.SourceUpdateBaseGuard sourceBaseGuard) {
        this.sessions = sessions; this.runs = runs; this.validations = validations;
        this.operators = operators; this.deliveries = deliveries;
        this.releaseControl = releaseControl;
        this.deliveryObservations = deliveryObservations;
        this.sourceUpdates = sourceUpdates;
        this.sourceBaseGuard = sourceBaseGuard;
    }

    @Transactional(readOnly = true)
    public ChangeHelpContext read(Long id, AuthenticatedOperator actor) {
        var operator = actor == null || actor.operatorId() == null ? null
                : operators.findById(actor.operatorId()).orElse(null);
        if (operator == null || !operator.isActive()) {
            throw new WorkSessionOperationBlockedException("Authenticated active operator required");
        }
        var session = sessions.findWithProjectAndDevelopmentChangeById(id)
                .orElseThrow(() -> new WorkSessionNotFoundException(id));
        var change = session.getDevelopmentChange();
        if (change == null || change.getProject() == null || session.getProject() == null
                || change.getChangeKey() == null
                || change.getWorkspaceIdentity() == null || change.getWorkspaceBranch() == null
                || change.getSelectedWorkerId() == null
                || !Objects.equals(change.getProject().getId(), session.getProject().getId())
                || !Objects.equals(change.getWorkspaceIdentity(), session.getWorkspaceIdentity())
                || !Objects.equals(change.getWorkspaceBranch(), session.getWorkspaceBranch())
                || !Objects.equals(change.getSelectedWorkerId(), session.getSelectedWorkerId())) {
            throw new WorkSessionOperationBlockedException("Help requires the exact bound DevelopmentChange");
        }
        var run = runs.findFirstBySessionIdOrderByCreatedAtDesc(id).orElse(null);
        var latest = new EnumMap<ValidationOperationKind, com.atenea.persistence.worksession.ValidationOperationEntity>(ValidationOperationKind.class);
        if (change.getSourceFingerprintSha256() != null) {
            for (var item : validations.findByWorkSessionIdAndSourceTreeFingerprintSha256OrderByStartedAtAscIdAsc(
                    id, change.getSourceFingerprintSha256())) {
                if (item.getOperation() != null && item.getOperation().definitionRevision().equals(item.getDefinitionRevision())
                        && Objects.equals(change.getSourceFingerprintSha256(), item.getSourceTreeFingerprintSha256())) {
                    latest.put(item.getOperation(), item);
                }
            }
        }
        var failed = latest.values().stream().filter(v -> v.getStatus() == ValidationOperationStatus.FAILED
                || v.getStatus() == ValidationOperationStatus.BLOCKED).findFirst().orElse(null);
        int passed = (int) latest.values().stream().filter(v -> v.getStatus() == ValidationOperationStatus.SUCCEEDED).count();
        var ownedDeliveries = deliveries.list(id).stream().filter(d -> Objects.equals(d.operatorId(), actor.operatorId())
                && Objects.equals(d.sessionId(), id)).toList();
        var recordedSource = sourceUpdates.latest(id).filter(op -> Objects.equals(op.sessionId(),id)
                && Objects.equals(op.operatorId(),actor.operatorId())).orElse(null);
        var delivery = ownedDeliveries.stream().filter(d -> belongsToRevision(d, session.getFinalCommitSha(), ownedDeliveries)
                || belongsToPreparedRevision(d,session,recordedSource))
                .findFirst().orElse(null);
        String deploymentStatus = "NOT_CONFIRMED";
        boolean deliveryEnabled=releaseControl.enabled();
        if ("MERGED".equals(name(session.getPullRequestStatus())) && deliveryEnabled
                && "PLATFORM_ADMINISTRATOR".equals(name(operator.getCodexOperationsRole()))) {
            // Same read-only, ownership-checked observation used by the Cambio screen.
            // No plan, grant, reconciliation or publication is requested by help.
            try {
                var observed = deliveryObservations.observeDeployment(id, actor);
                if (observed != null) deploymentStatus = observed.status();
            } catch (RuntimeException unavailable) {
                deploymentStatus = "UNAVAILABLE";
            }
        }
        String response = run == null || run.getResultTurn() == null ? "" : run.getResultTurn().getMessageText();
        com.atenea.delivery.ChangeDeliveryReadiness readiness=null;
        if (Long.valueOf(1).equals(session.getProject().getId()) && deliveryEnabled
                && "PLATFORM_ADMINISTRATOR".equals(name(operator.getCodexOperationsRole()))) {
            try { readiness=deliveryObservations.observeDelivery(id,actor); }
            catch (RuntimeException unavailable) {
                readiness=new com.atenea.delivery.ChangeDeliveryReadiness(id,"UNAVAILABLE",session.getFinalCommitSha(),null,
                        java.util.List.of(),false,null,null,"UNAVAILABLE","DELIVERY_OBSERVATION_UNAVAILABLE",Instant.now().getEpochSecond());
            }
        }
        boolean gitInstructions = response != null && response.contains("github/main")
                && response.toLowerCase(Locale.ROOT).contains("origin");
        String sourceBaseStatus="NOT_REQUIRED";
        if (recordedSource!=null && deliveryEnabled && "OPEN".equals(name(session.getStatus()))
                && !(delivery!=null && "PUBLISH_PR".equals(delivery.kind()) && !delivery.terminal())
                && "PLATFORM_ADMINISTRATOR".equals(name(operator.getCodexOperationsRole()))) {
            sourceBaseStatus=sourceBaseGuard.observe(recordedSource).name();
        }
        boolean preparationBlocked=recordedSource!=null && "BLOCKED".equals(recordedSource.state())
                && recordedSource.preparation()==null && recordedSource.resolverRunId()==null;
        boolean preparing=sessions.existsActiveSourceUpdateBySessionId(id) || preparationBlocked;
        boolean sourcePending=preparing || sessions.existsActiveSourceFinalizationBySessionId(id);
        boolean buildRepair=recordedSource!=null && !recordedSource.state().equals("PUBLISHED")
            && repairs!=null && repairs.preparation(recordedSource.id())!=null;
        var sourceUpdate=preparing || buildRepair ? recordedSource : null;
        String deliveryError=delivery==null ? null : safeCode(delivery.errorCode());
        String deliveryKind=delivery==null ? null : delivery.kind(),deliveryState=delivery==null ? null : delivery.state();
        if(!buildRepair && readiness!=null && "CONFIRMED".equals(readiness.requirementsStatus()) && !readiness.ready()
                && (delivery==null || delivery.terminal())) {
            var build=deliveryObservations.observeBuild(id,actor);
            if(build!=null && build.evidence()!=null) {
                if("READY".equals(build.evidence().state()) && "RELEASE_BUILD_FAILED".equals(deliveryError)) deliveryError=null;
                else if("FAILED".equals(build.evidence().state())) { deliveryKind="RELEASE";deliveryState="BLOCKED";deliveryError="RELEASE_BUILD_FAILED"; }
                else if("PENDING".equals(build.evidence().state())) { deliveryKind="RELEASE_BUILD";deliveryState="PENDING";deliveryError=null; }
            }
        }
        return new ChangeHelpContext(id, session.getProject().getId(), change.getChangeKey(), change.getSourceRevision(),
                Instant.now(), name(change.getStatus()), name(session.getStatus()), name(change.getWorkspaceState()),
                name(change.getSourceState()), name(change.getValidationState()), name(session.getPullRequestStatus()),
                name(change.getIntegrationState()), name(change.getReleaseState()),
                runs.existsBySessionIdAndStatusIn(id, AgentRunStatus.nonTerminalStatuses()),
                validations.existsByWorkSessionIdAndStatus(id, ValidationOperationStatus.RUNNING),
                sourcePending,
                name(session.getRemoteCloseState()), run == null ? null : run.getId(),
                run == null ? null : name(run.getStatus()), run == null ? null : safeCode(run.getFailureCode()),
                gitInstructions, passed, Long.valueOf(1).equals(session.getProject().getId()) ? ValidationOperationKind.values().length : 0,
                failed == null ? null : name(failed.getOperation()), failed == null ? null : safeText(failed.getSummary()),
                buildRepair ? "BUILD_REPAIR" : sourceUpdate != null ? "SOURCE_UPDATE" : deliveryKind,
                sourceUpdate != null ? sourceUpdate.state() : deliveryState,
                sourceUpdate != null ? safeCode(sourceUpdate.errorCode()) : deliveryError,
                name(operator.getCodexOperationsRole()), deliveryEnabled,
                run != null && run.getStatus() == AgentRunStatus.FAILED ? safeText(run.getErrorSummary()) : null,
                delivery == null ? null : name(delivery.target()),
                delivery == null || !delivery.evidence().path("expiresAt").isIntegralNumber() ? null
                        : delivery.evidence().path("expiresAt").asLong(), deploymentStatus,
                delivery != null && delivery.planSha256() != null && delivery.planSha256().matches("[0-9a-f]{64}"),
                readiness, reportedVersion(), sourceBaseStatus);
    }

    private static Integer reportedVersion() {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet)) return null;
        String code=servlet.getRequest().getHeader("X-Atenea-App-Version-Code");
        if (code==null || !code.matches("[1-9][0-9]{0,9}")) return null;
        try { int value=Integer.parseInt(code); return value>0 ? value : null; }
        catch (NumberFormatException invalid) { return null; }
    }

    private static boolean belongsToPreparedRevision(DeliveryOperation delivery,
            com.atenea.persistence.worksession.WorkSessionEntity session, com.atenea.delivery.SourceUpdateOperation source) {
        // A finalization rejected before intent creation has no new commit yet.
        // Attribute its error only to the exact latest retained preparation, not
        // to any null-head operation belonging to this session's history.
        if (source==null || source.command()==null || source.preparation()==null
                || !java.util.Set.of("RESOLVER_COMPLETED","READY_TO_FINALIZE").contains(source.state())
                || !"PUBLISH_PR".equals(delivery.kind()) || delivery.target()!=com.atenea.delivery.DeliveryTarget.APP_PROD
                || delivery.sourceCommit()!=null || source.updatedAt()==null || delivery.createdAt().isBefore(source.updatedAt())
                || !(java.util.Set.of("QUEUED","WAITING_CI").contains(delivery.state())
                    || java.util.Set.of("BLOCKED","FAILED").contains(delivery.state())
                        && delivery.errorCode()!=null && delivery.errorCode().startsWith("SOURCE_FINALIZATION_"))
                || !Objects.equals(session.getFinalCommitSha(),source.command().owner().sourceCommit())) return false;
        var change=session.getDevelopmentChange();
        Long revision=source.resultRevision()==null ? source.preparedRevision() : source.resultRevision();
        String fingerprint=source.resultRevision()==null ? source.preparation().preparedFingerprintSha256() : source.resultFingerprintSha256();
        return Objects.equals(revision,change.getSourceRevision()) && fingerprint!=null
                && fingerprint.equals(change.getSourceFingerprintSha256());
    }

    private static boolean belongsToRevision(DeliveryOperation operation, String head,
            java.util.List<DeliveryOperation> owned) {
        if (head == null) return false;
        if (!"RELEASE".equals(operation.kind()) && Objects.equals(head, operation.sourceCommit())) return true;
        if ("PUBLISH_PR".equals(operation.kind())) {
            return head.equals(operation.evidence().path("headCommit").asText())
                    || head.equals(operation.evidence().path("ufdDispatch").path("headSha").asText());
        }
        if (!"RELEASE".equals(operation.kind())) return false;
        // A recovery release publishes main, not the PR head. Accept it only through
        // its exact retained integration receipt, never by session or a moving main alone.
        try {
            var proof = ReleaseRecoverySource.from(operation);
            if (proof == null) return owned.stream().anyMatch(integrated ->
                    "INTEGRATE".equals(integrated.kind()) && integrated.target() == DeliveryTarget.APP_PROD
                    && "SUCCEEDED".equals(integrated.state()) && head.equals(integrated.sourceCommit())
                    && (operation.target() == DeliveryTarget.AX42_PLATFORM
                        || operation.sourceCommit() != null && operation.sourceCommit().equals(
                                integrated.evidence().path("mergeCommit").asText())));
            return proof != null && head.equals(proof.publishedHeadCommit()) && owned.stream().anyMatch(integrated ->
                    integrated.id().equals(proof.integrationOperationId()) && "INTEGRATE".equals(integrated.kind())
                    && integrated.target() == DeliveryTarget.APP_PROD && "SUCCEEDED".equals(integrated.state())
                    && head.equals(integrated.sourceCommit())
                    && proof.integratedMergeCommit().equals(integrated.evidence().path("mergeCommit").asText()));
        } catch (com.atenea.delivery.DeliveryRejectedException rejected) {
            return false;
        }
    }

    private static String name(Enum<?> value) { return value == null ? "UNKNOWN" : value.name(); }
    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,100}") ? value : null;
    }
    static String safeText(String text) {
        if (text == null || text.isBlank()) return null;
        String value = text.replaceAll("(?is)```.*?```", "[detalle omitido]")
                .replaceAll("(?i)Bearer\\s+\\S+|gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+", "[secreto omitido]")
                .replaceAll("(?i)\\b(?:password|passwd|token|secret|api[_-]?key|authorization|cookie)\\s*[:=]\\s*[^\\s,;]+", "[secreto omitido]")
                .replaceAll("[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{15,}\\.[A-Za-z0-9_-]{15,}", "[secreto omitido]")
                .replaceAll("https?://\\S+|(?<!\\w)/(?:[A-Za-z0-9_.-]+/)+[^\\s]*", "[referencia omitida]")
                .replaceAll("\\s+", " ").trim();
        return value.substring(0, Math.min(value.length(), 400));
    }
}
