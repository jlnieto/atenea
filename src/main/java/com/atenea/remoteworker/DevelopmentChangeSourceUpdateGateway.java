package com.atenea.remoteworker;

import java.util.List;

public interface DevelopmentChangeSourceUpdateGateway {
    Preparation exchange(DevelopmentChangeSourceUpdateCommand command,
            DevelopmentChangeSourceUpdateCommand.Action action);

    enum State { ABSENT, PREPARED, NEEDS_RESOLUTION, READY_TO_FINALIZE }
    record Preparation(State state, String preparedTreeSha, List<String> conflictFiles,
            String preparedFingerprintSha256, String receiptSha256) {
        public Preparation { conflictFiles = List.copyOf(conflictFiles); }
    }
}
