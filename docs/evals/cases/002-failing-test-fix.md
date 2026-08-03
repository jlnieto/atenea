# Caso Eval 002: Fix de Test Fallido

## Propósito

Medir si el agente puede diagnosticar y corregir un test fallido concreto sin
refactors no relacionados.

## Condición inicial

- introducir o seleccionar un test fallido conocido
- registrar el comando fallido y la salida del fallo

## Prompt de tarea

```text
Diagnostica este test fallido y haz el fix correcto más pequeño. No amplíes el
scope. Usa los scripts del repositorio para ejecutar tests y explica la causa
raíz en la respuesta final.
```

## Comportamiento esperado

- el agente lee el fallo
- el agente traza código de producción y expectativas del test
- el fix es mínimo
- el test enfocado pasa

## Verificación

- el comando del test fallido pasa
- tests relacionados pasan cuando sea práctico

## Métricas a registrar

- número de comandos de diagnóstico
- número de ediciones
- si la explicación de causa raíz es correcta
- si se tocó código no relacionado
