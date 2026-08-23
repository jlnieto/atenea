package com.atenea.v2.control;

import java.util.regex.Pattern;

public record V2OperationProjection(long revision, boolean terminal, String receiptSha256) {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public V2OperationProjection {
        if (revision < 0) {
            throw new IllegalArgumentException("Operation revision must be non-negative");
        }
        if (terminal && (receiptSha256 == null || !SHA256.matcher(receiptSha256).matches())) {
            throw new IllegalArgumentException("A terminal operation requires a lowercase receipt SHA-256");
        }
        if (!terminal && receiptSha256 != null) {
            throw new IllegalArgumentException("A non-terminal operation cannot have a receipt");
        }
    }

    public V2OperationProjection advance(
            long nextRevision,
            boolean nextTerminal,
            String nextReceiptSha256) {
        if (terminal) {
            throw new IllegalStateException("A terminal operation projection is immutable");
        }
        if (nextRevision <= revision) {
            throw new IllegalArgumentException("Operation revision must advance monotonically");
        }
        return new V2OperationProjection(nextRevision, nextTerminal, nextReceiptSha256);
    }
}
