package com.atenea.developmentchange;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class DevelopmentChangeSourceProjection {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern GIT_COMMIT = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");
    private static final Set<DownstreamProjection> ALL_DOWNSTREAM = Set.copyOf(
            EnumSet.allOf(DownstreamProjection.class));

    private final long sourceRevision;
    private final String sourceFingerprintSha256;
    private final String canonicalBaseCommit;

    public DevelopmentChangeSourceProjection(
            long sourceRevision,
            String sourceFingerprintSha256,
            String canonicalBaseCommit
    ) {
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision must not be negative");
        }
        this.sourceRevision = sourceRevision;
        this.sourceFingerprintSha256 = requireSha256(
                sourceFingerprintSha256, "sourceFingerprintSha256");
        this.canonicalBaseCommit = requireGitCommit(canonicalBaseCommit);
    }

    public Transition observe(
            String observedFingerprintSha256,
            String observedCanonicalBaseCommit,
            boolean workspaceDirty
    ) {
        String fingerprint = requireSha256(
                observedFingerprintSha256, "observedFingerprintSha256");
        String canonicalCommit = requireGitCommit(observedCanonicalBaseCommit);
        boolean fingerprintChanged = !sourceFingerprintSha256.equals(fingerprint);
        boolean canonicalAdvanced = !canonicalBaseCommit.equals(canonicalCommit);

        if (!fingerprintChanged && !canonicalAdvanced) {
            return new Transition(
                    Outcome.UNCHANGED,
                    sourceRevision,
                    SourceState.CLEAN,
                    false,
                    false,
                    Set.of());
        }

        long nextRevision = Math.addExact(sourceRevision, 1L);
        if (canonicalAdvanced) {
            return new Transition(
                    Outcome.CANONICAL_ADVANCED,
                    nextRevision,
                    SourceState.STALE,
                    true,
                    true,
                    ALL_DOWNSTREAM);
        }
        return new Transition(
                Outcome.SOURCE_CHANGED,
                nextRevision,
                workspaceDirty ? SourceState.DIRTY : SourceState.CLEAN,
                true,
                false,
                ALL_DOWNSTREAM);
    }

    private static String requireSha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static String requireGitCommit(String value) {
        if (value == null || !GIT_COMMIT.matcher(value).matches()) {
            throw new IllegalArgumentException("canonicalBaseCommit must be an exact Git object ID");
        }
        return value;
    }

    public record Transition(
            Outcome outcome,
            long sourceRevision,
            SourceState sourceState,
            boolean invalidatesDownstream,
            boolean reconciliationRequired,
            Set<DownstreamProjection> staleProjections
    ) {
        public Transition {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(sourceState, "sourceState");
            staleProjections = Set.copyOf(staleProjections);
        }
    }

    public enum Outcome {
        UNCHANGED,
        SOURCE_CHANGED,
        CANONICAL_ADVANCED
    }

    public enum SourceState {
        CLEAN,
        DIRTY,
        STALE
    }

    public enum DownstreamProjection {
        VALIDATION,
        REVIEW,
        INTEGRATION,
        RELEASE
    }
}
