package com.atenea.service.worksession;

import com.atenea.api.worksession.AgentRunResponse;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.SessionTurnActor;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.ExecutionTarget;
import com.atenea.persistence.worksession.WorkloadClass;
import com.atenea.mobilepush.MobilePushDispatchService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunService {

    private static final String INTERNAL_ORIGIN_TURN_MESSAGE = "Internal AgentRun origin";

    private final WorkSessionRepository workSessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final AgentRunProgressService agentRunProgressService;
    private final MobilePushDispatchService mobilePushDispatchService;

    public AgentRunService(
            WorkSessionRepository workSessionRepository,
            AgentRunRepository agentRunRepository,
            SessionTurnRepository sessionTurnRepository,
            AgentRunProgressService agentRunProgressService,
            MobilePushDispatchService mobilePushDispatchService
    ) {
        this.workSessionRepository = workSessionRepository;
        this.agentRunRepository = agentRunRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.agentRunProgressService = agentRunProgressService;
        this.mobilePushDispatchService = mobilePushDispatchService;
    }

    @Transactional
    public AgentRunEntity createRunningRun(Long sessionId) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        Instant now = Instant.now();
        SessionTurnEntity originTurn = createInternalOriginTurn(session, now);
        return createRunningRun(session, originTurn, now);
    }

    @Transactional
    public AgentRunEntity createRunningRun(WorkSessionEntity session, SessionTurnEntity originTurn) {
        return createRunningRun(session, originTurn, Instant.now());
    }

    @Transactional
    public AgentRunEntity createRemoteQueuedRun(
            WorkSessionEntity session,
            SessionTurnEntity originTurn,
            WorkloadClass workloadClass
    ) {
        Instant now = Instant.now();
        ensureNoNonTerminalRun(session.getId());

        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session);
        run.setOriginTurn(originTurn);
        run.setResultTurn(null);
        run.setStatus(AgentRunStatus.QUEUED);
        run.setTargetRepoPath(session.getProject().getRepoPath());
        run.setExternalTurnId(null);
        run.setExecutionTarget(ExecutionTarget.REMOTE);
        run.setSelectedWorkerId(session.getSelectedWorkerId());
        run.setWorkspaceIdentity(session.getWorkspaceIdentity());
        run.setDispatchId(UUID.randomUUID());
        run.setRemoteExecutionId(null);
        run.setWorkloadClass(workloadClass);
        run.setLeaseGeneration(1);
        run.setLeaseExpiresAt(now.plusSeconds(90));
        run.setLastHeartbeatAt(null);
        run.setLifecycleRevision(0);
        run.setQueuedAt(now);
        run.setStatusReason("Awaiting worker admission");
        run.setStartedAt(now);
        run.setFinishedAt(null);
        run.setOutputSummary(null);
        run.setErrorSummary(null);
        run.setCreatedAt(now);
        return agentRunRepository.save(run);
    }

    @Transactional
    public AgentRunEntity markSucceeded(Long runId, String externalTurnId, String outputSummary) {
        return markSucceeded(runId, externalTurnId, outputSummary, null);
    }

    @Transactional
    public AgentRunEntity markSucceeded(
            Long runId,
            String externalTurnId,
            String outputSummary,
            SessionTurnEntity resultTurn
    ) {
        AgentRunEntity run = getRun(runId);
        ensureRunning(run, AgentRunStatus.SUCCEEDED);

        agentRunProgressService.applyExternalTurnId(run, externalTurnId);
        run.setStatus(AgentRunStatus.SUCCEEDED);
        run.setFinishedAt(Instant.now());
        run.setOutputSummary(outputSummary);
        run.setErrorSummary(null);
        run.setResultTurn(resultTurn);
        AgentRunEntity savedRun = agentRunRepository.save(run);
        mobilePushDispatchService.notifyRunSucceeded(savedRun);
        return savedRun;
    }

    @Transactional
    public AgentRunEntity markFailed(Long runId, String externalTurnId, String errorSummary) {
        AgentRunEntity run = getRun(runId);
        ensureRunning(run, AgentRunStatus.FAILED);

        agentRunProgressService.applyExternalTurnId(run, externalTurnId);
        run.setStatus(AgentRunStatus.FAILED);
        run.setFinishedAt(Instant.now());
        run.setOutputSummary(null);
        run.setErrorSummary(errorSummary);
        return agentRunRepository.save(run);
    }

    @Transactional
    public boolean forceMarkFailedIfRunning(Long runId, String externalTurnId, String errorSummary) {
        String normalizedTurnId = normalizeNullableText(externalTurnId);
        String normalizedErrorSummary = normalizeNullableText(errorSummary);
        return agentRunRepository.forceMarkFailedIfRunning(
                runId,
                normalizedTurnId,
                normalizedErrorSummary,
                Instant.now()) > 0;
    }

    @Transactional(readOnly = true)
    public List<AgentRunResponse> getRuns(Long sessionId) {
        if (!workSessionRepository.existsById(sessionId)) {
            throw new WorkSessionNotFoundException(sessionId);
        }

        return agentRunRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                .map(this::toResponseInternal)
                .toList();
    }

    public AgentRunResponse toResponse(AgentRunEntity run) {
        return toResponseInternal(run);
    }

    private AgentRunEntity getRun(Long runId) {
        return agentRunRepository.findWithSessionById(runId)
                .orElseThrow(() -> new AgentRunNotFoundException(runId));
    }

    private String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void ensureRunning(AgentRunEntity run, AgentRunStatus targetStatus) {
        if (run.getStatus().isTerminal()) {
            throw new AgentRunTransitionNotAllowedException(run.getId(), run.getStatus(), targetStatus);
        }
    }

    private AgentRunEntity createRunningRun(WorkSessionEntity session, SessionTurnEntity originTurn, Instant now) {
        Long sessionId = session.getId();
        ensureNoNonTerminalRun(sessionId);

        AgentRunEntity run = new AgentRunEntity();
        run.setSession(session);
        run.setOriginTurn(originTurn);
        run.setResultTurn(null);
        run.setStatus(AgentRunStatus.RUNNING);
        run.setTargetRepoPath(session.getProject().getRepoPath());
        run.setExternalTurnId(null);
        run.setExecutionTarget(ExecutionTarget.LOCAL);
        run.setSelectedWorkerId(null);
        run.setWorkspaceIdentity(session.getWorkspaceIdentity());
        run.setDispatchId(null);
        run.setRemoteExecutionId(null);
        run.setWorkloadClass(WorkloadClass.NORMAL);
        run.setLeaseGeneration(0);
        run.setLifecycleRevision(0);
        run.setStartedAt(now);
        run.setFinishedAt(null);
        run.setOutputSummary(null);
        run.setErrorSummary(null);
        run.setCreatedAt(now);

        return agentRunRepository.save(run);
    }

    private void ensureNoNonTerminalRun(Long sessionId) {
        if (hasNonTerminalRun(sessionId)) {
            throw new AgentRunAlreadyRunningException(sessionId);
        }
    }

    private boolean hasNonTerminalRun(Long sessionId) {
        if (agentRunRepository.existsBySessionIdAndStatus(sessionId, AgentRunStatus.RUNNING)) {
            return true;
        }
        return agentRunRepository.existsBySessionIdAndStatusIn(sessionId, AgentRunStatus.nonTerminalStatuses());
    }

    private SessionTurnEntity createInternalOriginTurn(WorkSessionEntity session, Instant now) {
        SessionTurnEntity turn = new SessionTurnEntity();
        turn.setSession(session);
        turn.setActor(SessionTurnActor.ATENEA);
        turn.setMessageText(INTERNAL_ORIGIN_TURN_MESSAGE);
        turn.setInternal(true);
        turn.setCreatedAt(now);
        return sessionTurnRepository.save(turn);
    }

    private AgentRunResponse toResponseInternal(AgentRunEntity run) {
        return new AgentRunResponse(
                run.getId(),
                run.getSession().getId(),
                run.getOriginTurn() == null ? null : run.getOriginTurn().getId(),
                run.getResultTurn() == null ? null : run.getResultTurn().getId(),
                run.getStatus(),
                run.getTargetRepoPath(),
                run.getExternalTurnId(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getOutputSummary(),
                run.getErrorSummary(),
                run.getCreatedAt(),
                run.getExecutionTarget(),
                run.getSelectedWorkerId(),
                run.getWorkspaceIdentity(),
                run.getDispatchId(),
                run.getRemoteExecutionId(),
                run.getWorkloadClass(),
                run.getLeaseGeneration(),
                run.getLeaseExpiresAt(),
                run.getLastHeartbeatAt(),
                run.getLifecycleRevision(),
                run.getStatusReason()
        );
    }
}
