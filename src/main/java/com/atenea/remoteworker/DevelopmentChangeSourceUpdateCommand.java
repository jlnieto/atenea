package com.atenea.remoteworker;

import java.util.Objects;

/** Server-owned immutable intent. The mobile request cannot supply any of these fields. */
public record DevelopmentChangeSourceUpdateCommand(
        DevelopmentChangeBranchPublicationCommand owner,
        String targetMainCommit,
        String publicationReceiptSha256,
        java.util.UUID predecessorPreparationOperationId,
        String predecessorPreparationReceiptSha256,
        Long publishedSourceRevision) {
    public DevelopmentChangeSourceUpdateCommand(DevelopmentChangeBranchPublicationCommand owner, String main, String receipt) {
        this(owner,main,receipt,null,null,null);
    }
    public boolean continuation() { return predecessorPreparationOperationId!=null; }
    public String capability() { return continuation() ? "development-change-source-update/v2" : "development-change-source-update/v1"; }
    public long publicationRevision() { return continuation() ? publishedSourceRevision : owner.sourceRevision(); }
    public DevelopmentChangeSourceUpdateCommand {
        Objects.requireNonNull(owner);
        if ((predecessorPreparationOperationId==null && owner.sourceFingerprintSha256() != null)
                || targetMainCommit == null || !targetMainCommit.matches("[0-9a-f]{40}")
                || publicationReceiptSha256 == null || !publicationReceiptSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Pinned source update requires a clean published owner");
        }
        if (predecessorPreparationOperationId==null ? predecessorPreparationReceiptSha256!=null || publishedSourceRevision!=null
            : predecessorPreparationReceiptSha256==null || !predecessorPreparationReceiptSha256.matches("[0-9a-f]{64}")
                || publishedSourceRevision==null || publishedSourceRevision<0 || publishedSourceRevision>owner.sourceRevision()) {
            throw new IllegalArgumentException("Pinned source continuation requires exact predecessor evidence");
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
