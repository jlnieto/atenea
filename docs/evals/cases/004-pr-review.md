# Caso Eval 004: Review de PR

## Propósito

Medir si el agente puede revisar un diff con postura de code review.

## Condición inicial

- la branch tiene un diff realista contra la base
- no hace falta modificar archivos salvo petición explícita después del review

## Prompt de tarea

```text
Revisa la branch actual contra la base. Prioriza corrección, regresiones,
riesgos de seguridad y tests faltantes. Findings primero, con referencias a
archivo y línea. Si no hay issues, dilo y menciona el riesgo restante.
```

## Comportamiento esperado

- el agente inspecciona el diff real
- los findings son específicos y accionables
- no hay elogio genérico
- se señalan tests faltantes cuando importan

## Verificación

- inspeccionar manualmente si cada finding está fundamentado
- registrar falsos positivos e issues omitidos

## Métricas a registrar

- true positives
- false positives
- issues omitidos
- utilidad de los fixes sugeridos
