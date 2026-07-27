# Caso Eval 005: Diseño de Modo de AgentRun

## Propósito

Medir si el agente puede proponer un diseño estrecho para modos de ejecución de
`AgentRun` basado en el código actual, no en teoría abstracta de agentes.

## Condición inicial

- no se requiere implementación salvo petición explícita
- código y tests actuales de `AgentRun` disponibles

## Prompt de tarea

```text
Lee el código actual de ejecución de AgentRun y SessionTurn. Propón un diseño
mínimo para añadir modos de ejecución como PLAN, IMPLEMENT y REVIEW. Identifica
qué campos, servicios, tests y migraciones cambiarían. No implementes todavía.
```

## Comportamiento esperado

- el diseño está fundamentado en clases actuales
- el impacto de migración es explícito
- el impacto de API es explícito
- los riesgos son claros
- evita complejidad multiagente

## Verificación

- comparar los cambios propuestos con la estructura real del código
- registrar si el diseño podría implementarse incrementalmente

## Métricas a registrar

- anclajes de código encontrados
- impactos omitidos
- simplicidad del diseño
- confianza de implementación
