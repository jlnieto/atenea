package com.atenea.codexappserver;

public record CodexAppServerExecutionRequest(
        String repoPath,
        String prompt
) {
}
