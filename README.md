# Atenea

Documentos clave:

- `docs/roadmap.md`: estado real actual, bloques completados y gaps abiertos
- `docs/atenea-v1-architecture.md`: dirección arquitectónica general
- `docs/worksession-phase1.md`: estado real actual del core `WorkSession`
- `docs/worksession-target-flow.md`: objetivo canónico de producto para el siguiente gran bloque `WorkSession`
- `docs/session-deliverables-design.md`: diseño objetivo para deliverables persistidos, reporting y pricing de sesión
- `docs/task-branch-workflow.md`: referencia histórica del flujo retirado `Task` / `TaskExecution`
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

El producto backend hoy está centrado únicamente en `WorkSession`:

- `Project`
- `WorkSession`
- `SessionTurn`
- `AgentRun`
- apertura o resolución de sesión
- turnos conversacionales con Codex
- continuidad de thread
- historial de turns y runs
- publish a pull request
- sync de pull request
- cierre fuerte con reconciliación

El flujo legacy `Task` / `TaskExecution` ya fue retirado del backend y de la base de datos.

## Superficies API actuales

Hoy el backend expone dos superficies funcionales reales:

- `Project`
  - registro y listado de repositorios operables
  - bootstrap de proyectos canónicos
  - `defaultBaseBranch` por proyecto
  - overview agregado del estado de proyecto
- `WorkSession` / `SessionTurn` / `AgentRun`
  - apertura o resolución de sesión
  - branch de trabajo propio por sesión
  - vistas agregadas de sesión
  - turnos conversacionales con Codex
  - continuidad de thread
  - historial de turns y runs
  - publish a PR
  - sync de PR
  - cierre fuerte con reconciliación final

Referencias:

- `docs/worksession-phase1.md`: superficie conversacional `WorkSession`
- `docs/worksession-target-flow.md`: ruta objetivo de `WorkSession` como flujo completo de trabajo
- `docs/roadmap.md`: estado consolidado y gaps reales abiertos

## Arquitectura funcional actual

### Core `WorkSession`

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
- `POST /api/sessions/{sessionId}/turns/conversation-view`
- `GET /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/runs`
- `POST /api/sessions/{sessionId}/publish`
- `POST /api/sessions/{sessionId}/publish/conversation-view`
- `POST /api/sessions/{sessionId}/pull-request/sync`
- `POST /api/sessions/{sessionId}/pull-request/sync/conversation-view`
- `POST /api/sessions/{sessionId}/close`
- `POST /api/sessions/{sessionId}/close/conversation-view`
- `GET /api/sessions/{sessionId}/deliverables`
- `GET /api/sessions/{sessionId}/deliverables/approved`
- `GET /api/sessions/{sessionId}/deliverables/price-estimate/approved-summary`
- `GET /api/sessions/{sessionId}/deliverables/types/{type}/history`
- `GET /api/sessions/{sessionId}/deliverables/{deliverableId}`
- `POST /api/sessions/{sessionId}/deliverables/{type}/generate`
- `POST /api/sessions/{sessionId}/deliverables/{deliverableId}/approve`
- `GET /api/projects/{projectId}/approved-price-estimates`
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
- metadatos de delivery persistidos en sesión:
  - `pullRequestUrl`
  - `pullRequestStatus`
  - `finalCommitSha`
  - `publishedAt`
- estados de cierre:
  - `OPEN`
  - `CLOSING`
  - `CLOSED`
- cierre fuerte:
  - bloqueo si hay runs activos
  - bloqueo si hay cambios no publicados
  - bloqueo si la PR no está mergeada
  - vuelta obligatoria a rama principal del proyecto alineada con remoto
  - eliminación de rama local de sesión
  - eliminación de rama remota cuando aplica
- estado persistido de bloqueo de cierre:
  - `closeBlockedState`
  - `closeBlockedReason`
  - `closeBlockedAction`
  - `closeRetryable`
- subsistema de deliverables de sesión:
  - `WORK_TICKET`
  - `WORK_BREAKDOWN`
  - `PRICE_ESTIMATE`
- versionado por tipo de deliverable
- generación explícita por deliverable con snapshot persistido
- aprobación manual de una versión concreta
- `SUPERSEDED` para versiones anteriores regeneradas o reemplazadas
- `PRICE_ESTIMATE` con:
  - Markdown revisable
  - `contentJson` estructurado y validado
  - lectura rápida de pricing aprobado por sesión
  - lectura agregada de pricing aprobado por proyecto
- vistas agregadas para frontend:
  - `WorkSessionViewResponse`
  - `WorkSessionConversationViewResponse`
- snapshot descriptivo de repositorio en `WorkSessionResponse` con:
  - `repoValid`
  - `workingTreeClean`
  - `currentBranch`
  - `runInProgress`

## Overview de proyecto

`GET /api/projects/overview` ya es session-first:

- bloque `workSession` con la sesión canónica del proyecto
  - `OPEN` o `CLOSING` si existe una activa
  - o la más reciente por `lastActivityAt`

El overview ya no expone bloques legacy.

## Deliverables y pricing

El backend ya no está sólo en fase de read model para deliverables. Hoy implementa:

- generación explícita de:
  - `WORK_TICKET`
  - `WORK_BREAKDOWN`
  - `PRICE_ESTIMATE`
- snapshot persistido de evidencia de sesión por versión
- historial por tipo:
  - `GET /api/sessions/{sessionId}/deliverables/types/{type}/history`
- aprobación manual:
  - `POST /api/sessions/{sessionId}/deliverables/{deliverableId}/approve`
- latest approved set:
  - `GET /api/sessions/{sessionId}/deliverables/approved`

`PRICE_ESTIMATE` tiene además una capa estructurada para explotación operativa:

- `contentJson` validado en backend
- resumen aprobado por sesión:
  - `GET /api/sessions/{sessionId}/deliverables/price-estimate/approved-summary`
- lista de pricing aprobado por proyecto:
  - `GET /api/projects/{projectId}/approved-price-estimates`

La UI web ya consume estas superficies para:

- generar deliverables
- revisar versiones
- aprobar versiones
- consultar baseline de pricing aprobado de la sesión
- consultar pricing aprobado histórico del proyecto

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
