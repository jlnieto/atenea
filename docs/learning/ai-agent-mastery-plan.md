# Plan de Maestría en Agentes IA para Atenea

## Propósito

Este documento define el plan vivo de aprendizaje y construcción para usar
Atenea como laboratorio práctico de IA avanzada, coding agents y sistemas
agentic.

El objetivo no es coleccionar frameworks. El objetivo es construir criterio
experto:

- cuándo usar IA
- cuándo no usar IA
- cómo diseñar herramientas, permisos y evaluaciones para agentes
- cómo mantener flujos deterministas donde importan las garantías
- cómo hacer Atenea más potente sin perder control del sistema

## Principio operativo

Cada sesión de trabajo debe cerrar el mismo ciclo:

1. Aprender un concepto.
2. Aplicarlo a Atenea.
3. Registrar qué cambió en comprensión, código, documentación o evaluación.
4. Escribir el siguiente paso exacto.

No trates lectura, programación y notas como pistas separadas. Un buen día deja
al menos un artefacto durable:

- una nota de arquitectura mejorada
- una mejora pequeña de producto o código
- un caso de evaluación o resultado de evaluación
- una decisión registrada
- una duda abierta formulada con precisión

## Modelo mental

Atenea debe entenderse como una capa de producto y orquestación por encima de
runtimes de coding agents.

```text
Modelo
  gpt, claude, qwen, deepseek, modelos locales/abiertos

Runtime de agente
  Codex, OpenCode, Claude Code, OpenHands, bucle de tools propio

Orquestador
  Atenea Core, Agents SDK, LangGraph, motor de workflow propio

Workflow de producto
  Project, WorkSession, SessionTurn, AgentRun, branch, PR, billing, close
```

Codex App Server es el runtime de coding agent actual. Atenea no es sólo un chat
client para Codex. Atenea es dueña del workflow de producto alrededor del trabajo
en repositorios: sesión, rama, historial de turnos, historial de runs, delivery,
sync de PR, cierre y reconciliación.

## Fuentes de verdad actuales

Usa este orden al estudiar Atenea:

1. Código.
2. Tests.
3. Migraciones de base de datos.
4. Documentación actual.
5. Documentación histórica sólo como contexto.

Documentos actuales principales:

- `README.md`
- `docs/atenea-core.md`
- `docs/worksession-phase1.md`
- `docs/worksession-target-flow.md`
- `docs/roadmap.md`
- `docs/architecture/current-system-map.md`
- `docs/architecture/worksession-lifecycle.md`
- `docs/architecture/agentrun-lifecycle.md`

Referencia histórica:

- `docs/task-branch-workflow.md`

No reintroduzcas `Task` ni `TaskExecution` en el modelo runtime.

## Capas de aprendizaje

### Capa 1. Recuperar el mapa del sistema

Objetivo: volver a tener control preciso sobre lo que Atenea ya implementa.

Estudiar:

- `Project`
- `CoreCommand`
- `WorkSession`
- `SessionTurn`
- `AgentRun`
- ownership de ramas
- integración con Codex App Server
- publish a PR
- sync de pull request
- cierre y reconciliación del repositorio
- superficies de operador mobile/core

Artefactos:

- `docs/architecture/current-system-map.md`
- `docs/architecture/worksession-lifecycle.md`
- `docs/architecture/agentrun-lifecycle.md`
- entradas en `docs/learning/daily-log.md`

Criterios de salida:

- puedes explicar el happy path completo desde prompt del operador hasta PR
- puedes explicar qué ocurre cuando el cierre queda bloqueado
- puedes explicar la diferencia entre `externalThreadId` y `externalTurnId`
- puedes nombrar los servicios y tests que prueban el flujo

### Capa 2. Entender los agentes como sistemas

Objetivo: interiorizar la estructura real de un agente.

Fórmula base:

```text
Agente = modelo + instrucciones + herramientas + contexto + permisos + bucle de ejecución
```

Conceptos a estudiar:

- ventanas de contexto y selección de contexto
- prompts e instrucciones durables
- tool calling
- bucles de agente: planificar, actuar, observar, corregir
- estado y memoria
- sandboxing y aprobaciones
- salidas estructuradas
- trazas
- evaluaciones
- coste y latencia
- modos de fallo

Aplica cada concepto a Atenea con esta pregunta:

```text
¿Qué debe poseer Atenea y qué debe poseer el runtime de coding agent?
```

### Capa 3. Diseñar modos de ejecución antes que muchos agentes

Objetivo: evitar complejidad multiagente prematura.

Define modos antes de crear nuevos tipos de agentes:

```text
PLAN
  read-only, sin escrituras, produce un plan concreto

IMPLEMENT
  workspace-write, puede editar código y ejecutar tests

REVIEW
  read-only, revisa diff buscando corrección, regresiones y tests faltantes

CI_FIX
  centrado en checks fallidos, logs y fixes mínimos

DOCS
  actualiza documentación sin cambiar runtime salvo petición explícita

EXPLAIN
  enseña el sistema o un camino de código sin cambiar archivos

PUBLISH
  debe ser principalmente código de producto determinista

CLOSE
  debe ser reconciliación determinista con estados bloqueados claros
```

Para cada modo define:

- herramientas permitidas
- sandbox
- modelo
- prompts/instrucciones
- contexto requerido
- criterios de éxito
- validación posterior al run

### Capa 4. Separar workflow determinista de trabajo IA

Objetivo: saber dónde no usar IA.

La IA es apropiada para:

- explorar un codebase
- planificar un cambio
- editar código
- interpretar fallos de tests
- revisar un diff
- explicar tradeoffs
- redactar resúmenes para el operador

El código determinista debe poseer:

- lookup y validación de proyectos
- política de creación y checkout de ramas
- persistencia de runs y turns
- publish flow
- sync de pull request
- close y reconciliación
- transiciones de billing
- permisos y confirmaciones
- historial auditable

Regla:

```text
El agente puede proponer y modificar código. Atenea controla el ciclo de vida.
```

### Capa 5. Evaluar runtimes de agentes con tareas reales

Objetivo: sustituir ansiedad por herramientas por evidencia.

Compara runtimes sólo con casos repetibles:

- Codex App Server
- Codex SDK
- OpenCode, si se añade como experimento
- API directa o Agents SDK, cuando haya un caso real

Usa:

- `docs/evals/coding-agent-eval-plan.md`
- `docs/evals/cases/`
- `docs/evals/results/`

No decidas por artículos o demos. Decide con tareas de Atenea.

### Capa 6. Construir independencia de proveedor deliberadamente

Objetivo: evitar que Atenea dependa accidentalmente de un solo runtime.

Idea objetivo:

```text
AgentBackend
  startRun(...)
  continueRun(...)
  cancelRun(...)
  streamEvents(...)
  getResult(...)
```

La implementación actual puede seguir siendo Codex App Server. El punto es que
el lenguaje del dominio sea suficientemente independiente:

- `WorkSession`
- `SessionTurn`
- `AgentRun`
- modo de ejecución
- sandbox
- external thread id
- external turn id
- eventos

Implementaciones posibles:

- `CodexAppServerBackend`
- `CodexSdkBackend`
- `OpenCodeBackend`
- `DirectAgentsBackend`

Esto es una dirección futura de diseño, no una reescritura inmediata.

## Workflow diario

Cada día de trabajo empieza en `docs/learning/daily-log.md`.

Proceso:

1. Lee el último `Siguiente paso exacto`.
2. Haz sólo esa unidad, salvo que revele claramente un prerrequisito menor.
3. Verifica contra código y tests antes de tratar una afirmación como cierta.
4. Actualiza el mapa, eval o decisión correspondiente.
5. Añade una entrada al diario.
6. Termina con un nuevo `Siguiente paso exacto`.

Forma recomendada de entrada:

```markdown
## YYYY-MM-DD

### Objetivo

### Fuentes leídas

### Qué aprendí

### Aplicado a Atenea

### Artefacto cambiado

### Dudas abiertas

### Siguiente paso exacto
```

## Primera secuencia

Empieza con estas unidades en orden:

1. Leer y verificar el mapa actual del sistema.
2. Trazar `WorkSession` open/resolve desde controller a service y tests.
3. Trazar ejecución de turnos y continuidad con Codex App Server.
4. Trazar estados de `AgentRun` y reconciliación de runs obsoletos.
5. Trazar publish a PR.
6. Trazar sync de pull request.
7. Trazar close y reconciliación.
8. Definir modos candidatos de ejecución para `AgentRun`.
9. Crear los primeros cinco casos de evaluación de coding agents.
10. Ejecutar el primer caso con Codex App Server y registrar el resultado.

Después de estas diez unidades, los siguientes pasos deben salir de la evidencia
recogida en el diario, no de un plan genérico.

## Criterios de salud del plan

Este plan no tiene fecha final. Funciona cuando:

- cada día termina con un paso siguiente concreto
- las notas de arquitectura se mantienen alineadas con el código
- las decisiones sobre agentes/runtimes tienen evidencia de evals
- Atenea gana capacidad sin perder control determinista
- la incertidumbre se convierte en decisiones explícitas o dudas abiertas
