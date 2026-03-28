# AGENTS

## Propósito

Atenea es un backend que orquesta trabajo sobre repositorios registrados.

El modelo activo del producto y del backend es:

- `Project`
- `WorkSession`
- `SessionTurn`
- `AgentRun`

El antiguo flujo `Task` / `TaskExecution` ya fue retirado del código runtime, de la API pública y de la base de datos. Su documentación sólo debe tratarse como referencia histórica.

## Mapa conceptual mínimo

- `Project` identifica un repositorio operable por Atenea
- `WorkSession` es la unidad canónica de trabajo conversacional y de delivery
- `SessionTurn` persiste el historial visible de conversación de una sesión
- `AgentRun` persiste una ejecución concreta de Codex dentro de una sesión

## Estado actual del sistema

Hoy el backend implementa y expone:

- apertura o resolución de `WorkSession`
- branch de trabajo propio por sesión
- turnos conversacionales con Codex
- continuidad de `externalThreadId` entre turns
- historial de turns y runs
- publish a pull request
- sincronización de estado de pull request
- cierre fuerte con reconciliación del repositorio
- overview de proyecto session-first

## Fuentes de verdad recomendadas

Orden de prioridad:

1. código
2. tests
3. migraciones de base de datos
4. documentación actualizada del repo

Referencias útiles:

- `README.md`
- `docs/worksession-phase1.md`
- `docs/worksession-target-flow.md`
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
- no reintroduzcas `Task` / `TaskExecution` al razonar sobre el flujo actual salvo como contexto histórico
- antes de asumir que algo es canónico, verifica controladores, servicios y tests
- cuando haya conflicto entre documentación y código, prevalecen código y tests
- cuando necesites ejecutar pruebas o build, prioriza `./scripts/test.sh` y el resto de scripts de `scripts/` sobre invocaciones directas a `mvnw`

## Advertencias concretas

- `WorkSession` ya implementa más que persistencia y snapshot: revisa endpoints y tests antes de asumir gaps
- `GET /api/projects/overview` ya no expone bloque `legacy`; es session-first
- `docs/task-branch-workflow.md` es referencia histórica, no contrato funcional actual
