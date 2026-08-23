package com.atenea.remoteworker;

public record DevelopmentChangeWorkspaceObservation(
        Disposition disposition,
        String canonicalCommit,
        String sourceFingerprintSha256,
        boolean workspaceDirty,
        boolean retainedDraft,
        String requestFingerprintSha256,
        String ownershipFingerprintSha256
) {

    public enum Disposition {
        ABSENT,
        OWNED,
        FOREIGN
    }
}
