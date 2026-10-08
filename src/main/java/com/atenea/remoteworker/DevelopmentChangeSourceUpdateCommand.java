package com.atenea.remoteworker;

import java.util.Objects;

/** Server-owned immutable intent. The mobile request cannot supply any of these fields. */
public record DevelopmentChangeSourceUpdateCommand(
        DevelopmentChangeBranchPublicationCommand owner,
        String targetMainCommit,
        String publicationReceiptSha256) {
    public DevelopmentChangeSourceUpdateCommand {
        Objects.requireNonNull(owner);
        if (owner.sourceFingerprintSha256() != null
                || targetMainCommit == null || !targetMainCommit.matches("[0-9a-f]{40}")
                || publicationReceiptSha256 == null || !publicationReceiptSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Pinned source update requires a clean published owner");
        }
    }
    public enum Action {
        PREPARE("PREPARE_PINNED_MAIN", "prepare"),
        INSPECT("OBSERVE_ONLY", "inspect"),
        RECONCILE("OBSERVE_OR_RESUME_EXACT", "reconcile");
        final String effect;
        final String path;
        Action(String effect, String path) { this.effect = effect; this.path = path; }
    }
}
