package com.atenea.service.worksession;

import com.atenea.codexappserver.CodexAppServerClient;
import com.atenea.codexappserver.CodexAppServerClient.CodexAppServerExecutionHandle;
import com.atenea.codexappserver.CodexAppServerExecutionListener;
import com.atenea.codexappserver.CodexAppServerExecutionRequest;
import org.springframework.stereotype.Service;

@Service
public class SessionCodexOrchestrator {

    private static final String WORKSESSION_FINAL_ANSWER_FORMAT = """
            Produce the final_answer in disciplined Markdown.

            Formatting rules:
            - keep the full answer, do not truncate
            - use short paragraphs and real blank lines between sections
            - use real bullet/numbered lists when listing items
            - wrap file paths, commands, classes, methods and identifiers in backticks
            - use Markdown links when referencing concrete files or paths
            - use fenced code blocks for multi-line commands or code snippets
            - answer in the same language as the operator unless the request clearly asks for another language

            When they apply, prefer these sections in the final_answer:
            ## Punto actual
            ## Qué he encontrado
            ## Qué he hecho
            ## Siguiente paso recomendado
            ## Archivos relevantes
            ## Comandos útiles

            Omit any section that does not apply. Do not invent content just to fill sections.

            Operator request:
            """;

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
                new CodexAppServerExecutionRequest(repoPath, buildPrompt(message), threadId),
                listener);
    }

    static String buildPrompt(String message) {
        return WORKSESSION_FINAL_ANSWER_FORMAT + message;
    }
}
