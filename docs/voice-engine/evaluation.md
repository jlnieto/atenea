# Evaluation

El motor de voz solo se considera valido si permite trabajar realmente sin mirar la pantalla.

## Objetivos medibles

Calidad conversacional:

- Atenea entiende comandos naturales con prefijo `Atenea`.
- Atenea mantiene foco operativo entre turnos.
- Atenea responde `donde estas` con contexto exacto.
- Atenea resume antes de leer detalle.
- Atenea puede continuar tras una aclaracion.
- Atenea guarda notas sin perder el punto de lectura.

Calidad de audio:

- voz femenina humana;
- espanol de Espana;
- tono cercano;
- pronunciacion natural de nombres de proyectos;
- no suena robotica ni plana;
- no fatiga en respuestas largas.

Operacion:

- acciones sensibles piden confirmacion;
- notas enviadas como prompt se limpian de activas;
- historico conserva notas enviadas;
- cambios de foco son explicitos.

## Pruebas de aceptacion

### WorkSession larga

Contexto:

- foco en proyecto `fomasys`;
- Codex ha respondido con una respuesta larga.

Prueba:

```text
Atenea, dime el siguiente paso.
```

Esperado:

- Atenea resume primero;
- pregunta si quieres detalle;
- al pedir detalle, lee por segmentos;
- si el usuario interrumpe para preguntar por el segundo punto, Atenea aclara y puede continuar.

### Nota durante lectura

Prueba:

```text
Atenea, apunta revisar permisos en deploy.
```

mientras Atenea esta hablando.

Esperado:

- pausa o reduce la lectura;
- guarda nota;
- confirma brevemente;
- continua por donde iba.

### Enviar notas como prompt

Prueba:

```text
Atenea, manda las notas como prompt y pide que lo priorice.
```

Esperado:

- compone prompt con notas activas;
- pide confirmacion si puede ejecutar acciones;
- envia al foco actual;
- limpia notas activas;
- mantiene historico.

### Cambio a comunicaciones

Contexto:

- foco en `development/fomasys`.

Prueba:

```text
Atenea, revisa el email.
```

Esperado:

- cambia foco a `communications`;
- resume bandeja;
- permite preguntar por emails importantes;
- permite crear tarea o preparar respuesta.

### Operacion sensible

Prueba:

```text
Atenea, recupera Apache en el dedicado.
```

Esperado:

- Atenea explica accion;
- pide confirmacion;
- no ejecuta hasta confirmacion;
- despues resume resultado operativo.

## Escenarios de prueba real

1. En casa con silencio.
2. Caminando por la calle.
3. En Uber o coche con ruido.
4. Con auriculares Bluetooth.
5. Respuesta larga de Codex de mas de 5 minutos.
6. Interrupciones repetidas.
7. Perdida breve de red.
8. Cambio de foco development -> communications -> development.

## Evaluacion de providers

Comparar al menos:

- OpenAI Realtime como motor speech-to-speech;
- OpenAI para comprension + ElevenLabs para voz;
- transcripcion + TTS como modo separado, no como fallback silencioso.

Metricas:

- naturalidad de voz;
- latencia hasta primera palabra;
- calidad de interrupciones;
- estabilidad de sesion larga;
- coste aproximado por hora de uso;
- calidad con ruido;
- facilidad de integracion Android.

Decision actual:

La primera prioridad es voz femenina ultra humana. La latencia se mide y se optimiza despues.
