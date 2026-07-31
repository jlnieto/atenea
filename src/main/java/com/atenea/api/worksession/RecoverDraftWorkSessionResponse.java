package com.atenea.api.worksession;

public record RecoverDraftWorkSessionResponse(
        Long blockedSessionId,
        Long replacementSessionId,
        String retainedHead,
        String acceptedCommit,
        String draftFingerprintSha256,
        int stagedChangeCount,
        int unstagedChangeCount,
        int untrackedChangeCount,
        boolean valuesExposed
) {
}
