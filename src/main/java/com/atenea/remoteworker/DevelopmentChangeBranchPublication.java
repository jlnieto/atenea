package com.atenea.remoteworker;

public record DevelopmentChangeBranchPublication(
        String publishedHeadSha,
        RemoteDisposition remoteDisposition,
        String requestFingerprintSha256,
        String publicationReceiptSha256
) {
    public enum RemoteDisposition {
        CREATED,
        IDENTICAL
    }
}
