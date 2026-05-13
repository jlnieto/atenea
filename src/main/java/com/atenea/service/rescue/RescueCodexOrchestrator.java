package com.atenea.service.rescue;

import com.atenea.codexappserver.CodexAppServerClient;
import com.atenea.codexappserver.CodexAppServerClient.CodexAppServerExecutionHandle;
import com.atenea.codexappserver.CodexAppServerExecutionListener;
import com.atenea.codexappserver.CodexAppServerExecutionRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class RescueCodexOrchestrator {

    private static final String RESCUE_PROMPT_PREFIX = """
            Estás en MODO RESCATE OPERATIVO de Atenea.

            Contexto:
            - Este flujo existe porque el flujo normal de WorkSession puede estar bloqueado.
            - Trabajas directamente sobre el repositorio actual del proyecto.
            - No prepares ni abras una WorkSession.
            - No asumas que el repositorio está limpio, en la rama correcta, ni en un estado cómodo.
            - Puedes inspeccionar estado, diagnosticar bloqueos y operar sobre Git y archivos del repositorio.
            - Puedes hacer commit y push cuando el operador lo pida claramente.
            - Antes de ejecutar acciones destructivas o difíciles de revertir, pide confirmación explícita. Esto incluye
              `git reset --hard`, `git clean -fd`, borrado de ramas, borrado masivo de archivos, force push y cambios
              fuera del repositorio objetivo.
            - No toques otros repositorios ni directorios externos salvo que el operador lo pida de forma explícita.
            - Si una operación queda a medias, explica el estado exacto y el siguiente paso operativo.

            Formato de respuesta:
            - responde en el mismo idioma que el operador
            - usa Markdown claro
            - resume comandos relevantes y resultado
            - distingue diagnóstico, acciones realizadas y siguiente paso recomendado

            Solicitud del operador:
            """;

    private final CodexAppServerClient codexAppServerClient;

    public RescueCodexOrchestrator(
            @Qualifier("rescueCodexAppServerClient") CodexAppServerClient codexAppServerClient
    ) {
        this.codexAppServerClient = codexAppServerClient;
    }

    public CodexAppServerExecutionHandle startTurn(
            String repoPath,
            String message,
            String threadId,
            CodexAppServerExecutionListener listener
    ) throws Exception {
        return codexAppServerClient.startExecution(
                new CodexAppServerExecutionRequest(repoPath, RESCUE_PROMPT_PREFIX + message, threadId),
                listener);
    }
}
