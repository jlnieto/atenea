# Atenea

Documentos clave:

- `docs/roadmap.md`: estado de versión, fases y roadmap de producto
- `docs/atenea-v1-architecture.md`: dirección arquitectónica objetivo de V1
- `docs/worksession-phase1.md`: estado real de la Fase 1 del nuevo core `WorkSession`
- `docs/task-branch-workflow.md`: política de ramas por tarea, review/PR y reglas actuales de cierre

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

## Stack de desarrollo

`docker-compose.dev.yml` levanta:

- `db`: PostgreSQL para desarrollo
- `codex-app-server`: servicio `codex app-server` aislado en Docker
- `atenea-dev`: contenedor con `eclipse-temurin:21-jdk`, workspace montado y caché Maven persistente

## Notas

- El host no necesita Java para desarrollar Atenea en este VPS.
- Los comandos Maven deben ejecutarse a través de los scripts de `scripts/`.
- En desarrollo, `atenea-dev` y `codex-app-server` comparten el workspace canónico del host:
  - `/srv/atenea/workspace/repos` montado en `/workspace/repos`
  - `/srv/atenea/workspace/context` montado en `/workspace/context` en solo lectura
  - `/srv/atenea/workspace/codex-home` montado en `/workspace/codex-home`
- El workspace root es configuración de plataforma mediante `ATENEA_WORKSPACE_ROOT` y en este stack vale `/workspace/repos`.
- La estructura esperada del workspace es `/workspace/repos/internal/...` y `/workspace/repos/clients/<client>/...`.
- En desarrollo, el `repoPath` correcto para Atenea es `/workspace/repos/internal/atenea`.
- `repoPath` ya no es opcional. Debe:
  - ser absoluto
  - estar dentro del `workspaceRoot` configurado en plataforma
  - existir
  - ser un directorio
  - contener `.git`
- Estado actual del backend:
  - sigue existiendo el flujo legado centrado en `Task` / `TaskExecution`
  - ya existe el nuevo slice centrado en `WorkSession`
- Flujo legado todavía implementado:
  - branch identity por tarea
  - launch seguro con inspección Git
  - validación previa de `launch` basada en la `description` de la tarea
  - `relaunch` sobre la misma task branch
  - paso a `REVIEW_PENDING`
  - `abandon` para liberar task branches vacías o no útiles
  - metadatos de pull request en la tarea
  - creación y sincronización básica de pull requests con GitHub
  - resultado explícito de revisión en la tarea
  - visibilidad operativa derivada con `projectBlocked`, `hasReviewableChanges`, `lastExecutionFailed`, `launchReady`, `launchReadinessReason`, `blockingReason`, `nextAction` y `recoveryAction`
  - cierre de branch para liberar el proyecto
- Nuevo slice `WorkSession` ya implementado:
  - persistencia de `work_session`, `session_turn` y `agent_run`
  - una sola sesión `OPEN` por proyecto
  - una sola run `RUNNING` por sesión
  - `POST /api/projects/{projectId}/sessions`
  - `GET /api/sessions/{sessionId}`
  - apertura de sesión con `baseBranch` desde request o branch actual del repo
  - `repoState` descriptivo en `WorkSessionResponse` con:
    - `repoValid`
    - `workingTreeClean`
    - `currentBranch`
    - `runInProgress`
  - `runInProgress` ya forma parte del snapshot, pero el flujo API nuevo de creación/listado de `AgentRun` dentro de `WorkSession` todavía no existe
- Pendiente en el nuevo core `WorkSession`:
  - turnos con Codex
  - continuidad real de thread
  - listado de turns
  - listado de runs
  - cierre de sesión
- Errores actuales del slice `WorkSession`:
  - `404` si no existe el `Project` al abrir o la `WorkSession` al consultar
  - `409` si ya existe una sesión `OPEN` para el proyecto
  - `400` si falla la validación del request o el `repoPath` del proyecto es inválido
  - `422` si el `repoPath` es válido pero el repositorio no es operativo para abrir la sesión
- Decisión arquitectónica ya tomada:
  - el core futuro debe girar alrededor de `WorkSession`, no `Task`
  - el flujo legado `Task` sigue existiendo, pero no debe considerarse el núcleo del producto futuro
  - el nuevo frontend no debe crecer sobre endpoints de `Task`
- `codex app-server` usa un `HOME` dedicado en `/workspace/codex-home`.
- La base de desarrollo usa por defecto:
  - base de datos: `atenea`
  - usuario: `atenea`
  - password: `atenea`
  - puerto host: `5434`
- La app expone por defecto el puerto `8081`.
