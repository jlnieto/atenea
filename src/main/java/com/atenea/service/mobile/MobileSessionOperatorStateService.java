package com.atenea.service.mobile;

import com.atenea.api.mobile.MobileSessionOperatorState;
import com.atenea.api.mobile.MobileSessionOperatorStateResponse;
import com.atenea.api.mobile.MobileSessionPrimaryAction;
import com.atenea.api.worksession.WorkSessionConversationViewResponse;
import com.atenea.codexoperations.LegacyRemoteCloseService;
import com.atenea.persistence.auth.CodexOperationsRole;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRecoveryNextAction;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.RemoteCloseState;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.remoteworker.RemoteWorkerClient;
import com.atenea.remoteworker.RemoteWorkerException;
import com.atenea.remoteworker.RemoteWorkerProperties;
import com.atenea.service.worksession.AgentRunService;
import org.springframework.stereotype.Service;

@Service
public class MobileSessionOperatorStateService {

    private final AgentRunRepository agentRunRepository;
    private final WorkSessionRepository workSessionRepository;
    private final AgentRunService agentRunService;
    private final RemoteWorkerProperties remoteWorkerProperties;
    private final RemoteWorkerClient remoteWorkerClient;
    private final LegacyRemoteCloseService legacyRemoteCloseService;

    public MobileSessionOperatorStateService(
            AgentRunRepository agentRunRepository,
            WorkSessionRepository workSessionRepository,
            AgentRunService agentRunService,
            RemoteWorkerProperties remoteWorkerProperties,
            RemoteWorkerClient remoteWorkerClient,
            LegacyRemoteCloseService legacyRemoteCloseService
    ) {
        this.agentRunRepository = agentRunRepository;
        this.workSessionRepository = workSessionRepository;
        this.agentRunService = agentRunService;
        this.remoteWorkerProperties = remoteWorkerProperties;
        this.remoteWorkerClient = remoteWorkerClient;
        this.legacyRemoteCloseService = legacyRemoteCloseService;
    }

    public MobileSessionOperatorStateResponse build(
            WorkSessionConversationViewResponse conversation
    ) {
        var view = conversation.view();
        var session = view.session();
        RemoteCloseState closeState = session.remoteCloseState();

        if (closeState == RemoteCloseState.REQUESTED
                || closeState == RemoteCloseState.RECONCILING) {
            boolean reconciliationAvailable =
                    remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor(
                            ProjectCodexIdentity.PROJECT_IDENTITY);
            return state(
                    true,
                    MobileSessionOperatorState.CLOSING_REMOTE,
                    "Cerrando · liberando recursos remotos",
                    closeState == RemoteCloseState.RECONCILING
                            ? "La respuesta del cierre está pendiente de confirmar."
                            : null,
                    MobileSessionPrimaryAction.RECONCILE_REMOTE_CLOSE,
                    "Reconciliar cierre",
                    reconciliationAvailable,
                    CodexOperationsRole.ROUTINE_OPERATOR,
                    session.id(),
                    null);
        }

        if (closeState == RemoteCloseState.BLOCKED) {
            boolean recoveryAvailable =
                    "WORKSPACE_RELEASE_PREFLIGHT_REJECTED".equals(
                            session.remoteCloseErrorCode())
                    && legacyRemoteCloseService.isExactBlockedRecoveryAvailable(
                            session.id());
            return blockedState(session.id(), null, recoveryAvailable);
        }

        AgentRunEntity latestRun = view.latestRun() == null
                ? null
                : agentRunRepository.findById(view.latestRun().id()).orElse(null);
        MobileSessionOperatorStateResponse capacityState = capacityState(latestRun);
        if (capacityState != null) {
            return capacityState;
        }
        MobileSessionOperatorStateResponse historicalCapacityState =
                historicalCapacityState(latestRun, session.id());
        if (historicalCapacityState != null) {
            return historicalCapacityState;
        }

        if (closeState == RemoteCloseState.UNVERIFIED_LEGACY) {
            boolean enabled = remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor(
                    ProjectCodexIdentity.PROJECT_IDENTITY);
            return state(
                    enabled,
                    MobileSessionOperatorState.LEGACY_CLOSE_REQUIRED,
                    "Cierre remoto pendiente de verificar",
                    "La capacidad histórica sólo puede revisarse con una confirmación administrativa.",
                    MobileSessionPrimaryAction.RECONCILE_REMOTE_CLOSE,
                    "Reconciliar cierre",
                    enabled,
                    CodexOperationsRole.PLATFORM_ADMINISTRATOR,
                    session.id(),
                    null);
        }

        if (latestRun != null
                && latestRun.getRecoveryNextAction()
                        == AgentRunRecoveryNextAction.CONTACT_PLATFORM_ADMINISTRATOR) {
            return state(
                    true,
                    MobileSessionOperatorState.OWNERSHIP_REVIEW_REQUIRED,
                    "Revisión administrativa necesaria",
                    "La propiedad remota no coincide de forma inequívoca.",
                    MobileSessionPrimaryAction.CONTACT_PLATFORM_ADMINISTRATOR,
                    "Contactar con administración",
                    false,
                    CodexOperationsRole.PLATFORM_ADMINISTRATOR,
                    session.id(),
                    latestRun.getId());
        }

        if (view.runInProgress()) {
            return state(
                    false,
                    MobileSessionOperatorState.RUNNING,
                    "Codex trabajando",
                    null,
                    MobileSessionPrimaryAction.WAIT,
                    "Esperar actualización",
                    false,
                    null,
                    session.id(),
                    latestRun == null ? null : latestRun.getId());
        }

        if (session.status() == WorkSessionStatus.CLOSED) {
            return state(
                    false,
                    MobileSessionOperatorState.CLOSED,
                    "Sesión cerrada",
                    null,
                    MobileSessionPrimaryAction.NONE,
                    null,
                    false,
                    null,
                    session.id(),
                    latestRun == null ? null : latestRun.getId());
        }

        return state(
                false,
                MobileSessionOperatorState.DEFAULT,
                "Sesión lista",
                null,
                MobileSessionPrimaryAction.NONE,
                null,
                false,
                null,
                session.id(),
                latestRun == null ? null : latestRun.getId());
    }

    private MobileSessionOperatorStateResponse capacityState(AgentRunEntity run) {
        if (run == null
                || !"CLOSED_SESSION_OWNS_CAPACITY".equals(run.getFailureCode())
                || run.getRecoveryNextAction()
                        != AgentRunRecoveryNextAction.RECONCILE_REMOTE_CLOSE
                || run.getRecoveryBlockerWorkSessionId() == null) {
            return null;
        }

        Long blockerId = run.getRecoveryBlockerWorkSessionId();
        WorkSessionEntity blocker = workSessionRepository.findWithProjectById(blockerId)
                .orElse(null);
        if (agentRunService.isRemoteRetryEligible(run.getId())) {
            return releasedCapacityState(run, blockerId);
        }

        if (blocker != null && (blocker.getRemoteCloseState() == RemoteCloseState.REQUESTED
                || blocker.getRemoteCloseState() == RemoteCloseState.RECONCILING)) {
            return state(
                    true,
                    MobileSessionOperatorState.CLOSED_OWNER_RECONCILING,
                    "Cierre remoto en reconciliación",
                    "La sesión cerrada que retenía capacidad aún está confirmando su liberación.",
                    MobileSessionPrimaryAction.WAIT,
                    "Esperar actualización",
                    false,
                    null,
                    blockerId,
                    run.getId());
        }

        if (blocker != null && blocker.getRemoteCloseState() == RemoteCloseState.BLOCKED) {
            return state(
                    true,
                    MobileSessionOperatorState.OWNERSHIP_REVIEW_REQUIRED,
                    "Revisión administrativa necesaria",
                    "La capacidad no puede liberarse hasta verificar su propiedad.",
                    MobileSessionPrimaryAction.CONTACT_PLATFORM_ADMINISTRATOR,
                    "Contactar con administración",
                    false,
                    CodexOperationsRole.PLATFORM_ADMINISTRATOR,
                    blockerId,
                    run.getId());
        }

        boolean enabled = remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY);
        return state(
                enabled,
                MobileSessionOperatorState.CLOSED_OWNER_BLOCKS_CAPACITY,
                "Bloqueada por una sesión cerrada",
                "Otra sesión cerrada conserva la capacidad necesaria. El reintento estará disponible después de reconciliar su cierre.",
                MobileSessionPrimaryAction.RECONCILE_REMOTE_CLOSE,
                "Reconciliar cierre",
                enabled,
                CodexOperationsRole.PLATFORM_ADMINISTRATOR,
                blockerId,
                run.getId());
    }

    private MobileSessionOperatorStateResponse historicalCapacityState(
            AgentRunEntity run,
            Long currentSessionId
    ) {
        if (!remoteWorkerProperties.isRemoteCloseReconciliationEnabledFor(
                    ProjectCodexIdentity.PROJECT_IDENTITY)
                || run == null
                || run.getFailureCode() != null
                || run.getRecoveryNextAction() != null
                || run.getStatus() == null
                || !run.getStatus().isTerminal()
                || run.getRemoteExecutionId() != null
                || !ProjectCodexIdentity.matches(run)) {
            return null;
        }
        WorkSessionEntity current = workSessionRepository.findWithProjectById(
                currentSessionId).orElse(null);
        if (current == null || current.getProject() == null
                || current.getCreatedAt() == null
                || current.getStatus() == WorkSessionStatus.CLOSED
                || !ProjectCodexIdentity.WORKER_ID.equals(current.getSelectedWorkerId())) {
            return null;
        }
        WorkSessionEntity predecessor = workSessionRepository
                .findFirstByProjectIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(
                        current.getProject().getId(),
                        WorkSessionStatus.CLOSED,
                        current.getCreatedAt())
                .orElse(null);
        if (!isExactHistoricalPredecessor(current, predecessor)) {
            return null;
        }
        Long predecessorId = predecessor.getId();
        if (predecessor.getRemoteCloseState() == RemoteCloseState.RELEASED) {
            if (!hasExactReleasedReceipt(predecessor)
                    || !agentRunService.isRemoteRetryEligible(run.getId())) {
                return ownershipReview(predecessorId, run.getId());
            }
            return releasedCapacityState(run, predecessorId);
        }
        if (predecessor.getRemoteCloseState() == RemoteCloseState.REQUESTED
                || predecessor.getRemoteCloseState() == RemoteCloseState.RECONCILING) {
            return state(
                    true,
                    MobileSessionOperatorState.CLOSED_OWNER_RECONCILING,
                    "Cierre remoto en reconciliación",
                    "La sesión cerrada que retenía capacidad aún está confirmando su liberación.",
                    MobileSessionPrimaryAction.WAIT,
                    "Esperar actualización",
                    false,
                    null,
                    predecessorId,
                    run.getId());
        }
        if (predecessor.getRemoteCloseState() == RemoteCloseState.BLOCKED) {
            boolean recoveryAvailable =
                    "WORKSPACE_RELEASE_PREFLIGHT_REJECTED".equals(
                            predecessor.getRemoteCloseErrorCode())
                    && legacyRemoteCloseService.isExactBlockedRecoveryAvailable(
                            predecessorId);
            return recoveryAvailable
                    ? blockedState(predecessorId, run.getId(), true)
                    : ownershipReview(predecessorId, run.getId());
        }
        if (predecessor.getRemoteCloseState() != RemoteCloseState.UNVERIFIED_LEGACY) {
            return null;
        }
        try {
            WorkSessionEntity sourceWitness =
                    ProjectCodexIdentity.hasCanonicalSourceObservation(predecessor)
                            ? predecessor : current;
            RemoteWorkerClient.WorkspaceCapacityOwner diagnosis =
                    remoteWorkerClient.diagnoseWorkspaceCapacityOwner(
                            predecessor, sourceWitness);
            if (!predecessor.getRemoteSessionId().toString().equals(diagnosis.sessionId())
                    || !predecessor.getWorkspaceIdentity().equals(
                            diagnosis.workspaceIdentity())) {
                return ownershipReview(predecessorId, run.getId());
            }
        } catch (RemoteWorkerException exception) {
            return ownershipReview(predecessorId, run.getId());
        }
        return state(
                true,
                MobileSessionOperatorState.CLOSED_OWNER_BLOCKS_CAPACITY,
                "Bloqueada por una sesión cerrada",
                "Otra sesión cerrada conserva la capacidad necesaria. El reintento estará disponible después de reconciliar su cierre.",
                MobileSessionPrimaryAction.RECONCILE_REMOTE_CLOSE,
                "Reconciliar cierre",
                true,
                CodexOperationsRole.PLATFORM_ADMINISTRATOR,
                predecessorId,
                run.getId());
    }

    private boolean isExactHistoricalPredecessor(
            WorkSessionEntity current,
            WorkSessionEntity predecessor
    ) {
        if (predecessor == null
                || predecessor.getProject() == null
                || current.getProject() == null
                || !current.getProject().getId().equals(predecessor.getProject().getId())
                || !ProjectCodexIdentity.hasCanonicalSourceObservation(current)
                || current.getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.WORKER_ID.equals(current.getSelectedWorkerId())
                || !ProjectCodexIdentity.WORKLOAD_KIND.equals(
                        current.getRemoteWorkloadKind())
                || current.getRemoteSessionId() == null
                || predecessor.getExecutionTarget() != ExecutionTarget.REMOTE
                || !ProjectCodexIdentity.WORKER_ID.equals(
                        predecessor.getSelectedWorkerId())
                || !ProjectCodexIdentity.matches(predecessor)
                || predecessor.getRemoteSessionId() == null
                || predecessor.getRemoteSessionId().equals(current.getRemoteSessionId())
                || predecessor.getCreatedAt() == null
                || current.getCreatedAt() == null
                || !predecessor.getCreatedAt().isBefore(current.getCreatedAt())
                || (!ProjectCodexIdentity.hasCanonicalSourceObservation(predecessor)
                    && !hasNoCanonicalSourceObservation(predecessor))) {
            return false;
        }
        String remoteId = predecessor.getRemoteSessionId().toString();
        boolean exactRemoteIdentity = ("remote:" + ProjectCodexIdentity.WORKER_ID
                    + ":work-session:" + remoteId)
                        .equals(predecessor.getWorkspaceIdentity())
                && ("atenea/session-" + remoteId)
                        .equals(predecessor.getWorkspaceBranch());
        if (!exactRemoteIdentity
                || ProjectCodexIdentity.hasCanonicalSourceObservation(predecessor)) {
            return exactRemoteIdentity;
        }
        WorkSessionEntity immediateWitness = workSessionRepository
                .findFirstByProjectIdAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                        predecessor.getProject().getId(), predecessor.getCreatedAt())
                .orElse(null);
        return immediateWitness != null
                && current.getId() != null
                && current.getId().equals(immediateWitness.getId());
    }

    private MobileSessionOperatorStateResponse releasedCapacityState(
            AgentRunEntity run,
            Long releasedOwnerId
    ) {
        if (!remoteWorkerProperties.isFreshSessionOnSourceAdvanceEnabledFor(
                ProjectCodexIdentity.PROJECT_IDENTITY)) {
            return state(
                    true,
                    MobileSessionOperatorState.CAPACITY_RELEASED,
                    "Capacidad liberada",
                    null,
                    MobileSessionPrimaryAction.RETRY_AGENT_RUN,
                    "Reintentar tarea",
                    true,
                    CodexOperationsRole.ROUTINE_OPERATOR,
                    releasedOwnerId,
                    run.getId());
        }
        try {
            RemoteWorkerClient.WorkspaceReadiness readiness =
                    remoteWorkerClient.diagnoseWorkspaceReadiness(run);
            if ("READY_FOR_RETRY".equals(readiness.state())) {
                return state(
                        true,
                        MobileSessionOperatorState.CAPACITY_RELEASED,
                        "Capacidad liberada",
                        null,
                        MobileSessionPrimaryAction.RETRY_AGENT_RUN,
                        "Reintentar tarea",
                        true,
                        CodexOperationsRole.ROUTINE_OPERATOR,
                        releasedOwnerId,
                        run.getId());
            }
            if ("SOURCE_ADVANCED".equals(readiness.state())) {
                return state(
                        true,
                        MobileSessionOperatorState.SOURCE_ADVANCED,
                        "Código actualizado",
                        "Esta tarea quedó vinculada a una versión anterior. Empieza una sesión nueva para trabajar sobre el código actual.",
                        MobileSessionPrimaryAction.START_FRESH_SESSION,
                        "Empezar desde código actual",
                        true,
                        CodexOperationsRole.PLATFORM_ADMINISTRATOR,
                        run.getSession().getId(),
                        run.getId());
            }
            return ownershipReview(releasedOwnerId, run.getId());
        } catch (RemoteWorkerException exception) {
            if (exception.isCompatibleTransportFailure()) {
                return state(
                        true,
                        MobileSessionOperatorState.SOURCE_READINESS_PENDING,
                        "Comprobando código actual",
                        "No se pudo confirmar todavía si el código cambió. Vuelve a intentarlo cuando se restablezca la conexión.",
                        MobileSessionPrimaryAction.WAIT,
                        "Esperar actualización",
                        false,
                        null,
                        run.getSession().getId(),
                        run.getId());
            }
            return ownershipReview(releasedOwnerId, run.getId());
        }
    }

    private boolean hasNoCanonicalSourceObservation(WorkSessionEntity session) {
        return session.getCanonicalSourceRef() == null
                && session.getCanonicalSourceCommit() == null
                && session.getCanonicalSourceObservationSha256() == null
                && session.getCanonicalSourceObservedAt() == null;
    }

    private boolean hasExactReleasedReceipt(WorkSessionEntity predecessor) {
        return predecessor.getRemoteCloseOperationId() != null
                && predecessor.getRemoteCloseReceiptSha256() != null
                && predecessor.getRemoteCloseReceiptSha256().matches("^[0-9a-f]{64}$")
                && predecessor.getRemoteCloseReleasedAt() != null;
    }

    private MobileSessionOperatorStateResponse ownershipReview(
            Long predecessorId,
            Long runId
    ) {
        return state(
                true,
                MobileSessionOperatorState.OWNERSHIP_REVIEW_REQUIRED,
                "Revisión administrativa necesaria",
                "La propiedad remota no coincide de forma inequívoca.",
                MobileSessionPrimaryAction.CONTACT_PLATFORM_ADMINISTRATOR,
                "Contactar con administración",
                false,
                CodexOperationsRole.PLATFORM_ADMINISTRATOR,
                predecessorId,
                runId);
    }

    private MobileSessionOperatorStateResponse blockedState(
            Long targetSessionId,
            Long runId,
            boolean recoveryAvailable
    ) {
        return state(
                true,
                MobileSessionOperatorState.REMOTE_CLOSE_BLOCKED,
                "Cierre remoto bloqueado",
                recoveryAvailable
                        ? "No se liberó ningún recurso. Genera una nueva confirmación administrativa."
                        : "La propiedad remota no pudo verificarse de forma segura.",
                recoveryAvailable
                        ? MobileSessionPrimaryAction.RECONCILE_REMOTE_CLOSE
                        : MobileSessionPrimaryAction.CONTACT_PLATFORM_ADMINISTRATOR,
                recoveryAvailable
                        ? "Volver a validar cierre"
                        : "Contactar con administración",
                recoveryAvailable,
                CodexOperationsRole.PLATFORM_ADMINISTRATOR,
                targetSessionId,
                runId);
    }

    private MobileSessionOperatorStateResponse state(
            boolean surfaceEnabled,
            MobileSessionOperatorState state,
            String title,
            String blocker,
            MobileSessionPrimaryAction primaryAction,
            String primaryActionLabel,
            boolean primaryActionAvailable,
            CodexOperationsRole requiredRole,
            Long targetWorkSessionId,
            Long targetAgentRunId
    ) {
        return new MobileSessionOperatorStateResponse(
                surfaceEnabled,
                state,
                title,
                blocker,
                primaryAction,
                primaryActionLabel,
                primaryActionAvailable,
                requiredRole,
                targetWorkSessionId,
                targetAgentRunId);
    }
}
