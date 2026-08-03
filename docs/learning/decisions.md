# Decisiones de Diseño sobre Agentes IA

Este archivo registra decisiones que dan forma a cómo Atenea usa agentes IA.
Mantén las entradas cortas y falsables.

## Decisión 001. Atenea no es un chat fino sobre Codex

Estado: aceptada

Fecha: 2026-06-20

Decisión:

Atenea debe tratarse como una capa de producto y orquestación alrededor del
trabajo sobre repositorios. Codex App Server es el runtime de coding agent
actual, no todo el límite del producto.

Razón:

El backend ya posee `Project`, `CoreCommand`, `WorkSession`, `SessionTurn`,
`AgentRun`, branches de sesión, publish/sync de pull requests, deliverables,
lecturas de billing y close/reconcile.

Consecuencia:

El trabajo futuro debe fortalecer el workflow y la capa de evaluación antes de
reemplazar el runtime.

## Decisión 002. Preferir modos de ejecución antes que proliferación de agentes

Estado: aceptada

Fecha: 2026-06-20

Decisión:

La siguiente abstracción a explorar es el modo de ejecución de `AgentRun`, no un
conjunto grande de agentes con nombre.

Modos candidatos:

- `PLAN`
- `IMPLEMENT`
- `REVIEW`
- `CI_FIX`
- `DOCS`
- `EXPLAIN`

Razón:

Los modos mapean directamente con permisos, sandbox, prompts, salida esperada y
validación. Son más fáciles de evaluar que un sistema multiagente definido de
forma laxa.

Consecuencia:

Antes de añadir un segundo runtime o una topología de subagentes propia, Atenea
debe definir qué puede hacer cada modo y cómo se mide el éxito.

## Decisión 003. Las acciones de ciclo de vida de producto siguen deterministas

Estado: aceptada

Fecha: 2026-06-20

Decisión:

Publish, sync de pull request, close/reconcile, transiciones de billing y gates
de confirmación deben seguir siendo workflows deterministas del backend.

Razón:

Estas operaciones protegen estado de repositorio y negocio. Necesitan validación,
repetibilidad, auditabilidad y estados bloqueados claros.

Consecuencia:

La IA puede generar resúmenes, diagnosticar fallos o proponer siguientes
acciones, pero el backend debe ejecutar y validar las transiciones de ciclo de
vida.

## Decisión 004. Los cambios de runtime requieren evidencia de evals

Estado: aceptada

Fecha: 2026-06-20

Decisión:

Codex App Server, Codex SDK, OpenCode y enfoques con API directa deben
compararse mediante casos de evaluación de Atenea antes de cualquier decisión de
migración.

Razón:

Artículos y demos optimizan para restricciones distintas. Atenea necesita
evidencia sobre sus propias tareas, reglas de repo, flujos de operador y modos
de fallo.

Consecuencia:

La pista `docs/evals/` es obligatoria antes de trabajo de reemplazo de runtime.
