package com.atenea.remoteworker;

public record DevelopmentChangeWorkspaceObservation(
        Disposition disposition,
        String sourceCommit,
        String sourceFingerprintSha256,
        boolean workspaceDirty,
        boolean retainedDraft,
        String requestFingerprintSha256
) {

    public enum Disposition {
        ABSENT,
        OWNED,
        FOREIGN
    }
}
