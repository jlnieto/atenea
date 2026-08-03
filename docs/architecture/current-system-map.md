# Mapa Actual del Sistema

## Propósito

Este mapa es un documento compacto de orientación sobre el backend actual de
Atenea. No sustituye a la documentación de producto detallada. Señala el modelo
runtime que debe usarse al aprender, evaluar o extender comportamiento de
agentes.

Úsalo junto con:

- `README.md`
- `docs/atenea-core.md`
- `docs/worksession-phase1.md`
- `docs/worksession-target-flow.md`
- `docs/roadmap.md`

## Modelo runtime actual

El modelo activo del backend está centrado en:

- `Project`
- `CoreCommand`
- `WorkSession`
- `SessionTurn`
- `AgentRun`

El flujo retirado `Task` / `TaskExecution` es sólo histórico.

## Capas de producto

```text
Superficies de operador
  Core API, mobile API, WorkSession API

Atenea Core
  interpretación de intención, capacidades tipadas, confirmación, timeline de eventos

Workflow de development
  WorkSession, SessionTurn, AgentRun, deliverables, lecturas de billing

Runtime de coding agent
  Codex App Server a través de SessionCodexOrchestrator

Frontera Repository/GitHub
  GitRepositoryService, WorkSessionGitHubService, GitHubClient

Persistencia
  Project, CoreCommand, WorkSession, SessionTurn, AgentRun y tablas relacionadas
```

## Superficies API importantes

Core:

- `POST /api/core/commands`
- `POST /api/core/commands/{commandId}/confirm`
- lecturas de comandos y streams de eventos

WorkSession:

- resolver/abrir sesión
- conversation view
- crear turno
- listar turnos
- listar runs
- publish
- sync de pull request
- close
- deliverables

Mobile:

- overview de proyectos
- inbox
- summary/eventos de sesión
- aliases para acciones de sesión
- auth, notificaciones y superficies de voz

Billing:

- price estimate aprobado por sesión
- price estimates aprobados por proyecto
- cola y summary global de billing

## Paquetes principales

Core:

- `src/main/java/com/atenea/api/core`
- `src/main/java/com/atenea/service/core`
- `src/main/java/com/atenea/persistence/core`

WorkSession:

- `src/main/java/com/atenea/api/worksession`
- `src/main/java/com/atenea/service/worksession`
- `src/main/java/com/atenea/persistence/worksession`

Codex App Server:

- `src/main/java/com/atenea/codexappserver`
- `src/main/java/com/atenea/service/worksession/SessionCodexOrchestrator.java`

Git y GitHub:

- `src/main/java/com/atenea/service/git`
- `src/main/java/com/atenea/github`
- `src/main/java/com/atenea/service/worksession/WorkSessionGitHubService.java`

Projects:

- `src/main/java/com/atenea/api/project`
- `src/main/java/com/atenea/service/project`
- `src/main/java/com/atenea/persistence/project`

## Tests de anclaje

WorkSession y AgentRun:

- `src/test/java/com/atenea/api/worksession/WorkSessionControllerTest.java`
- `src/test/java/com/atenea/api/worksession/SessionTurnControllerTest.java`
- `src/test/java/com/atenea/api/worksession/AgentRunControllerTest.java`
- `src/test/java/com/atenea/api/worksession/WorkSessionFlowIntegrationTest.java`
- `src/test/java/com/atenea/service/worksession/WorkSessionServiceTest.java`
- `src/test/java/com/atenea/service/worksession/SessionTurnCreateServiceTest.java`
- `src/test/java/com/atenea/service/worksession/SessionCodexOrchestratorTest.java`
- `src/test/java/com/atenea/service/worksession/AgentRunServiceTest.java`
- `src/test/java/com/atenea/service/worksession/AgentRunStartupReconciliationRunnerTest.java`
- `src/test/java/com/atenea/service/worksession/WorkSessionGitHubServiceTest.java`

Core:

- `src/test/java/com/atenea/api/core/CoreControllerTest.java`
- `src/test/java/com/atenea/api/core/CoreCommandIntegrationTest.java`
- `src/test/java/com/atenea/service/core/CoreCommandServiceTest.java`
- `src/test/java/com/atenea/service/core/DevelopmentCoreDomainHandlerTest.java`
- `src/test/java/com/atenea/service/core/HybridCoreIntentInterpreterTest.java`

Codex App Server:

- `src/test/java/com/atenea/codexappserver/CodexAppServerClientTest.java`

## Resumen del workflow development actual

1. El operador entra por Core, mobile o endpoints de WorkSession.
2. Atenea resuelve o abre una `WorkSession` para un `Project`.
3. Atenea prepara una branch propiedad de la sesión.
4. El operador crea un turno.
5. Atenea persiste el `SessionTurn` del operador.
6. Atenea crea un `AgentRun` en estado `RUNNING`.
7. Atenea llama a Codex App Server mediante `SessionCodexOrchestrator`.
8. Atenea persiste la continuidad de thread/turn de Codex.
9. En éxito, Atenea persiste un `SessionTurn` de Codex y marca el run como
   `SUCCEEDED`.
10. Cuando está listo, Atenea publica la branch de sesión a un pull request.
11. Atenea sincroniza el estado del pull request.
12. Tras merge, Atenea cierra la sesión y reconcilia el repositorio local.

## Riesgo actual de aprendizaje

El repo ha crecido rápido. El riesgo principal no es perderse un framework. El
riesgo principal es perder la pista de lo que ya está implementado y tomar
decisiones de arquitectura desde memoria incompleta.

Mitigación:

- actualizar este mapa sólo después de leer código/tests
- mantener notas diarias en `docs/learning/daily-log.md`
- evaluar cambios de runtime de agentes mediante `docs/evals/`
