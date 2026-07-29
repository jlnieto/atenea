package com.atenea.persistence.worksession;

import java.util.EnumSet;
import java.util.Set;

public enum PreviewState {
    STOPPED,
    STARTING,
    READY,
    BLOCKED,
    RECONCILING,
    EXPIRED;

    public static Set<PreviewState> reconcilableStates() {
        return EnumSet.of(STARTING, READY, RECONCILING);
    }

    public boolean isTerminal() {
        return this == STOPPED || this == BLOCKED || this == EXPIRED;
    }
}
