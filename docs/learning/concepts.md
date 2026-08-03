# Conceptos de Agentes IA Aplicados a Atenea

Este archivo es un glosario vivo. Cada concepto sólo debe crecer cuando esté
unido a una decisión, eval o implementación concreta de Atenea.

## Agente

Definición de trabajo:

```text
Agente = modelo + instrucciones + herramientas + contexto + permisos + bucle de ejecución
```

Aplicación a Atenea:

- Codex App Server aporta hoy la mayor parte del runtime de coding agent.
- Atenea aporta el workflow de producto: sesión, branch, historial de runs, PR y
  close.
- Los futuros modos de Atenea deberían configurar el runtime antes que
  reemplazarlo prematuramente.

## Runtime

Un runtime es el sistema que permite a un modelo trabajar mediante herramientas.

Ejemplos:

- Codex App Server
- Codex SDK
- OpenCode
- API directa más un bucle de tools propio

Aplicación a Atenea:

- el runtime actual es Codex App Server
- Atenea debería mantener su lenguaje de dominio suficientemente independiente
  para evaluar otros runtimes más adelante

## Tool

Una tool es una superficie de acción disponible para el agente u orquestador.

En Atenea importan dos categorías:

```text
Herramientas de programación
  leer archivos, editar archivos, ejecutar tests, inspeccionar git

Herramientas de producto
  resolver sesión, publicar PR, sincronizar PR, cerrar sesión, marcar billing
```

Regla de diseño:

- las herramientas de programación pueden ser amplias porque programar requiere
  exploración
- las herramientas de producto deben ser tipadas, validadas y auditadas

## Contexto

Contexto es la información proporcionada al modelo para un run.

Aplicación a Atenea:

- las reglas durables del repo viven en `AGENTS.md`
- el estado de sesión vive en `WorkSession`
- el historial conversacional vive en `SessionTurn`
- la traza de ejecución vive en `AgentRun`
- la realidad actual de sistema está documentada en `README.md`,
  `docs/roadmap.md` y los mapas de arquitectura

Duda abierta:

- ¿qué datos de sesión y proyecto debería inyectar Atenea explícitamente en cada
  turn de Codex en vez de depender del descubrimiento del repo?

## Sandbox

Sandbox es el límite de permisos alrededor de un run.

Política candidata:

```text
PLAN       read-only
REVIEW     read-only
EXPLAIN    read-only
DOCS       workspace-write, limitado por instrucción a documentación
IMPLEMENT  workspace-write
CI_FIX     workspace-write
PUBLISH    código de servicio determinista
CLOSE      código de servicio determinista
```

Duda abierta:

- ¿cuánta parte de esto debería persistirse como metadata de `AgentRun`?

## Evaluación

Evaluación es cómo Atenea decide si un cambio de agente/runtime mejora de verdad.

Métricas mínimas:

- completó o no
- tests pasaron o no
- tamaño del diff
- número de turns
- tiempo transcurrido
- intervención del operador
- violaciones de instrucciones
- limpieza del repo tras el run
- notas subjetivas de calidad

Los artefactos de evaluación viven en:

- `docs/evals/coding-agent-eval-plan.md`
- `docs/evals/cases/`
- `docs/evals/results/`

## Workflow determinista

Algunos comportamientos deben seguir siendo backend ordinario.

Ejemplos:

- validación de proyecto
- reglas de branch
- persistencia
- publish/sync de PR
- close/reconcile
- transiciones de billing
- confirmaciones

Regla:

```text
El modelo puede ayudar a decidir e implementar. Atenea debe validar y poseer el estado.
```

## Multiagente

La ejecución multiagente es útil cuando el trabajo es paralelo o naturalmente
separado:

- exploración por subsistema
- review por categoría de riesgo
- jueces independientes de eval
- planificación e implementación separadas por permisos

No empieces aquí. Primero define modos de ejecución y evals.
