package com.atenea.remoteworker;

import java.util.Objects;
import java.util.UUID;

/** Immutable App-owned repair intent. Never deserialized from a mobile request. */
public record ValidationRecoveryCommand(DevelopmentChangeBranchPublicationCommand owner, UUID validationOperationId) {
    public static final String PROTOCOL = "development-change-validation-recovery/v1";
    public ValidationRecoveryCommand {
        Objects.requireNonNull(owner); Objects.requireNonNull(validationOperationId);
        if (owner.sourceFingerprintSha256() == null) throw new IllegalArgumentException("Exact draft required");
    }
    public enum Action {
        DIAGNOSE("OBSERVE_ONLY"), PREPARE("PREPARE_EXACT_REPAIR"), APPLY("APPLY_EXACT_REPAIR"), INSPECT("OBSERVE_ONLY");
        final String effect;
        Action(String effect) { this.effect = effect; }
    }
}
