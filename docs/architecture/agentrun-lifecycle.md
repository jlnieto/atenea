# Ciclo de Vida de AgentRun

## Propósito

Este documento explica cómo Atenea registra ejecuciones concretas de coding
agent dentro de una `WorkSession`.

Distinción clave:

```text
WorkSession.externalThreadId
  continuidad conversacional externa a nivel sesión

AgentRun.externalTurnId
  una ejecución externa concreta dentro de esa sesión
```

## Modelo actual

`AgentRun` persiste una ejecución del coding agent dentro de una sesión.

Campos documentados actualmente:

- `sessionId`
- `originTurnId`
- `resultTurnId`
- `status`
- `targetRepoPath`
- `externalTurnId`
- `startedAt`
- `finishedAt`
- `outputSummary`
- `errorSummary`

Restricción actual:

- sólo un `AgentRun` en estado `RUNNING` por `WorkSession`

## Flujo de estados actual

Flujo esperado:

```text
RUNNING -> SUCCEEDED
RUNNING -> FAILED
```

También existe reconciliación de runs `RUNNING` obsoletos en startup/lecturas.

## Flujo de creación

Cuando el operador crea un turno de sesión:

1. Se persiste un `SessionTurn` para el mensaje del operador.
2. Se crea un `AgentRun` con estado `RUNNING`.
3. El run apunta al turno del operador mediante `originTurnId`.
4. El run guarda el repo path objetivo.
5. La ejecución con Codex empieza mediante `SessionCodexOrchestrator`.

## Flujo de ejecución con Codex

Runtime actual:

- Codex App Server

Paquete principal de integración:

- `src/main/java/com/atenea/codexappserver`

Servicio principal de orquestación:

- `src/main/java/com/atenea/service/worksession/SessionCodexOrchestrator.java`

Comportamiento de continuidad:

- si `WorkSession.externalThreadId` existe, se envía de nuevo a Codex
- cuando Codex devuelve un thread id, Atenea lo guarda en `WorkSession`
- cada ejecución concreta guarda su Codex turn id en `AgentRun.externalTurnId`

## Flujo de éxito

En ejecución exitosa:

1. Se persiste un `SessionTurn` de Codex.
2. `AgentRun.resultTurnId` apunta al turno de Codex.
3. `AgentRun.status` pasa a `SUCCEEDED`.
4. `outputSummary` captura el resumen útil del resultado.
5. Se establece `finishedAt`.

## Flujo de fallo

En ejecución fallida:

1. El turno del operador permanece persistido.
2. El run permanece como evidencia de traza.
3. `AgentRun.status` pasa a `FAILED`.
4. `errorSummary` registra el fallo.
5. Se establece `finishedAt`.

## Reconciliación

La documentación actual menciona reconciliación de runs `RUNNING` obsoletos en
lecturas posteriores y en startup.

Anclajes de código importantes:

- `src/main/java/com/atenea/service/worksession/AgentRunReconciliationService.java`
- `src/main/java/com/atenea/service/worksession/AgentRunStartupReconciliationRunner.java`
- `src/test/java/com/atenea/service/worksession/AgentRunStartupReconciliationRunnerTest.java`

## Metadata futura candidata

El diseño futuro de `AgentRun` debería considerar persistir modo de ejecución
antes de introducir múltiples runtimes.

Campos candidatos:

- `mode`
- `agentBackend`
- `model`
- `sandbox`
- `approvalPolicy`
- `startedBySurface`
- `eventTraceAvailable`

Modos candidatos:

- `PLAN`
- `IMPLEMENT`
- `REVIEW`
- `CI_FIX`
- `DOCS`
- `EXPLAIN`

Dudas abiertas:

- ¿Debe ser obligatorio el modo para cada nuevo run?
- ¿Deben los runs existentes backfillearse como `IMPLEMENT` o `UNKNOWN`?
- ¿Qué eventos streamed de Codex App Server deben convertirse en eventos de
  dominio persistidos?
- ¿Deben enlazarse resultados de eval a `AgentRun`?

## Siguiente pasada de verificación

Leer estos archivos y actualizar este documento con nombres exactos de métodos y
reglas de transición de estado:

- `src/main/java/com/atenea/service/worksession/AgentRunService.java`
- `src/main/java/com/atenea/service/worksession/AgentRunProgressService.java`
- `src/main/java/com/atenea/service/worksession/AgentRunReconciliationService.java`
- `src/main/java/com/atenea/service/worksession/SessionTurnCompletionService.java`
- `src/test/java/com/atenea/service/worksession/AgentRunServiceTest.java`
- `src/test/java/com/atenea/service/worksession/SessionTurnCreateServiceTest.java`
- `src/test/java/com/atenea/service/worksession/SessionCodexOrchestratorTest.java`
