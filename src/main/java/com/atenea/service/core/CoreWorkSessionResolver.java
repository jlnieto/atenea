package com.atenea.service.core;

import com.atenea.persistence.worksession.WorkSessionEntity;
import com.atenea.persistence.worksession.WorkSessionRepository;
import com.atenea.persistence.worksession.WorkSessionStatus;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoreWorkSessionResolver {

    private static final Set<WorkSessionStatus> ACTIVE_STATUSES = Set.of(
            WorkSessionStatus.OPEN,
            WorkSessionStatus.CLOSING
    );

    private final WorkSessionRepository workSessionRepository;

    public CoreWorkSessionResolver(WorkSessionRepository workSessionRepository) {
        this.workSessionRepository = workSessionRepository;
    }

    @Transactional(readOnly = true)
    public WorkSessionEntity requireById(Long sessionId) {
        return workSessionRepository.findWithProjectById(sessionId)
                .orElseThrow(() -> new CoreInvalidContextException("Missing or invalid Core parameter: workSessionId"));
    }

    @Transactional(readOnly = true)
    public WorkSessionEntity resolveActiveSession(String input, Long activeWorkSessionId, Long projectId) {
        if (activeWorkSessionId != null) {
            return requireById(activeWorkSessionId);
        }

        List<WorkSessionEntity> sessions = projectId == null
                ? workSessionRepository.findByStatusInOrderByLastActivityAtDesc(ACTIVE_STATUSES)
                : workSessionRepository.findByProjectIdOrderByLastActivityAtDesc(projectId).stream()
                        .filter(session -> ACTIVE_STATUSES.contains(session.getStatus()))
                        .toList();
        if (sessions.isEmpty()) {
            return null;
        }
        if (sessions.size() == 1) {
            return sessions.getFirst();
        }
        String normalized = normalize(input);
        List<WorkSessionEntity> matches = sessions.stream()
                .filter(session -> normalized.contains(normalize(session.getTitle()))
                        || normalized.contains(normalize(session.getProject().getName())))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.size() > 1) {
            throw clarification(matches);
        }
        throw clarification(sessions);
    }

    private CoreClarificationRequiredException clarification(List<WorkSessionEntity> sessions) {
        return new CoreClarificationRequiredException(new CoreClarification(
                "More than one active work session matches the current request. Please clarify the session.",
                sessions.stream()
                        .limit(5)
                        .map(session -> new CoreClarificationOption(
                                "WORK_SESSION",
                                session.getId(),
                                session.getProject().getName() + " / " + session.getTitle()))
                        .toList()));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').trim();
    }
}
