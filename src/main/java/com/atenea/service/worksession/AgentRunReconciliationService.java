package com.atenea.service.worksession;

import com.atenea.codexappserver.CodexAppServerProperties;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentRunReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunReconciliationService.class);
    private static final Duration STALE_GRACE_PERIOD = Duration.ofMinutes(1);
    private static final String STALE_RUN_ERROR_SUMMARY =
            "Marked FAILED during reconciliation because the run stayed RUNNING past the stale timeout window";
    private static final String STARTUP_RUN_ERROR_SUMMARY =
            "Marked FAILED during startup reconciliation because Atenea restarted while the run was still RUNNING";

    private final AgentRunRepository agentRunRepository;
    private final CodexAppServerProperties codexAppServerProperties;

    public AgentRunReconciliationService(
            AgentRunRepository agentRunRepository,
            CodexAppServerProperties codexAppServerProperties
    ) {
        this.agentRunRepository = agentRunRepository;
        this.codexAppServerProperties = codexAppServerProperties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reconcileSession(Long sessionId) {
        List<AgentRunEntity> runningRuns = agentRunRepository.findBySessionIdAndStatusOrderByCreatedAtAsc(
                sessionId,
                AgentRunStatus.RUNNING);
        if (runningRuns == null || runningRuns.isEmpty()) {
            return false;
        }

        Instant now = Instant.now();
        boolean reconciled = false;
        for (AgentRunEntity run : runningRuns) {
            if (!isStale(run, now)) {
                continue;
            }

            run.setStatus(AgentRunStatus.FAILED);
            run.setFinishedAt(now);
            run.setOutputSummary(null);
            run.setErrorSummary(STALE_RUN_ERROR_SUMMARY);
            agentRunRepository.saveAndFlush(run);
            reconciled = true;

            log.warn(
                    "reconciled stale AgentRun id={} sessionId={} startedAt={} externalTurnId={}",
                    run.getId(),
                    sessionId,
                    run.getStartedAt(),
                    run.getExternalTurnId());
        }

        return reconciled;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reconcileRunningRunsAfterStartup() {
        List<AgentRunEntity> runningRuns = agentRunRepository.findByStatusOrderByCreatedAtAsc(AgentRunStatus.RUNNING);
        if (runningRuns == null || runningRuns.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        int reconciledCount = 0;
        for (AgentRunEntity run : runningRuns) {
            run.setStatus(AgentRunStatus.FAILED);
            run.setFinishedAt(now);
            run.setOutputSummary(null);
            run.setErrorSummary(STARTUP_RUN_ERROR_SUMMARY);
            agentRunRepository.saveAndFlush(run);
            reconciledCount++;

            log.warn(
                    "reconciled startup AgentRun id={} sessionId={} startedAt={} externalTurnId={}",
                    run.getId(),
                    run.getSession() == null ? null : run.getSession().getId(),
                    run.getStartedAt(),
                    run.getExternalTurnId());
        }

        return reconciledCount;
    }

    private boolean isStale(AgentRunEntity run, Instant now) {
        if (run.getFinishedAt() != null) {
            return true;
        }

        Instant startedAt = run.getStartedAt();
        if (startedAt == null) {
            return true;
        }

        Duration staleThreshold = codexAppServerProperties.getStaleTimeout().plus(STALE_GRACE_PERIOD);
        return startedAt.plus(staleThreshold).isBefore(now);
    }
}
