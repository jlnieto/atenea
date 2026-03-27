# AGENTS

## Propósito

Atenea es un backend que orquesta trabajo sobre repositorios registrados.

Hoy expone dos modelos reales:

- legacy:
  - `Project`
  - `Task`
  - `TaskExecution`
- nuevo modelo conversacional:
  - `WorkSession`
  - `SessionTurn`
  - `AgentRun`

## Mapa conceptual mínimo

- `Project` identifica un repositorio operable por Atenea
- `Task` y `TaskExecution` forman el flujo legacy centrado en branch por tarea
- `WorkSession` es la unidad conversacional nueva
- `SessionTurn` persiste historial visible de conversación
- `AgentRun` persiste una ejecución concreta de Codex dentro de una sesión

## Legacy vs nuevo modelo

No asumas que uno sustituyó completamente al otro.

Estado actual:

- `Task` / `TaskExecution` sigue implementado y operativo
- `WorkSession` / `SessionTurn` / `AgentRun` también está implementado y operativo
- el producto está en coexistencia, no en migración cerrada

## Fuentes de verdad recomendadas

Orden de prioridad:

1. código
2. tests
3. migraciones de base de datos
4. documentación actualizada del repo

Referencias útiles:

- `README.md`
- `docs/worksession-phase1.md`
- `docs/roadmap.md`
- `docs/task-branch-workflow.md`

## Scripts operativos

Para este repo, los comandos operativos canónicos viven en `scripts/`.

Reglas para agentes/Codex:

- no ejecutes `./mvnw` directamente desde el host para tests, build o run
- usa siempre los scripts de `scripts/` como superficie operativa preferida
- para pruebas, usa por defecto `./scripts/test.sh`
- usa `./scripts/test.sh` también cuando necesites pasar filtros de Maven, por ejemplo:
  - `./scripts/test.sh -Dtest=SessionTurnControllerTest test`
- si necesitas arrancar la app local de desarrollo, usa `./scripts/run.sh`
- si necesitas una shell dentro del contenedor de desarrollo, usa `./scripts/shell.sh`
- si necesitas compilar el jar, usa `./scripts/build.sh`
- si necesitas logs de servicios auxiliares, usa `./scripts/logs-db.sh` o `./scripts/logs-codex.sh`
- si necesitas bajar el stack de desarrollo, usa `./scripts/down.sh`

Resumen de scripts:

- `./scripts/test.sh`: ejecuta `./mvnw test` dentro del contenedor `atenea-dev`; no requiere Java instalado en el host
- `./scripts/build.sh`: ejecuta `./mvnw clean package` dentro del contenedor `atenea-dev`
- `./scripts/run.sh`: levanta `db` y `codex-app-server`, y arranca Spring Boot dentro de `atenea-dev`
- `./scripts/shell.sh`: abre una shell Bash dentro de `atenea-dev` con el repo montado
- `./scripts/logs-db.sh`: sigue logs de PostgreSQL
- `./scripts/logs-codex.sh`: sigue logs de `codex-app-server`
- `./scripts/down.sh`: baja el stack definido en `docker-compose.dev.yml`

## Reglas para trabajar en este repo

- no inventes funcionalidades no visibles en código o tests
- no trates documentación antigua como fuente superior al código
- no mezcles `TaskExecution` y `WorkSession` en una misma solución sin revisar primero el flujo real
- antes de asumir que algo es legacy o canónico, verifica controladores, servicios y tests
- cuando haya conflicto entre documentación antigua y código, prevalecen código y tests
- cuando necesites ejecutar pruebas o build, prioriza `./scripts/test.sh` y el resto de scripts de `scripts/` sobre invocaciones directas a `mvnw`

## Advertencias concretas

- `WorkSession` ya implementa más que persistencia y snapshot: revisa endpoints y tests antes de asumir gaps
- `TaskExecution` sigue siendo relevante en overview y en el flujo legacy
- la coexistencia actual es intencionada; no la simplifiques mentalmente a un único modelo sin evidencia
