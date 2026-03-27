package com.atenea.service.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atenea.codexappserver.CodexAppServerClient;
import com.atenea.codexappserver.CodexAppServerClient.CodexAppServerExecutionHandle;
import com.atenea.codexappserver.CodexAppServerExecutionListener;
import com.atenea.codexappserver.CodexAppServerExecutionRequest;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionCodexOrchestratorTest {

    @Mock
    private CodexAppServerClient codexAppServerClient;

    @Test
    void startTurnWrapsOperatorMessageWithMarkdownFormattingGuidance() throws Exception {
        SessionCodexOrchestrator orchestrator = new SessionCodexOrchestrator(codexAppServerClient);
        CodexAppServerExecutionHandle handle =
                new CodexAppServerExecutionHandle("thread-1", "turn-1", CompletableFuture.completedFuture(null));
        when(codexAppServerClient.startExecution(any(CodexAppServerExecutionRequest.class), any()))
                .thenReturn(handle);

        orchestrator.startTurn(
                "/workspace/repos/sandboxes/smoke/pruebas-inicial",
                "Explícame el estado del proyecto",
                "thread-existing",
                CodexAppServerExecutionListener.NO_OP);

        ArgumentCaptor<CodexAppServerExecutionRequest> requestCaptor =
                ArgumentCaptor.forClass(CodexAppServerExecutionRequest.class);
        verify(codexAppServerClient).startExecution(requestCaptor.capture(), any());

        CodexAppServerExecutionRequest request = requestCaptor.getValue();
        assertEquals("/workspace/repos/sandboxes/smoke/pruebas-inicial", request.repoPath());
        assertEquals("thread-existing", request.threadId());
        assertTrue(request.prompt().contains("Produce the final_answer in disciplined Markdown."));
        assertTrue(request.prompt().contains("## Punto actual"));
        assertTrue(request.prompt().contains("## Qué he encontrado"));
        assertTrue(request.prompt().contains("Operator request:\nExplícame el estado del proyecto"));
    }
}
