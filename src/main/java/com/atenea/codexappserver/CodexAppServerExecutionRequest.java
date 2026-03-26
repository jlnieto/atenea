package com.atenea.codexappserver;

public record CodexAppServerExecutionRequest(
        String repoPath,
        String prompt,
        String threadId
) {

    public CodexAppServerExecutionRequest(String repoPath, String prompt) {
        this(repoPath, prompt, null);
    }
}
