# Ciclo de Vida de WorkSession

## Propósito

Este documento registra el ciclo operativo de una `WorkSession` tal como se
entiende hoy. Debe actualizarse sólo desde código, tests y documentación actual.

Fuentes detalladas:

- `docs/worksession-phase1.md`
- `docs/worksession-target-flow.md`

## Estados de ciclo de vida

Estados documentados actualmente:

- `OPEN`
- `CLOSING`
- `CLOSED`

## Abrir o resolver

Entradas:

- `POST /api/projects/{projectId}/sessions`
- `POST /api/projects/{projectId}/sessions/resolve`
- `POST /api/projects/{projectId}/sessions/resolve/view`
- `POST /api/projects/{projectId}/sessions/resolve/conversation-view`
- capacidades Core development para crear o continuar work sessions
- aliases mobile sobre el mismo workflow

Comportamiento actual:

1. Validar que el proyecto existe.
2. Validar `Project.repoPath` mediante reglas de workspace path.
3. Inspeccionar el repositorio con `GitRepositoryService`.
4. Elegir `baseBranch` desde request, default del proyecto o branch actual.
5. Persistir la sesión como `OPEN`.
6. Derivar `workspaceBranch` como `atenea/session-{sessionId}`.
7. Preparar o recuperar la workspace branch.

Política de branch:

- permitir reutilización cuando ya está en `workspaceBranch`
- permitir creación o checkout desde `baseBranch` con worktree limpio
- bloquear cuando el repo está en una tercera branch
- bloquear estados sucios inseguros

## Ejecución de turnos

Entradas:

- `POST /api/sessions/{sessionId}/turns`
- `POST /api/sessions/{sessionId}/turns/conversation-view`
- Core `continue_work_session`
- aliases mobile sobre el mismo workflow

Comportamiento actual:

1. Validar que la sesión existe y está `OPEN`.
2. Validar que el repositorio sigue disponible.
3. Persistir `SessionTurn` visible del operador.
4. Crear `AgentRun` en estado `RUNNING`.
5. Llamar a Codex App Server mediante `SessionCodexOrchestrator`.
6. Reutilizar `WorkSession.externalThreadId` cuando existe.
7. Persistir identificadores devueltos de thread y turn.
8. En éxito, persistir `SessionTurn` visible de Codex.
9. Marcar `AgentRun` como `SUCCEEDED`.
10. En fallo, marcar `AgentRun` como `FAILED` y conservar la traza.

Continuidad:

- `WorkSession.externalThreadId` es continuidad conversacional de Codex a nivel
  sesión.
- `AgentRun.externalTurnId` es trazabilidad por run.

## Publish

Entradas:

- `POST /api/sessions/{sessionId}/publish`
- `POST /api/sessions/{sessionId}/publish/conversation-view`
- Core `publish_work_session`
- alias mobile sobre el mismo workflow

Comportamiento documentado actual:

1. Stage de cambios del repositorio.
2. Crear commit.
3. Push de la branch de sesión con upstream.
4. Crear pull request en GitHub.
5. Persistir metadata de delivery en `WorkSession`.

Metadata de delivery persistida:

- `pullRequestUrl`
- `pullRequestStatus`
- `finalCommitSha`
- `publishedAt`

## Sync de pull request

Entradas:

- `POST /api/sessions/{sessionId}/pull-request/sync`
- `POST /api/sessions/{sessionId}/pull-request/sync/conversation-view`
- Core `sync_work_session_pull_request`
- alias mobile sobre el mismo workflow

Propósito actual:

- refrescar estado del pull request
- mantener el estado de delivery de la sesión alineado con GitHub
- preparar decisiones fiables de cierre

## Deliverables y billing

La sesión puede producir deliverables versionados:

- `WORK_TICKET`
- `WORK_BREAKDOWN`
- `PRICE_ESTIMATE`

Comportamiento importante:

- la generación es explícita
- la aprobación marca baseline
- pricing tiene lecturas estructuradas de summary
- el estado de billing se trackea después de la aprobación

## Close

Entradas:

- `POST /api/sessions/{sessionId}/close`
- `POST /api/sessions/{sessionId}/close/conversation-view`
- Core `close_work_session`
- alias mobile sobre el mismo workflow

Comportamiento documentado actual:

1. Validar que no hay ningún run activo.
2. Validar estado del pull request.
3. Bloquear si el PR no está merged cuando merge es requerido.
4. Reconciliar el repositorio local hacia la base branch.
5. Borrar la branch local de sesión cuando es seguro.
6. Borrar la branch remota de sesión cuando aplica.
7. Persistir `CLOSED` y timestamps de cierre.

Close puede bloquearse con diagnósticos:

- `closeBlockedState`
- `closeBlockedReason`
- `closeBlockedAction`
- `closeRetryable`

Estados bloqueados típicos:

- `running_run`
- `dirty_worktree`
- `pull_request_not_merged`
- `unexpected_branch`
- `unpublished_commits`
- `repo_unavailable`

## Siguiente pasada de verificación

Leer estos archivos y actualizar este documento con nombres exactos de métodos y
casos borde:

- `src/main/java/com/atenea/api/worksession/WorkSessionController.java`
- `src/main/java/com/atenea/service/worksession/WorkSessionService.java`
- `src/main/java/com/atenea/service/worksession/SessionBranchService.java`
- `src/main/java/com/atenea/service/worksession/WorkSessionGitHubService.java`
- `src/test/java/com/atenea/service/worksession/WorkSessionServiceTest.java`
- `src/test/java/com/atenea/api/worksession/WorkSessionControllerTest.java`
