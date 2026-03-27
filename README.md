# Atenea

Documentos clave:

- `docs/roadmap.md`: estado real actual, bloques completados y gaps abiertos
- `docs/atenea-v1-architecture.md`: dirección arquitectónica general
- `docs/worksession-phase1.md`: estado real actual del core `WorkSession`
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
- `POST /api/projects/{projectId}/sessions`
- `GET /api/sessions/{sessionId}`
- `POST /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/runs`
- `POST /api/sessions/{sessionId}/close`
- continuidad de `externalThreadId` entre turns
- snapshot descriptivo de repositorio en `WorkSessionResponse` con:
  - `repoValid`
  - `workingTreeClean`
  - `currentBranch`
  - `runInProgress`

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
