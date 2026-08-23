package com.atenea.api.worksession;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record CreateSessionTurnRequest(
        @NotBlank String message,
        UUID clientRequestId,
        List<UUID> attachmentIds
) {

    private static final Set<String> FORBIDDEN_SELECTORS = Set.of(
            "path", "repoPath", "targetRepoPath", "host", "workerId",
            "selectedWorkerId", "slot", "workspace", "workspacePath",
            "workspaceIdentity", "shell", "credentials", "token",
            "authorization", "executionAuthority");

    public CreateSessionTurnRequest {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }

    public CreateSessionTurnRequest(String message) {
        this(message, null, List.of());
    }

    @JsonAnySetter
    public void rejectInternalSelector(String field, Object ignored) {
        if (FORBIDDEN_SELECTORS.contains(field)) {
            throw new IllegalArgumentException(
                    "Internal execution selectors are not accepted");
        }
    }

    @AssertTrue(message = "clientRequestId is required when attachmentIds are present")
    public boolean isRequestIdentityPresentForAttachments() {
        return attachmentIds.isEmpty() || clientRequestId != null;
    }
}
