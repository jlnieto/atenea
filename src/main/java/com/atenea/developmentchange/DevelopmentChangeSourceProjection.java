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
    private final String dirtySourceFingerprintSha256;
    private final String sourceCommit;
    private final boolean workspaceDirty;

    public DevelopmentChangeSourceProjection(
            long sourceRevision,
            String dirtySourceFingerprintSha256,
            String sourceCommit,
            boolean workspaceDirty
    ) {
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("sourceRevision must not be negative");
        }
        this.sourceRevision = sourceRevision;
        this.dirtySourceFingerprintSha256 = requireFingerprint(
                dirtySourceFingerprintSha256, workspaceDirty);
        this.sourceCommit = requireGitCommit(sourceCommit);
        this.workspaceDirty = workspaceDirty;
    }

    public Transition observe(
            String observedFingerprintSha256,
            String observedSourceCommit,
            boolean observedWorkspaceDirty
    ) {
        String fingerprint = requireFingerprint(
                observedFingerprintSha256, observedWorkspaceDirty);
        String commit = requireGitCommit(observedSourceCommit);
        boolean fingerprintChanged = observedWorkspaceDirty
                && !Objects.equals(dirtySourceFingerprintSha256, fingerprint);
        boolean sourceChanged = !sourceCommit.equals(commit)
                || workspaceDirty != observedWorkspaceDirty
                || fingerprintChanged;

        if (!sourceChanged) {
            return new Transition(
                    Outcome.UNCHANGED,
                    sourceRevision,
                    observedWorkspaceDirty ? SourceState.DIRTY : SourceState.CLEAN,
                    false,
                    false,
                    Set.of());
        }

        long nextRevision = Math.addExact(sourceRevision, 1L);
        return new Transition(
                Outcome.SOURCE_CHANGED,
                nextRevision,
                observedWorkspaceDirty ? SourceState.DIRTY : SourceState.CLEAN,
                true,
                false,
                ALL_DOWNSTREAM);
    }

    private static String requireFingerprint(String value, boolean dirty) {
        if (dirty && (value == null || !SHA256.matcher(value).matches())) {
            throw new IllegalArgumentException(
                    "dirty source fingerprint must be a lowercase SHA-256");
        }
        if (!dirty && value != null) {
            throw new IllegalArgumentException("clean source must not have a fingerprint");
        }
        return value;
    }

    private static String requireGitCommit(String value) {
        if (value == null || !GIT_COMMIT.matcher(value).matches()) {
            throw new IllegalArgumentException("sourceCommit must be an exact Git object ID");
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
        SOURCE_CHANGED
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
