package com.atenea.api.worksession;

public record TurnExecutionProfileResponse(
        Long runId,
        String modelId,
        String modelSource,
        String reasoningEffort,
        String effortSource,
        String codexVersion
) {}
