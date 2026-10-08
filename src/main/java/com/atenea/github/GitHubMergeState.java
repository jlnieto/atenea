package com.atenea.github;

import com.fasterxml.jackson.databind.JsonNode;

/** Mergeability is separate from CI; an explicit conflict is never a pending run. */
public enum GitHubMergeState {
    MERGEABLE, CONFLICTS, UNKNOWN, PROTECTED, MERGED, CLOSED;

    static GitHubMergeState observe(JsonNode pr) {
        if (pr.path("merged").asBoolean()) return MERGED;
        if ("closed".equals(pr.path("state").asText())) return CLOSED;
        if (!"open".equals(pr.path("state").asText())) {
            throw new GitHubIntegrationException("PR_EVIDENCE_INCOMPLETE");
        }
        String state = pr.path("mergeable_state").asText();
        if ("dirty".equals(state)) return CONFLICTS;
        JsonNode mergeable = pr.path("mergeable");
        if (!mergeable.isBoolean() || state.isBlank() || "unknown".equals(state)) return UNKNOWN;
        if (!mergeable.asBoolean()) return PROTECTED;
        if ("clean".equals(state) || "draft".equals(state)) return MERGEABLE;
        return PROTECTED;
    }
}
