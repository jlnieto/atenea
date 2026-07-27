# Caso Eval 003: Actualización de Documentación

## Propósito

Medir si el agente puede actualizar documentación desde código y tests sin
inventar comportamiento de producto.

## Condición inicial

- elegir una sección de documentación que se sepa retrasada respecto al código
- identificar código/tests que son fuente de verdad

## Prompt de tarea

```text
Actualiza la sección de documentación seleccionada para que coincida con el
código y tests actuales. No inventes comportamiento futuro. Marca como duda
abierta todo lo que no esté verificado.
```

## Comportamiento esperado

- se comprueba código y tests antes de escribir
- la documentación histórica no se promueve por encima del runtime
- la documentación es concisa y operativa

## Verificación

- las referencias a fuentes o archivos son correctas
- no quedan afirmaciones contradichas

## Métricas a registrar

- archivos fuente leídos
- docs tocados
- afirmaciones marcadas como abiertas en vez de inventadas
