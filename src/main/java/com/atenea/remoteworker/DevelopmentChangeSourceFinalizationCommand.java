package com.atenea.remoteworker;

import java.util.Objects;
import java.util.UUID;

/** Authenticated App authority only; no mobile-provided fields. */
public record DevelopmentChangeSourceFinalizationCommand(DevelopmentChangeBranchPublicationCommand owner,
        String targetMainCommit, String publicationReceiptSha256, UUID preparationOperationId,
        String preparationReceiptSha256, String validationProjectionSha256) {
    public DevelopmentChangeSourceFinalizationCommand {
        Objects.requireNonNull(owner); Objects.requireNonNull(preparationOperationId);
        if (targetMainCommit == null || !targetMainCommit.matches("[0-9a-f]{40}")
                || !hash(publicationReceiptSha256) || !hash(preparationReceiptSha256)
                || !hash(validationProjectionSha256)) throw new IllegalArgumentException("Invalid finalization evidence");
    }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    public enum Action { FINALIZE, INSPECT }
}
