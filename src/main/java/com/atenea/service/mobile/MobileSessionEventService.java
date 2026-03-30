package com.atenea.service.mobile;

import com.atenea.api.mobile.MobileSessionEventResponse;
import com.atenea.api.mobile.MobileSessionEventsResponse;
import com.atenea.persistence.worksession.AgentRunEntity;
import com.atenea.persistence.worksession.AgentRunRepository;
import com.atenea.persistence.worksession.SessionDeliverableEntity;
import com.atenea.persistence.worksession.SessionDeliverableRepository;
import com.atenea.persistence.worksession.SessionTurnEntity;
import com.atenea.persistence.worksession.SessionTurnRepository;
import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.service.worksession.WorkSessionNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MobileSessionEventService {

    private final WorkSessionRepository workSessionRepository;
    private final SessionTurnRepository sessionTurnRepository;
    private final AgentRunRepository agentRunRepository;
    private final SessionDeliverableRepository sessionDeliverableRepository;

    public MobileSessionEventService(
            WorkSessionRepository workSessionRepository,
            SessionTurnRepository sessionTurnRepository,
            AgentRunRepository agentRunRepository,
            SessionDeliverableRepository sessionDeliverableRepository
    ) {
        this.workSessionRepository = workSessionRepository;
        this.sessionTurnRepository = sessionTurnRepository;
        this.agentRunRepository = agentRunRepository;
        this.sessionDeliverableRepository = sessionDeliverableRepository;
    }

    @Transactional(readOnly = true)
    public MobileSessionEventsResponse getEvents(Long sessionId, Instant after, Integer limit) {
        WorkSessionEntity session = workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(sessionId));
        int resolvedLimit = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);

        List<MobileSessionEventResponse> events = new ArrayList<>();
        events.add(new MobileSessionEventResponse(
                "SESSION_OPENED",
                session.getOpenedAt(),
                "Session opened",
                session.getTitle(),
                null,
                null,
                null));

        if (session.getPublishedAt() != null) {
            events.add(new MobileSessionEventResponse(
                    "SESSION_PUBLISHED",
                    session.getPublishedAt(),
                    "Session published",
                    session.getPullRequestUrl(),
                    null,
                    null,
                    null));
        }
        if (session.getCloseBlockedState() != null) {
            events.add(new MobileSessionEventResponse(
                    "SESSION_CLOSE_BLOCKED",
                    session.getUpdatedAt(),
                    "Close blocked",
                    session.getCloseBlockedReason(),
                    null,
                    null,
                    null));
        }
        if (session.getClosedAt() != null) {
            events.add(new MobileSessionEventResponse(
                    "SESSION_CLOSED",
                    session.getClosedAt(),
                    "Session closed",
                    session.getTitle(),
                    null,
                    null,
                    null));
        }

        for (SessionTurnEntity turn : sessionTurnRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)) {
            if (turn.isInternal()) {
                continue;
            }
            events.add(new MobileSessionEventResponse(
                    "TURN_" + turn.getActor().name(),
                    turn.getCreatedAt(),
                    turn.getActor().name() + " turn",
                    preview(turn.getMessageText()),
                    null,
                    turn.getId(),
                    null));
        }

        for (AgentRunEntity run : agentRunRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)) {
            events.add(new MobileSessionEventResponse(
                    "RUN_STARTED",
                    run.getStartedAt(),
                    "Run started",
                    preview(run.getOutputSummary() != null ? run.getOutputSummary() : run.getErrorSummary()),
                    run.getId(),
                    run.getOriginTurn() == null ? null : run.getOriginTurn().getId(),
                    null));
            if (run.getFinishedAt() != null) {
                events.add(new MobileSessionEventResponse(
                        "RUN_" + run.getStatus().name(),
                        run.getFinishedAt(),
                        "Run " + run.getStatus().name().toLowerCase(),
                        preview(run.getStatus().name().equals("FAILED") ? run.getErrorSummary() : run.getOutputSummary()),
                        run.getId(),
                        run.getResultTurn() == null ? null : run.getResultTurn().getId(),
                        null));
            }
        }

        for (SessionDeliverableEntity deliverable : sessionDeliverableRepository.findBySessionIdOrderByTypeAscVersionDesc(sessionId)) {
            events.add(new MobileSessionEventResponse(
                    "DELIVERABLE_GENERATED",
                    deliverable.getCreatedAt(),
                    deliverable.getType().name() + " generated",
                    deliverable.getTitle(),
                    null,
                    null,
                    deliverable.getId()));
            if (deliverable.getApprovedAt() != null) {
                events.add(new MobileSessionEventResponse(
                        "DELIVERABLE_APPROVED",
                        deliverable.getApprovedAt(),
                        deliverable.getType().name() + " approved",
                        deliverable.getTitle(),
                        null,
                        null,
                        deliverable.getId()));
            }
            if (deliverable.getBilledAt() != null) {
                events.add(new MobileSessionEventResponse(
                        "DELIVERABLE_BILLED",
                        deliverable.getBilledAt(),
                        deliverable.getType().name() + " billed",
                        deliverable.getBillingReference(),
                        null,
                        null,
                        deliverable.getId()));
            }
        }

        List<MobileSessionEventResponse> filtered = events.stream()
                .filter(event -> event.at() != null)
                .filter(event -> after == null || event.at().isAfter(after))
                .sorted(Comparator.comparing(MobileSessionEventResponse::at).reversed())
                .limit(resolvedLimit)
                .toList();
        return new MobileSessionEventsResponse(sessionId, filtered, Instant.now());
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace('\n', ' ').trim().replaceAll("\\s+", " ");
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 157) + "...";
    }
}
