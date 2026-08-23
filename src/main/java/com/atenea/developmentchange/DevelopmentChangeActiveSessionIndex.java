package com.atenea.developmentchange;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DevelopmentChangeActiveSessionIndex {

    private final Map<UUID, ActiveSession> activeSessions;

    public DevelopmentChangeActiveSessionIndex() {
        this(Map.of());
    }

    private DevelopmentChangeActiveSessionIndex(Map<UUID, ActiveSession> activeSessions) {
        this.activeSessions = Map.copyOf(activeSessions);
    }

    public DevelopmentChangeActiveSessionIndex open(
            UUID changeKey,
            long projectId,
            long sessionId
    ) {
        Objects.requireNonNull(changeKey, "changeKey");
        if (projectId <= 0 || sessionId <= 0) {
            throw new IllegalArgumentException("projectId and sessionId must be positive");
        }
        if (activeSessions.containsKey(changeKey)) {
            throw new IllegalStateException("change already has an active session");
        }

        Map<UUID, ActiveSession> updated = new LinkedHashMap<>(activeSessions);
        updated.put(changeKey, new ActiveSession(projectId, sessionId));
        return new DevelopmentChangeActiveSessionIndex(updated);
    }

    public DevelopmentChangeActiveSessionIndex close(UUID changeKey, long sessionId) {
        Objects.requireNonNull(changeKey, "changeKey");
        ActiveSession active = activeSessions.get(changeKey);
        if (active == null || active.sessionId() != sessionId) {
            throw new IllegalStateException("active session ownership does not match");
        }
        Map<UUID, ActiveSession> updated = new LinkedHashMap<>(activeSessions);
        updated.remove(changeKey);
        return new DevelopmentChangeActiveSessionIndex(updated);
    }

    public long activeSessionId(UUID changeKey) {
        ActiveSession active = activeSessions.get(Objects.requireNonNull(changeKey, "changeKey"));
        if (active == null) {
            throw new IllegalStateException("change has no active session");
        }
        return active.sessionId();
    }

    public long activeCountForProject(long projectId) {
        if (projectId <= 0) {
            throw new IllegalArgumentException("projectId must be positive");
        }
        return activeSessions.values().stream()
                .filter(active -> active.projectId() == projectId)
                .count();
    }

    private record ActiveSession(long projectId, long sessionId) {
    }
}
