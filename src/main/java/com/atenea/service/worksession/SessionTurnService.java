package com.atenea.service.worksession;

import com.atenea.api.worksession.CreateSessionTurnRequest;
import com.atenea.api.worksession.CreateSessionTurnResponse;
import com.atenea.api.worksession.SessionTurnResponse;
import com.atenea.api.worksession.SessionTurnAttachmentResponse;
import com.atenea.api.worksession.TurnExecutionProfileResponse;
import com.atenea.codexappserver.CodexAppServerClient.CodexAppServerExecutionHandle;
import com.atenea.codexappserver.CodexAppServerExecutionListener;
import com.atenea.attachments.AttachmentProperties;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.SessionTurnAttachmentRepository;
import com.atenea.persistence.worksession.SessionTurnAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentEntity;
import com.atenea.persistence.worksession.WorkSessionAttachmentRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkloadClass;
import com.atenea.remoteworker.RemoteAgentRunCoordinator;
import com.atenea.remoteworker.CanonicalSourceAdmissionService;
import com.atenea.remoteworker.ProjectCodexIdentity;
import com.atenea.service.project.WorkspaceRepositoryPathValidator;
import com.atenea.service.git.GitRepositoryService;
import com.atenea.service.git.GitRepositoryOperationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SessionTurnService {

    private static final int MAX_TURN_WINDOW_LIMIT = 100;
    private static final Set<String> HISTORICAL_IMAGE_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp");
    private static final Logger log = LoggerFactory.getLogger(SessionTurnService.class);

    private final WorkSessionRepository workSessionRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final SessionTurnAttachmentRepository sessionTurnAttachmentRepository;
    private final WorkSessionAttachmentRepository workSessionAttachmentRepository;
    private final WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator;
    private final GitRepositoryService gitRepositoryService;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunService agentRunService;
    private final AgentRunProgressService agentRunProgressService;
    private final AgentRunReconciliationService agentRunReconciliationService;
    private final SessionCodexOrchestrator sessionCodexOrchestrator;
    private final SessionTurnCompletionService sessionTurnCompletionService;
    private final CanonicalSourceAdmissionService canonicalSourceAdmissionService;
    private final TurnAttachmentSelectionValidator turnAttachmentSelectionValidator;
    private final TurnAttachmentFingerprintService turnAttachmentFingerprintService;
    private RemoteAgentRunCoordinator remoteAgentRunCoordinator;

    public SessionTurnService(
            WorkSessionRepository workSessionRepository,
            SessionTurnRepository sessionTurnRepository,
            SessionTurnAttachmentRepository sessionTurnAttachmentRepository,
            WorkSessionAttachmentRepository workSessionAttachmentRepository,
            WorkspaceRepositoryPathValidator workspaceRepositoryPathValidator,
            GitRepositoryService gitRepositoryService,
            AgentRunRepository agentRunRepository,
            AgentRunService agentRunService,
            AgentRunProgressService agentRunProgressService,
            AgentRunReconciliationService agentRunReconciliationService,
            SessionCodexOrchestrator sessionCodexOrchestrator,
            SessionTurnCompletionService sessionTurnCompletionService,
            CanonicalSourceAdmissionService canonicalSourceAdmissionService,
            TurnAttachmentSelectionValidator turnAttachmentSelectionValidator,
            TurnAttachmentFingerprintService turnAttachmentFingerprintService
    ) {
        this.workSessionRepository = workSessionRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.sessionTurnAttachmentRepository = sessionTurnAttachmentRepository;
        this.workSessionAttachmentRepository = workSessionAttachmentRepository;
        this.workspaceRepositoryPathValidator = workspaceRepositoryPathValidator;
        this.gitRepositoryService = gitRepositoryService;
        this.agentRunRepository = agentRunRepository;
        this.agentRunService = agentRunService;
        this.agentRunProgressService = agentRunProgressService;
        this.agentRunReconciliationService = agentRunReconciliationService;
        this.sessionCodexOrchestrator = sessionCodexOrchestrator;
        this.sessionTurnCompletionService = sessionTurnCompletionService;
        this.canonicalSourceAdmissionService = canonicalSourceAdmissionService;
        this.turnAttachmentSelectionValidator = turnAttachmentSelectionValidator;
        this.turnAttachmentFingerprintService = turnAttachmentFingerprintService;
    }

    @Autowired(required = false)
    void setRemoteAgentRunCoordinator(RemoteAgentRunCoordinator remoteAgentRunCoordinator) {
        this.remoteAgentRunCoordinator = remoteAgentRunCoordinator;
    }

    @Transactional(readOnly = true)
    public List<SessionTurnResponse> getTurns(Long sessionId) {
        return getTurns(sessionId, null, null);
    }

    @Transactional(readOnly = true)
    public List<SessionTurnResponse> getTurns(Long sessionId, Long beforeTurnId, Integer limit) {
        if (!workSessionRepository.existsById(sessionId)) {
            throw new WorkSessionNotFoundException(sessionId);
        }

        Integer effectiveLimit = normalizeOptionalLimit(limit);
        if (effectiveLimit == null) {
            Map<Long, TurnExecutionProfileResponse> profiles = profilesByTurnId(sessionId);
            List<SessionTurnEntity> turns =
                    sessionTurnRepository.findBySessionIdAndInternalFalseOrderByCreatedAtAsc(sessionId);
            return toResponses(sessionId, turns, profiles);
        }

        List<SessionTurnEntity> newestFirst = beforeTurnId == null
                ? sessionTurnRepository.findBySessionIdAndInternalFalseOrderByCreatedAtDesc(
                        sessionId,
                        PageRequest.of(0, effectiveLimit))
                : sessionTurnRepository.findBySessionIdAndInternalFalseAndIdLessThanOrderByCreatedAtDesc(
                        sessionId,
                        beforeTurnId,
                        PageRequest.of(0, effectiveLimit));
        List<SessionTurnEntity> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        Map<Long, TurnExecutionProfileResponse> profiles = profilesByTurnId(sessionId);
        return toResponses(sessionId, chronological, profiles);
    }

    @Transactional(readOnly = true)
    public long countVisibleTurns(Long sessionId) {
        if (!workSessionRepository.existsById(sessionId)) {
            throw new WorkSessionNotFoundException(sessionId);
        }
        return sessionTurnRepository.countBySessionIdAndInternalFalse(sessionId);
    }

    @Transactional(noRollbackFor = WorkSessionTurnExecutionFailedException.class)
    public CreateSessionTurnResponse createTurn(Long sessionId, CreateSessionTurnRequest request) {
        String message = request.message() == null ? null : request.message().trim();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Turn message must not be blank");
        }

        WorkSessionEntity session = (request.clientRequestId() == null
                ? workSessionRepository.findWithProjectById(sessionId)
                : workSessionRepository.findLockedWithProjectById(sessionId))
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));

        if (request.clientRequestId() != null) {
            SessionTurnEntity acceptedTurn = sessionTurnRepository
                    .findBySessionIdAndClientRequestId(sessionId, request.clientRequestId())
                    .orElse(null);
            if (acceptedTurn != null) {
                return replayAcceptedImageTurn(session, acceptedTurn, request, message);
            }
        }

        if (session.getStatus() != WorkSessionStatus.OPEN) {
            throw new WorkSessionNotOpenException(sessionId, session.getStatus());
        }
        canonicalSourceAdmissionService.admitBeforeWrite(session);
        agentRunReconciliationService.reconcileSession(sessionId);
        if (agentRunRepository.existsBySessionIdAndStatus(sessionId, AgentRunStatus.RUNNING)
                || agentRunRepository.existsBySessionIdAndStatusIn(
                        sessionId,
                        AgentRunStatus.nonTerminalStatuses())) {
            throw new WorkSessionAlreadyRunningException(sessionId);
        }

        TurnAttachmentSelectionValidator.ValidatedSelection attachmentSelection = null;
        String requestFingerprintSha256 = null;
        if (!request.attachmentIds().isEmpty()) {
            attachmentSelection = turnAttachmentSelectionValidator.validate(
                    session,
                    request.attachmentIds());
            requestFingerprintSha256 = turnAttachmentFingerprintService.requestFingerprintSha256(
                    message,
                    attachmentSelection.attachments().stream()
                            .map(attachment -> new TurnAttachmentFingerprintService.AttachmentFingerprintInput(
                                    attachment.id(),
                                    attachment.contentType(),
                                    attachment.sizeBytes(),
                                    attachment.sha256()))
                            .toList());
        }

        Instant now = Instant.now();
        SessionTurnEntity operatorTurn = createVisibleTurn(
                session,
                SessionTurnActor.OPERATOR,
                message,
                now,
                attachmentSelection == null ? null : request.clientRequestId(),
                requestFingerprintSha256);
        touchSession(session, now);

        if (session.getExecutionTarget() == ExecutionTarget.REMOTE) {
            if (remoteAgentRunCoordinator == null) {
                throw new WorkSessionOperationBlockedException("Remote AgentRun coordinator is unavailable");
            }
            AgentRunEntity run = attachmentSelection == null
                    ? agentRunService.createRemoteQueuedRun(
                            session,
                            operatorTurn,
                            WorkloadClass.NORMAL)
                    : agentRunService.createRemoteQueuedRun(
                            session,
                            operatorTurn,
                            WorkloadClass.NORMAL,
                            attachmentSelection);
            if (attachmentSelection != null) {
                insertAttachmentBindings(sessionId, operatorTurn.getId(), attachmentSelection);
            }
            registerRemoteDispatch(run.getId());
            List<SessionTurnAttachmentResponse> responseAttachments = attachmentSelection == null
                    ? List.of()
                    : attachmentResponses(sessionId, List.of(operatorTurn))
                            .getOrDefault(operatorTurn.getId(), List.of());
            return new CreateSessionTurnResponse(
                    toResponse(
                            operatorTurn,
                            executionProfile(run),
                            responseAttachments),
                    agentRunService.toResponse(run),
                    null);
        }

        String repoPath = resolveOperationalRepoPath(session);
        AgentRunEntity run = agentRunService.createRunningRun(session, operatorTurn);
        ExecutionProgress progress = new ExecutionProgress();

        try {
            CodexAppServerExecutionHandle executionHandle = startTurnWithThreadRecovery(
                    session,
                    repoPath,
                    operatorTurn.getMessageText(),
                    progress);

            String effectiveThreadId = firstNonBlank(
                    executionHandle.threadId(),
                    progress.threadId,
                    session.getExternalThreadId());
            String effectiveTurnId = firstNonBlank(executionHandle.turnId(), progress.turnId);
            persistExecutionProgress(session, run, effectiveThreadId, effectiveTurnId);
            registerCompletionTracking(
                    session.getId(),
                    run.getId(),
                    effectiveThreadId,
                    effectiveTurnId,
                    executionHandle);

            return new CreateSessionTurnResponse(
                    toResponse(operatorTurn, null),
                    agentRunService.toResponse(run),
                    null
            );
        } catch (WorkSessionTurnExecutionFailedException exception) {
            throw exception;
        } catch (Exception exception) {
            agentRunService.markFailed(run.getId(), progress.turnId, exception.getMessage());
            persistExecutionProgress(session, run, progress.threadId, progress.turnId);
            touchSession(session, Instant.now());
            throw new WorkSessionTurnExecutionFailedException(
                    "Codex execution failed for WorkSession turn",
                    exception);
        }
    }

    private void registerRemoteDispatch(Long runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            remoteAgentRunCoordinator.dispatchAfterCommit(runId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                remoteAgentRunCoordinator.dispatchAfterCommit(runId);
            }
        });
    }

    private CodexAppServerExecutionHandle startTurnWithThreadRecovery(
            WorkSessionEntity session,
            String repoPath,
            String message,
            ExecutionProgress progress
    ) throws Exception {
        try {
            return startTurn(repoPath, message, session.getExternalThreadId(), progress);
        } catch (Exception exception) {
            if (!shouldRetryWithFreshThread(session.getExternalThreadId(), exception)) {
                throw exception;
            }

            log.warn(
                    "retrying WorkSession turn with a fresh Codex thread after stale externalThreadId sessionId={} threadId={}",
                    session.getId(),
                    session.getExternalThreadId());
            clearPersistedThread(session);
            progress.threadId = null;
            progress.turnId = null;
            return startTurn(repoPath, message, null, progress);
        }
    }

    private CodexAppServerExecutionHandle startTurn(
            String repoPath,
            String message,
            String threadId,
            ExecutionProgress progress
    ) throws Exception {
        return sessionCodexOrchestrator.startTurn(
                repoPath,
                message,
                threadId,
                new CodexAppServerExecutionListener() {
                    @Override
                    public void onThreadStarted(String newThreadId) {
                        progress.threadId = newThreadId;
                    }

                    @Override
                    public void onTurnStarted(String newThreadId, String turnId) {
                        progress.threadId = newThreadId;
                        progress.turnId = turnId;
                    }
                });
    }

    private boolean shouldRetryWithFreshThread(String externalThreadId, Exception exception) {
        if (externalThreadId == null || externalThreadId.isBlank()) {
            return false;
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("turn/start") && normalized.contains("thread not found");
    }

    private void clearPersistedThread(WorkSessionEntity session) {
        session.setExternalThreadId(null);
        session.setUpdatedAt(Instant.now());
    }

    private SessionTurnResponse toResponse(SessionTurnEntity turn) {
        return toResponse(turn, null);
    }

    private SessionTurnResponse toResponse(SessionTurnEntity turn, TurnExecutionProfileResponse profile) {
        return toResponse(turn, profile, List.of());
    }

    private SessionTurnResponse toResponse(
            SessionTurnEntity turn,
            TurnExecutionProfileResponse profile,
            List<SessionTurnAttachmentResponse> attachments
    ) {
        return new SessionTurnResponse(
                turn.getId(),
                turn.getActor(),
                turn.getMessageText(),
                turn.getCreatedAt(),
                profile,
                attachments
        );
    }

    private List<SessionTurnResponse> toResponses(
            Long sessionId,
            List<SessionTurnEntity> turns,
            Map<Long, TurnExecutionProfileResponse> profiles
    ) {
        Map<Long, List<SessionTurnAttachmentResponse>> attachments =
                attachmentResponses(sessionId, turns);
        return turns.stream()
                .map(turn -> toResponse(
                        turn,
                        profiles.get(turn.getId()),
                        attachments.getOrDefault(turn.getId(), List.of())))
                .toList();
    }

    private Map<Long, List<SessionTurnAttachmentResponse>> attachmentResponses(
            Long sessionId,
            List<SessionTurnEntity> turns
    ) {
        if (turns.isEmpty()) {
            return Map.of();
        }
        List<Long> turnIds = turns.stream().map(SessionTurnEntity::getId).toList();
        List<SessionTurnAttachmentEntity> bindings = sessionTurnAttachmentRepository
                .findByWorkSessionIdAndSessionTurnIdInOrderBySessionTurnIdAscPositionAsc(
                        sessionId,
                        turnIds);
        if (bindings.isEmpty()) {
            return Map.of();
        }

        Set<UUID> attachmentIds = bindings.stream()
                .map(SessionTurnAttachmentEntity::getAttachmentId)
                .collect(Collectors.toSet());
        Map<UUID, WorkSessionAttachmentEntity> indexed = workSessionAttachmentRepository
                .findAllById(attachmentIds)
                .stream()
                .collect(Collectors.toMap(WorkSessionAttachmentEntity::getId, Function.identity()));
        if (indexed.size() != attachmentIds.size()) {
            throw historicalAttachmentConflict();
        }

        Map<Long, List<SessionTurnAttachmentResponse>> result = new LinkedHashMap<>();
        for (SessionTurnAttachmentEntity binding : bindings) {
            List<SessionTurnAttachmentResponse> projected = result.computeIfAbsent(
                    binding.getSessionTurnId(),
                    ignored -> new ArrayList<>());
            if (binding.getPosition() != projected.size() || projected.size() >= 4) {
                throw historicalAttachmentConflict();
            }
            WorkSessionAttachmentEntity attachment = indexed.get(binding.getAttachmentId());
            long projectedBytes = projected.stream()
                    .mapToLong(SessionTurnAttachmentResponse::sizeBytes)
                    .sum();
            if (attachment == null
                    || attachment.getKind() != com.atenea.persistence.worksession.AttachmentKind.IMAGE
                    || !HISTORICAL_IMAGE_CONTENT_TYPES.contains(attachment.getContentType())
                    || attachment.getSizeBytes() <= 0
                    || attachment.getSizeBytes() > WorkSessionAttachmentMetadataService.DEFAULT_MAX_FILE_BYTES
                    || projectedBytes > AttachmentProperties.DEFAULT_MAX_ATTACHMENT_BYTES_PER_TURN
                            - attachment.getSizeBytes()
                    || attachment.getSha256() == null
                    || !attachment.getSha256().matches("[0-9a-f]{64}")) {
                throw historicalAttachmentConflict();
            }
            projected.add(new SessionTurnAttachmentResponse(
                    attachment.getId(),
                    binding.getPosition(),
                    attachment.getOriginalFilename(),
                    attachment.getContentType(),
                    attachment.getSizeBytes(),
                    attachment.getSha256(),
                    "/api/sessions/" + sessionId + "/attachments/"
                            + attachment.getId() + "/content"));
        }
        return result.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> List.copyOf(entry.getValue())));
    }

    private AttachmentOwnershipException historicalAttachmentConflict() {
        return new AttachmentOwnershipException(
                "Los adjuntos históricos no conservan un binding de imagen completo y verificable.");
    }

    private Map<Long, TurnExecutionProfileResponse> profilesByTurnId(Long sessionId) {
        Map<Long, TurnExecutionProfileResponse> result = new HashMap<>();
        for (AgentRunEntity run : agentRunRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)) {
            TurnExecutionProfileResponse profile = executionProfile(run);
            if (profile == null) continue;
            if (run.getOriginTurn() != null && !run.getOriginTurn().isInternal()) {
                result.put(run.getOriginTurn().getId(), profile);
            }
            if (run.getResultTurn() != null && !run.getResultTurn().isInternal()) {
                result.put(run.getResultTurn().getId(), profile);
            }
        }
        return result;
    }

    private TurnExecutionProfileResponse executionProfile(AgentRunEntity run) {
        if (run.getCodexModelId() == null) return null;
        return new TurnExecutionProfileResponse(
                run.getId(), run.getCodexModelId(), run.getCodexModelSource().name(),
                run.getCodexReasoningEffort().canonicalValue(), run.getCodexEffortSource().name(),
                run.getCodexVersion());
    }

    private String resolveOperationalRepoPath(WorkSessionEntity session) {
        String repoPath = workspaceRepositoryPathValidator.normalizeConfiguredRepoPath(session.getProject().getRepoPath());
        try {
            gitRepositoryService.getCurrentBranch(repoPath);
            return repoPath;
        } catch (GitRepositoryOperationException exception) {
            throw new WorkSessionOperationBlockedException(
                    "Project repository is not operational for WorkSession turn execution: "
                            + exception.getMessage());
        }
    }

    private SessionTurnEntity createVisibleTurn(
            WorkSessionEntity session,
            SessionTurnActor actor,
            String messageText,
            Instant createdAt,
            java.util.UUID clientRequestId,
            String requestFingerprintSha256
    ) {
        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setSession(session);
        turn.setActor(actor);
        turn.setMessageText(messageText);
        turn.setInternal(false);
        turn.setClientRequestId(clientRequestId);
        turn.setRequestFingerprintSha256(requestFingerprintSha256);
        turn.setCreatedAt(createdAt);
        return sessionTurnRepository.save(turn);
    }

    private void insertAttachmentBindings(
            Long sessionId,
            Long turnId,
            TurnAttachmentSelectionValidator.ValidatedSelection selection
    ) {
        for (int index = 0; index < selection.attachments().size(); index++) {
            int inserted = sessionTurnAttachmentRepository.insert(
                    sessionId,
                    turnId,
                    selection.attachments().get(index).id(),
                    (short) index);
            if (inserted != 1) {
                throw new AttachmentConflictException(
                        "No se pudo confirmar el binding inmutable de todas las imágenes.");
            }
        }
    }

    private CreateSessionTurnResponse replayAcceptedImageTurn(
            WorkSessionEntity session,
            SessionTurnEntity acceptedTurn,
            CreateSessionTurnRequest request,
            String message
    ) {
        if (acceptedTurn.getRequestFingerprintSha256() == null) {
            throw conflictingImageReplay();
        }

        List<SessionTurnAttachmentEntity> bindings = sessionTurnAttachmentRepository
                .findByWorkSessionIdAndSessionTurnIdOrderByPositionAsc(
                        session.getId(),
                        acceptedTurn.getId());
        for (int index = 0; index < bindings.size(); index++) {
            if (bindings.get(index).getPosition() != index) {
                throw conflictingImageReplay();
            }
        }
        List<java.util.UUID> acceptedIds = bindings.stream()
                .map(SessionTurnAttachmentEntity::getAttachmentId)
                .toList();
        if (acceptedIds.isEmpty() || !acceptedIds.equals(request.attachmentIds())) {
            throw conflictingImageReplay();
        }

        List<TurnAttachmentFingerprintService.AttachmentFingerprintInput> fingerprintInputs =
                acceptedIds.stream()
                        .map(attachmentId -> replayFingerprintInput(session.getId(), attachmentId))
                        .toList();
        String manifestSha256;
        String requestFingerprintSha256;
        try {
            manifestSha256 = turnAttachmentFingerprintService
                    .attachmentManifestSha256(fingerprintInputs);
            requestFingerprintSha256 = turnAttachmentFingerprintService
                    .requestFingerprintSha256(message, fingerprintInputs);
        } catch (IllegalArgumentException exception) {
            throw conflictingImageReplay();
        }
        long attachmentBytes = fingerprintInputs.stream()
                .mapToLong(TurnAttachmentFingerprintService.AttachmentFingerprintInput::sizeBytes)
                .sum();

        AgentRunEntity acceptedRun = agentRunRepository
                .findFirstBySessionIdAndOriginTurnIdOrderByCreatedAtAsc(
                        session.getId(),
                        acceptedTurn.getId())
                .orElseThrow(this::conflictingImageReplay);
        if (!ProjectCodexIdentity.IMAGE_WORKLOAD_KIND.equals(acceptedRun.getWorkloadKind())
                || acceptedRun.getAttachmentCount() != acceptedIds.size()
                || acceptedRun.getAttachmentBytes() != attachmentBytes
                || !manifestSha256.equals(acceptedRun.getAttachmentManifestSha256())
                || !requestFingerprintSha256.equals(acceptedTurn.getRequestFingerprintSha256())) {
            throw conflictingImageReplay();
        }

        return new CreateSessionTurnResponse(
                toResponse(
                        acceptedTurn,
                        executionProfile(acceptedRun),
                        attachmentResponses(session.getId(), List.of(acceptedTurn))
                                .getOrDefault(acceptedTurn.getId(), List.of())),
                agentRunService.toResponse(acceptedRun),
                null);
    }

    private TurnAttachmentFingerprintService.AttachmentFingerprintInput replayFingerprintInput(
            Long sessionId,
            java.util.UUID attachmentId
    ) {
        WorkSessionAttachmentEntity attachment = workSessionAttachmentRepository
                .findByIdAndWorkSessionId(attachmentId, sessionId)
                .orElseThrow(this::conflictingImageReplay);
        return new TurnAttachmentFingerprintService.AttachmentFingerprintInput(
                attachment.getId(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getSha256());
    }

    private AttachmentConflictException conflictingImageReplay() {
        return new AttachmentConflictException(
                "La identidad de esta solicitud ya pertenece a otro contenido; "
                        + "se conserva intacta la aceptación original.");
    }

    private void persistExecutionProgress(
            WorkSessionEntity session,
            AgentRunEntity run,
            String externalThreadId,
            String externalTurnId
    ) {
        agentRunProgressService.applyExternalThreadId(session, externalThreadId);
        agentRunProgressService.applyExternalTurnId(run, externalTurnId);
    }

    private void touchSession(WorkSessionEntity session, Instant timestamp) {
        session.setLastActivityAt(timestamp);
        session.setUpdatedAt(timestamp);
    }

    private void registerCompletionTracking(
            Long sessionId,
            Long runId,
            String externalThreadId,
            String externalTurnId,
            CodexAppServerExecutionHandle executionHandle
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sessionTurnCompletionService.trackCompletion(
                    sessionId,
                    runId,
                    externalThreadId,
                    externalTurnId,
                    executionHandle.completionFuture());
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessionTurnCompletionService.trackCompletion(
                        sessionId,
                        runId,
                        externalThreadId,
                        externalTurnId,
                        executionHandle.completionFuture());
            }
        });
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Integer normalizeOptionalLimit(Integer limit) {
        if (limit == null) {
            return null;
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Turn limit must be greater than zero");
        }
        if (limit > MAX_TURN_WINDOW_LIMIT) {
            throw new IllegalArgumentException("Turn limit must not exceed " + MAX_TURN_WINDOW_LIMIT);
        }
        return limit;
    }

    private static final class ExecutionProgress {
        private String threadId;
        private String turnId;
    }
}
