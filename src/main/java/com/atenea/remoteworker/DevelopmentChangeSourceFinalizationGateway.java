package com.atenea.remoteworker;

public interface DevelopmentChangeSourceFinalizationGateway {
    Result finalizeSource(DevelopmentChangeSourceFinalizationCommand command,
            DevelopmentChangeSourceFinalizationCommand.Action action);
    enum FinalizationState { ABSENT, PREPARED, PUBLISHED }
    record Result(FinalizationState state, String publishedHeadSha, String expectedTreeSha, String finalizationReceiptSha256) { }
}
