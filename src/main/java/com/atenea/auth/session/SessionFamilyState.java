package com.atenea.auth.session;

import java.util.Objects;
import java.util.UUID;

public record SessionFamilyState(
        UUID familyId,
        long currentGeneration,
        boolean revoked
) {

    public SessionFamilyState {
        Objects.requireNonNull(familyId, "familyId");
        if (currentGeneration < 0) {
            throw new IllegalArgumentException("currentGeneration must not be negative");
        }
    }

    public Transition consume(long generation) {
        if (revoked) {
            return new Transition(this, Outcome.FAMILY_REVOKED, null);
        }
        if (generation < 0 || generation > currentGeneration) {
            return new Transition(this, Outcome.INVALID_GENERATION, null);
        }
        if (generation < currentGeneration) {
            return new Transition(
                    new SessionFamilyState(familyId, currentGeneration, true),
                    Outcome.REPLAY_REVOKED,
                    null);
        }
        long successor = Math.addExact(currentGeneration, 1L);
        return new Transition(
                new SessionFamilyState(familyId, successor, false),
                Outcome.ROTATED,
                successor);
    }

    public record Transition(
            SessionFamilyState state,
            Outcome outcome,
            Long successorGeneration
    ) {
        public Transition {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public enum Outcome {
        ROTATED,
        REPLAY_REVOKED,
        INVALID_GENERATION,
        FAMILY_REVOKED
    }
}
