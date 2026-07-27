# Diario de Aprendizaje

Este es el diario operativo para aprender agentes IA a través de Atenea.

Reglas:

- cada entrada debe terminar con un `Siguiente paso exacto`
- prioriza hechos verificados sobre impresiones
- enlaza el código, tests o documentación que soporta cada conclusión
- si una afirmación no está verificada, márcala como duda abierta
- mantén las entradas útiles para retomar el trabajo al día siguiente

## Plantilla

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

## 2026-06-20

### Objetivo

Crear un sistema durable de aprendizaje y construcción para ganar competencia
profunda en agentes IA mientras Atenea evoluciona desde evidencia, no desde
ansiedad por herramientas.

### Fuentes leídas

- `README.md`
- `docs/atenea-core.md`
- `docs/worksession-phase1.md`
- `docs/worksession-target-flow.md`
- `docs/roadmap.md`
- árbol actual de código bajo `src/main/java/com/atenea`
- árbol actual de tests bajo `src/test/java/com/atenea`

### Qué aprendí

Atenea ya tiene un backend session-first sustancial. El modelo actual no es sólo
persistencia alrededor de Codex. Incluye `Project`, `CoreCommand`,
`WorkSession`, `SessionTurn`, `AgentRun`, ownership de branch por sesión,
ejecución con Codex App Server, publish/sync de PR, close/reconcile, superficies
mobile y billing/deliverables.

El siguiente movimiento correcto no es perseguir otro runtime de agente de
inmediato. El siguiente movimiento correcto es hacer que aprendizaje, mapas de
arquitectura y evals de agentes formen parte del repositorio.

### Aplicado a Atenea

Se creó una estructura de aprendizaje alrededor de tres pistas:

- plan de aprendizaje y diario
- mapas de arquitectura
- evals de coding agents

### Artefacto cambiado

- `docs/learning/ai-agent-mastery-plan.md`
- `docs/learning/daily-log.md`
- `docs/learning/concepts.md`
- `docs/learning/decisions.md`
- `docs/architecture/current-system-map.md`
- `docs/architecture/worksession-lifecycle.md`
- `docs/architecture/agentrun-lifecycle.md`
- `docs/evals/coding-agent-eval-plan.md`
- primeras plantillas de casos bajo `docs/evals/cases/`

### Dudas abiertas

- ¿Cuáles deben ser los primeros modos concretos de ejecución de `AgentRun` en
  código?
- ¿Debe persistirse el modo de `AgentRun` antes de añadir un segundo backend?
- ¿Qué eventos de Codex App Server son suficientemente importantes para
  persistirse más allá del summary final?

### Siguiente paso exacto

Leer `WorkSessionController`, `WorkSessionService`, `SessionBranchService` y
`WorkSessionServiceTest`, y después actualizar
`docs/architecture/worksession-lifecycle.md` con cualquier detalle que falte en
el mapa actual.
