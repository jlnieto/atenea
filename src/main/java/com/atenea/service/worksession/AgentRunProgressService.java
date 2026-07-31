package com.atenea.service.worksession;

import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunProgressCategory;
import com.atenea.persistence.worksession.AgentRunProgressEventEntity;
import com.atenea.persistence.worksession.AgentRunProgressEventRepository;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.AgentRunStatus;
import com.atenea.persistence.worksession.WorkSessionEntity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AgentRunProgressService {

    static final int RETAINED_EVENT_LIMIT = 200;

    private final AgentRunRepository agentRunRepository;
    private final AgentRunProgressEventRepository progressEventRepository;
    private final Clock clock;

    public AgentRunProgressService() {
        this(null, null, Clock.systemUTC());
    }

    @Autowired
    public AgentRunProgressService(
            AgentRunRepository agentRunRepository,
            AgentRunProgressEventRepository progressEventRepository) {
        this(agentRunRepository, progressEventRepository, Clock.systemUTC());
    }

    AgentRunProgressService(
            AgentRunRepository agentRunRepository,
            AgentRunProgressEventRepository progressEventRepository,
            Clock clock) {
        this.agentRunRepository = agentRunRepository;
        this.progressEventRepository = progressEventRepository;
        this.clock = clock;
    }

    public void applyExternalTurnId(AgentRunEntity run, String externalTurnId) {
        if (StringUtils.hasText(externalTurnId)) {
            run.setExternalTurnId(externalTurnId.trim());
        }
    }

    public void applyExternalThreadId(WorkSessionEntity session, String externalThreadId) {
        if (StringUtils.hasText(externalThreadId)) {
            session.setExternalThreadId(externalThreadId.trim());
            session.setUpdatedAt(clock.instant());
        }
    }

    @Transactional
    public AgentRunProgressAppendResult append(Long runId, AgentRunProgressCategory category) {
        Objects.requireNonNull(category, "category");
        AgentRunEntity run = agentRunRepository.findByIdForProgressUpdate(runId)
                .orElseThrow(() -> new AgentRunNotFoundException(runId));
        assertTerminalConsistency(run, category);

        String message = category.operatorMessage();
        AgentRunProgressEventEntity latest = progressEventRepository
                .findFirstByAgentRunIdOrderBySequenceDesc(runId)
                .orElse(null);
        if (latest != null
                && latest.getCategory() == category
                && latest.getOperatorMessage().equals(message)) {
            return new AgentRunProgressAppendResult(
                    latest,
                    false,
                    run.getProgressRetainedFloor());
        }

        Instant now = clock.instant();
        long sequence = run.getProgressNextSequence();
        long retainedFloor = Math.max(1, sequence - RETAINED_EVENT_LIMIT + 1);

        AgentRunProgressEventEntity event = new AgentRunProgressEventEntity();
        event.setAgentRun(run);
        event.setSequence(sequence);
        event.setCategory(category);
        event.setOperatorMessage(message);
        event.setOccurredAt(now);
        event.setCreatedAt(now);
        event = progressEventRepository.save(event);

        run.setProgressNextSequence(Math.addExact(sequence, 1));
        run.setProgressRetainedFloor(retainedFloor);
        run.setProgressLatestSequence(sequence);
        run.setProgressLatestCategory(category);
        run.setProgressLatestMessage(message);
        run.setProgressLatestAt(now);
        run.setProgressCurrentState(category);
        run.setProgressTerminalCategory(category.isTerminal() ? category : null);
        run.setProgressElapsedMillis(elapsedMillis(run, now));
        run.setProgressRequiredNextAction(category.nextAction());
        agentRunRepository.save(run);

        progressEventRepository.deleteBelowRetainedFloor(runId, retainedFloor);
        return new AgentRunProgressAppendResult(event, true, retainedFloor);
    }

    @Transactional(readOnly = true)
    public AgentRunProgressReplay replay(Long runId, long afterSequence) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("Progress replay cursor must be non-negative");
        }
        AgentRunEntity run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new AgentRunNotFoundException(runId));
        if (run.getProgressLatestSequence() == null) {
            return new AgentRunProgressReplay(
                    afterSequence, 1, false, null, null, null, 0,
                    null, List.of());
        }

        long floor = run.getProgressRetainedFloor();
        boolean belowFloor = afterSequence < floor - 1;
        long effectiveCursor = belowFloor ? floor - 1 : afterSequence;
        List<AgentRunProgressEventEntity> events = progressEventRepository
                .findByAgentRunIdAndSequenceGreaterThanOrderBySequenceAsc(runId, effectiveCursor);
        AgentRunProgressReplay.AgentRunProgressEventProjection latest =
                new AgentRunProgressReplay.AgentRunProgressEventProjection(
                        run.getProgressLatestSequence(),
                        run.getProgressLatestCategory(),
                        run.getProgressLatestMessage(),
                        run.getProgressLatestAt());
        return new AgentRunProgressReplay(
                afterSequence,
                floor,
                belowFloor,
                run.getProgressCurrentState(),
                latest,
                run.getProgressTerminalCategory(),
                run.getProgressElapsedMillis(),
                run.getProgressRequiredNextAction(),
                List.copyOf(events));
    }

    private static long elapsedMillis(AgentRunEntity run, Instant now) {
        Instant start = run.getStartedAt();
        Instant end = run.getFinishedAt() == null ? now : run.getFinishedAt();
        if (end.isBefore(start)) {
            return 0;
        }
        return Duration.between(start, end).toMillis();
    }

    private static void assertTerminalConsistency(
            AgentRunEntity run,
            AgentRunProgressCategory category) {
        if (!category.isTerminal()) {
            if (run.getStatus().isTerminal()) {
                throw new AgentRunTransitionNotAllowedException(
                        run.getId(), run.getStatus(), run.getStatus());
            }
            return;
        }
        AgentRunStatus expected = switch (category) {
            case COMPLETED -> AgentRunStatus.SUCCEEDED;
            case FAILED -> AgentRunStatus.FAILED;
            case CANCELLED -> AgentRunStatus.CANCELLED;
            default -> throw new IllegalStateException("Unexpected terminal progress category");
        };
        if (run.getStatus() != expected) {
            throw new AgentRunTransitionNotAllowedException(
                    run.getId(), run.getStatus(), expected);
        }
    }
}
