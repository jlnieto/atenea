# Plan de Evaluación de Coding Agents

## Propósito

Este plan define cómo Atenea debe comparar runtimes de coding agents, prompts y
modos de ejecución usando trabajo real del repositorio.

El objetivo es decidir desde evidencia, no desde demos o artículos.

## Candidatos

Candidato actual:

- Codex App Server

Candidatos futuros posibles:

- Codex SDK
- OpenCode
- workflow con API directa de OpenAI / Agents SDK

No añadas un candidato hasta que exista una hipótesis concreta de integración y
al menos un caso de eval donde pueda superar al runtime actual.

## Dimensiones de evaluación

Registra esto para cada run:

- runtime
- modelo, si se conoce
- modo de ejecución
- prompt
- estado del repositorio antes del run
- completó o no
- tests ejecutados
- tests pasaron
- resumen del diff
- número de turns
- tiempo transcurrido
- intervenciones del operador
- violaciones de instrucciones
- limpieza del repo después del run
- juicio final

Opcional cuando esté disponible:

- uso de tokens
- estimación de coste
- número de eventos streamed
- external thread id
- external turn id

## Plantilla estándar de resultado

Crea archivos de resultado bajo `docs/evals/results/`.

```markdown
# Resultado: CASE_ID - RUNTIME - YYYY-MM-DD

## Preparación

## Prompt usado

## Runtime

## Resultado

## Verificación

## Métricas

## Calidad del diff

## Adherencia a instrucciones

## Seguimiento
```

## Casos iniciales

Usa primero estos casos:

- `docs/evals/cases/001-small-controller-change.md`
- `docs/evals/cases/002-failing-test-fix.md`
- `docs/evals/cases/003-docs-update.md`
- `docs/evals/cases/004-pr-review.md`
- `docs/evals/cases/005-agentrun-mode-design.md`

## Reglas de evaluación

- Ejecuta contra un estado de repo limpio y conocido cuando sea posible.
- No compares candidatos en tareas distintas y lo llames evidencia.
- Registra fallos. Un fallo con buenos diagnósticos es valioso.
- Mantén prompts estables cuando compares runtimes.
- Prefiere casos pequeños y repetibles antes que tareas autónomas amplias.
- Usa `./scripts/test.sh` para tests en este repo.

## Qué cuenta como runtime mejor

Un runtime es mejor sólo si mejora una o más dimensiones:

- tasa de finalización
- corrección
- tasa de tests en verde
- calidad del diff
- adherencia a instrucciones
- observabilidad
- latencia
- coste
- simplicidad de integración
- control del operador

Sin una mejora medida, conserva el runtime actual.
