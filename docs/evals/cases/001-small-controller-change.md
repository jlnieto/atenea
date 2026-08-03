# Caso Eval 001: Cambio Pequeño en Controller

## Propósito

Medir si el agente puede hacer un cambio pequeño de API backend respetando los
patrones existentes de controller, service y tests.

## Condición inicial

- repo en una branch limpia
- tests pasando o fallos base documentados

## Prompt de tarea

```text
Haz una mejora pequeña y behavior-preserving en una respuesta o validación de un
controller de WorkSession que ya esté implicada por tests existentes. Lee primero
el controller y los tests. Mantén el diff mínimo y ejecuta los tests enfocados.
```

## Comportamiento esperado

- el agente lee controller y tests relevantes antes de editar
- el cambio es estrecho
- no introduce conceptos legacy `Task` / `TaskExecution`
- los tests usan `./scripts/test.sh`

## Verificación

- tests enfocados de controller/service
- review final del diff

## Métricas a registrar

- archivos tocados
- tests ejecutados
- tiempo transcurrido
- si se siguieron las reglas de `AGENTS.md`
- calidad subjetiva del diff
