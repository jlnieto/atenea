package com.atenea.service.worksession;

import com.atenea.codexappserver.CodexAppServerClient;
import com.atenea.codexappserver.CodexAppServerClient.CodexAppServerExecutionHandle;
import com.atenea.codexappserver.CodexAppServerExecutionListener;
import com.atenea.codexappserver.CodexAppServerExecutionRequest;
import org.springframework.stereotype.Service;

@Service
public class SessionCodexOrchestrator {

    private final CodexAppServerClient codexAppServerClient;

    public SessionCodexOrchestrator(CodexAppServerClient codexAppServerClient) {
        this.codexAppServerClient = codexAppServerClient;
    }

    public CodexAppServerExecutionHandle startTurn(
            String repoPath,
            String message,
            String threadId,
            CodexAppServerExecutionListener listener
    ) throws Exception {
        return codexAppServerClient.startExecution(
                new CodexAppServerExecutionRequest(repoPath, message, threadId),
                listener);
    }
}
