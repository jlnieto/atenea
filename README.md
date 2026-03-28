# Atenea

Documentos clave:

- `docs/roadmap.md`: estado real actual, bloques completados y gaps abiertos
- `docs/atenea-v1-architecture.md`: dirección arquitectónica general
- `docs/worksession-phase1.md`: estado real actual del core `WorkSession`
- `docs/worksession-target-flow.md`: objetivo canónico de producto para el siguiente gran bloque `WorkSession`
- `docs/task-branch-workflow.md`: flujo legacy `Task` / `TaskExecution`
- `AGENTS.md`: guía local canónica para agentes/Codex

Workflow de desarrollo para este VPS:

```bash
./scripts/test.sh
./scripts/run.sh
./scripts/build.sh
./scripts/shell.sh
./scripts/logs-db.sh
./scripts/logs-codex.sh
./scripts/down.sh
```

## Qué hace cada script

- `./scripts/test.sh`
  Ejecuta la suite Maven dentro de Docker con JDK 21. No requiere Java instalado en el host.
  Es la vía canónica para tests y debe preferirse sobre ejecutar `./mvnw` directamente desde el host.

- `./scripts/run.sh`
  Levanta la base de datos de desarrollo y arranca Spring Boot dentro de Docker exponiendo el puerto `8081`.

- `./scripts/build.sh`
  Genera el `jar` con `./mvnw clean package` dentro de Docker.

- `./scripts/shell.sh`
  Abre una shell Bash dentro del contenedor de desarrollo con el repo montado.

- `./scripts/logs-db.sh`
  Sigue los logs de PostgreSQL de desarrollo.

- `./scripts/logs-codex.sh`
  Sigue los logs del servicio `codex-app-server` del stack de desarrollo.

- `./scripts/down.sh`
  Baja el stack definido en `docker-compose.dev.yml`.

## Stack principal

`docker-compose.dev.yml` levanta:

- `db`: PostgreSQL para desarrollo
- `codex-app-server`: servicio `codex app-server` aislado en Docker
- `atenea-dev`: contenedor de desarrollo con JDK 21, workspace montado y caché Maven persistente

## Estado actual

Atenea es un backend Spring Boot que orquesta trabajo sobre repositorios registrados.

Hoy conviven dos modelos reales:

- flujo legacy:
  - `Project`
  - `Task`
  - `TaskExecution`
  - branch por tarea
  - review / pull request / cierre
- nuevo core conversacional:
  - `WorkSession`
  - `SessionTurn`
  - `AgentRun`
  - apertura de sesión
  - turnos con Codex
  - continuidad de thread
  - listado de turns y runs
  - cierre de sesión

La dirección del producto está centrada en `WorkSession`, pero el flujo `Task` / `TaskExecution` sigue implementado y operativo.

## Superficies API actuales

Hoy el backend expone tres superficies funcionales reales:

- `Project`
  - registro y listado de repositorios operables
  - bootstrap de proyectos canónicos
  - `defaultBaseBranch` por proyecto
  - overview agregado del estado de proyecto
- legacy `Task` / `TaskExecution`
  - flujo por branch de tarea
  - launch / relaunch
  - review / pull request / cierre
  - señales operativas derivadas para UI
- `WorkSession` / `SessionTurn` / `AgentRun`
  - apertura o resolución de sesión
  - branch de trabajo propio por sesión
  - vistas agregadas de sesión
  - turnos conversacionales con Codex
  - continuidad de thread
  - historial de turns y runs
  - cierre de sesión

Referencias:

- `docs/task-branch-workflow.md`: superficie legacy `Task` / `TaskExecution`
- `docs/worksession-phase1.md`: superficie conversacional `WorkSession`
- `docs/worksession-target-flow.md`: ruta objetivo de `WorkSession` como flujo completo de trabajo
- `docs/roadmap.md`: estado consolidado y gaps reales abiertos

## Arquitectura funcional actual

### Legacy `Task` / `TaskExecution`

Sigue existiendo y cubre:

- creación y listado de tareas
- launch / relaunch
- branch safety y project lock
- validación de readiness basada en `description`
- paso a `REVIEW_PENDING`
- `abandon`
- metadatos de pull request
- integración básica con GitHub
- review outcome explícito
- cierre de branch
- visibilidad operativa derivada

### Nuevo core `WorkSession`

Actualmente ya implementa:

- persistencia de `work_session`, `session_turn` y `agent_run`
- una sola sesión `OPEN` por proyecto
- una sola run `RUNNING` por sesión
- `defaultBaseBranch` a nivel de `Project`
- `POST /api/projects/{projectId}/sessions`
- `POST /api/projects/{projectId}/sessions/resolve`
- `POST /api/projects/{projectId}/sessions/resolve/view`
- `POST /api/projects/{projectId}/sessions/resolve/conversation-view`
- `GET /api/sessions/{sessionId}`
- `GET /api/sessions/{sessionId}/view`
- `GET /api/sessions/{sessionId}/conversation-view`
- `POST /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/runs`
- `POST /api/sessions/{sessionId}/close`
- `workspaceBranch` real por sesión con convención `atenea/session-{id}`
- fallback de `baseBranch`:
  - `request.baseBranch`
  - si no viene, `project.defaultBaseBranch`
  - si tampoco existe, branch actual del repo
- recuperación estricta del branch de sesión:
  - permitida desde `baseBranch` limpia
  - permitida si el repo ya está en `workspaceBranch`
  - bloqueada si el repo está en una tercera rama
- continuidad de `externalThreadId` entre turns
- reconciliación de runs `RUNNING` stale al recargar estado de sesión
- vistas agregadas para frontend:
  - `WorkSessionViewResponse`
  - `WorkSessionConversationViewResponse`
- snapshot descriptivo de repositorio en `WorkSessionResponse` con:
  - `repoValid`
  - `workingTreeClean`
  - `currentBranch`
  - `runInProgress`

## Overview de proyecto

`GET /api/projects/overview` ya expone coexistencia explícita entre ambos modelos:

- bloque `workSession` con la sesión canónica del proyecto
  - `OPEN` si existe una abierta
  - o la más reciente por `lastActivityAt`
- bloque `legacy` con:
  - `latestTask`
  - `latestExecution`

El gap actual no es ausencia de overview mixto en backend, sino decidir qué superficie debe ser la canónica para frontend y flujos operador.

## Notas operativas

- El host no necesita Java para desarrollar Atenea en este VPS.
- Los comandos Maven deben ejecutarse a través de los scripts de `scripts/`.
- Para tests, la entrada canónica es `./scripts/test.sh`.
- En desarrollo, `atenea-dev` y `codex-app-server` comparten el workspace canónico del host:
  - `/srv/atenea/workspace/repos` montado en `/workspace/repos`
  - `/srv/atenea/workspace/context` montado en `/workspace/context` en solo lectura
  - `/srv/atenea/workspace/codex-home` montado en `/workspace/codex-home`
- El workspace root es configuración de plataforma mediante `ATENEA_WORKSPACE_ROOT` y en este stack vale `/workspace/repos`.
- La estructura esperada del workspace es `/workspace/repos/internal/...`, `/workspace/repos/clients/...` y `/workspace/repos/sandboxes/...`.
- En desarrollo, el `repoPath` correcto para Atenea es `/workspace/repos/internal/atenea`.
- `repoPath` no es opcional. Debe:
  - ser absoluto
  - estar dentro del `workspaceRoot` configurado
  - existir
  - ser un directorio
  - contener `.git`
- `codex app-server` usa un `HOME` dedicado bajo `/workspace/codex-home`.
- La base de desarrollo usa por defecto:
  - base de datos: `atenea`
  - usuario: `atenea`
  - password: `atenea`
  - puerto host: `5434`
- La app expone por defecto el puerto `8081`.
