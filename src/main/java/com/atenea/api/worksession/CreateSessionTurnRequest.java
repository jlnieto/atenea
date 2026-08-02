package com.atenea.api.worksession;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import java.util.List;
import java.util.UUID;

public record CreateSessionTurnRequest(
        @NotBlank String message,
        UUID clientRequestId,
        List<UUID> attachmentIds
) {

    public CreateSessionTurnRequest {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }

    public CreateSessionTurnRequest(String message) {
        this(message, null, List.of());
    }

    @AssertTrue(message = "clientRequestId is required when attachmentIds are present")
    public boolean isRequestIdentityPresentForAttachments() {
        return attachmentIds.isEmpty() || clientRequestId != null;
    }
}
