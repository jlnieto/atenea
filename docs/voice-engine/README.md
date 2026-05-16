# Atenea Voice Engine

Este bloque define el objetivo, arquitectura y contrato de producto para convertir Atenea en una interfaz operativa por voz de alto nivel.

El objetivo no es anadir una grabadora a la app. El objetivo es construir una capa central reutilizable para hablar con Atenea de forma natural, con voz femenina humana, foco operativo persistente, interrupciones, notas temporales y acciones seguras.

## Decisiones de producto fijadas

- Atenea habla de tu, con tono cercano y profesional.
- Idioma principal: espanol de Espana.
- Para respuestas largas, Atenea da primero resumen y pregunta si quieres detalle.
- Las notas de voz guardan solo transcripcion, no audio original.
- Las notas son globales, no quedan atadas obligatoriamente a un proyecto.
- Si el usuario cambia a comunicaciones, Atenea cambia el foco a comunicaciones.
- La palabra de activacion obligatoria es `Atenea`.
- Se acepta usar APIs externas si aportan maxima calidad.
- Atenea no inicia conversaciones sola: responde cuando el usuario pregunta.
- Prioridad inicial: voz femenina ultra humana. La latencia se evaluara despues con datos reales.

## Documentos

- `vision.md`: vision de producto, principios y alcance.
- `conversation-model.md`: foco operativo, estados, interrupciones, notas y cursor de lectura.
- `architecture.md`: arquitectura Android/backend/providers y persistencia.
- `provider-strategy.md`: estrategia de proveedores de voz y fuentes oficiales.
- `native-runtime-architecture.md`: arquitectura nativa definitiva para manos libres real.
- `webrtc-only-migration.md`: plan de retirada de WebSocket movil y migracion a WebRTC-only.
- `commands.md`: comandos naturales iniciales.
- `evaluation.md`: criterios de calidad y pruebas de aceptacion.
- `implementation-plan.md`: plan de implementacion por fases.

## Estado actual

La app Android tiene un runtime nativo separado en `:voice-runtime`.

Estado implementado en Android nativo:

- servicio foreground de microfono;
- diagnostico de ruta de audio, volumen de canal y estado Realtime;
- sesion Realtime con token efimero emitido por backend;
- transporte Realtime WebRTC desde Android;
- VAD semantico del proveedor;
- corte de respuesta por barge-in.
- control dedicado de ruta/salida de audio en `VoiceAudioRouteController`;
- seleccion preferente de Bluetooth cuando Android lo expone como dispositivo de comunicacion;
- volumen de voz aplicado tanto al track remoto WebRTC como al canal Android `STREAM_VOICE_CALL`;
- seleccion de voz Realtime y velocidad desde la pantalla de Voz;
- notas siempre en modo bloque y cierre explicito con `Atenea, fin`;
- cursor de lectura persistido en `voice_focus.playback`;
- lectura por segmentos con comandos de continuar, repetir, anterior, siguiente y salto directo.

Este estado deja de ser spike WebSocket y pasa a ser la base WebRTC-only de producto. Sigue pendiente evolucionar el sideband backend-proveedor para herramientas directas y contexto operativo mas profundo.

Decision actual:

- el runtime movil final sera OpenAI Realtime por WebRTC;
- WebSocket se elimina completamente del camino movil de voz;
- no habra fallback WebSocket en Android;
- sideband backend-proveedor se permite como capa de control avanzada;
- no se mezclaran motores de salida de voz dentro de la misma sesion.

Estado no cerrado todavia:

- wake word local `Atenea`;
- uso con pantalla apagada;
- sideband backend para herramientas y contexto real;
- tool calls Realtime conectados directamente a dominios Atenea.

## Principio central

Atenea Voice Engine debe mantener siempre esta pregunta respondida:

```text
Donde esta Atenea, que esta haciendo, que esta leyendo, que notas tiene pendientes y que puede hacer ahora de forma segura.
```

Sin esa respuesta, la voz se vuelve una entrada fragil. Con esa respuesta, Atenea puede operar como una capa profesional manos libres.
