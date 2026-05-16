# Architecture

Atenea Voice Engine debe ser una capa central, no una funcionalidad aislada de una pantalla.

## Componentes

```text
Android App
  VoiceRuntime
    WebRtcRealtimeClient
    VoiceAudioRouteController
    BargeInController
    VoicePlaybackController
    VoiceSegmenter
    VoiceSessionClient
    VoiceNotesClient
    HeadsetIntegration

Backend Atenea
  VoiceSessionService
  VoiceFocusService
  VoiceTurnService
  VoiceNoteService
  VoiceListeningScriptService
  VoiceCommandRouter
  VoicePromptComposer
  VoiceSafetyService

Providers
  RealtimeVoiceProvider
  TranscriptionProvider
  TextToSpeechProvider
  ListeningScriptProvider
```

## Android

Responsabilidades:

- capturar audio con baja latencia;
- detectar palabra obligatoria `Atenea`;
- gestionar interrupcion mientras Atenea habla;
- reproducir voz;
- mostrar estado minimo de sesion;
- soportar auriculares;
- seleccionar salida Bluetooth cuando Android la expone como dispositivo de comunicacion;
- mostrar salida activa y volumen del canal de voz;
- mantener servicio activo cuando el modo voz este habilitado;
- enviar eventos al backend;
- recibir eventos de texto/audio.

Estados visibles en app:

- escuchando `Atenea`;
- escuchando instruccion;
- pensando;
- hablando;
- pausado;
- nota guardada;
- esperando confirmacion;
- ejecutando accion.

## Backend

El backend es la fuente de verdad de:

- foco operativo;
- historial de voz;
- notas activas;
- confirmaciones;
- routing a dominios;
- composicion de prompts;
- seguridad.

El backend no debe depender de una pantalla concreta.

## Providers

Interfaces internas:

```text
RealtimeVoiceProvider
  startSession
  connectWebRtc
  interrupt
  close

TranscriptionProvider
  transcribeStream
  transcribeClip

TextToSpeechProvider
  synthesize
  stream
  stop

ListeningScriptProvider
  rewriteForListening
  preserveReferences
  segment
```

Estrategia inicial:

- priorizar voz femenina ultra humana;
- evaluar OpenAI Realtime para conversacion natural e interrupciones;
- evaluar ElevenLabs para salida de voz premium;
- permitir arquitectura hibrida: OpenAI para comprension y ElevenLabs para voz.

## Persistencia propuesta

Entidades conceptuales:

```text
VoiceSession
VoiceTurn
VoiceFocusSnapshot
VoicePlaybackSegment
VoiceNote
VoiceNoteSendIntent
VoicePendingConfirmation
```

`VoiceSession`:

- id;
- operador;
- estado;
- provider activo;
- startedAt;
- endedAt.

`VoiceFocusSnapshot`:

- domain;
- projectId;
- workSessionId;
- coreCommandId;
- communicationsContextId;
- operationHostId;
- activity;
- updatedAt.

`VoicePlaybackSegment`:

- voiceSessionId;
- sourceType;
- sourceId;
- segmentIndex;
- title;
- text;
- status.

`VoiceNote`:

- id;
- text;
- capturedAt;
- focusSnapshot;
- status: active, sent, archived;
- consumedByCommandId.

`VoiceNoteSendIntent`:

- id;
- operador;
- destino congelado en el momento de preparar el envio;
- projectId/projectName;
- workSessionId/workSessionTitle;
- noteIds;
- instruction;
- confirmationPrompt;
- status: pending, confirmed, cancelled, expired, failed, sent;
- agentRunId cuando Codex recibe el turno.

Regla de producto:

- Realtime no decide donde se envian las notas;
- Android no consume notas por su cuenta;
- el backend prepara un intent pendiente con destino explicito;
- el usuario confirma o cancela;
- solo al confirmar se crea un `SessionTurn` real en la `WorkSession`;
- solo si ese turno se crea correctamente las notas pasan a `sent`.

## API inicial

Endpoints candidatos:

```text
POST /api/mobile/voice/sessions
GET  /api/mobile/voice/focus
POST /api/mobile/voice/focus
POST /api/mobile/voice/turns
POST /api/mobile/voice/notes
GET  /api/mobile/voice/notes/active
GET  /api/mobile/voice/notes/state
POST /api/mobile/voice/notes/send
POST /api/mobile/voice/notes/send-intents
POST /api/mobile/voice/notes/send-intents/{id}/confirm
POST /api/mobile/voice/notes/send-intents/{id}/cancel
POST /api/mobile/voice/confirmations/{id}/confirm
POST /api/mobile/voice/confirmations/{id}/cancel
```

`POST /api/mobile/voice/notes/send` queda como endpoint heredado para compatibilidad. El camino canonico de Android es `send-intents`.

Para streaming realtime movil:

```text
POST /api/mobile/voice/realtime/session
POST /api/mobile/voice/realtime/calls
```

El endpoint exacto puede ser token efimero o SDP via backend, pero el transporte movil de voz es WebRTC-only. No se define WebSocket como fallback de Android.

## Texto completo vs texto escuchable

La respuesta completa de Codex no debe perderse. Para voz se manejan dos representaciones:

- texto original: respuesta completa, con rutas, URLs, comandos y detalles tecnicos;
- texto escuchable: version redactada para audio, sin inventar datos, con pausas naturales y referencias tecnicas resumidas.

Regla:

- la app puede leer el texto escuchable por defecto;
- el operador debe poder pedir literalidad o detalle tecnico: `lee literal`, `lee los enlaces`, `lee las rutas`, `detalle del segmento tres`;
- la segmentacion opera sobre la version escuchable, pero el origen conserva el texto completo.

La primera implementacion Android incluye `VoiceSegmenter` con transformacion local ligera para URLs, rutas inline, bloques de codigo y comandos. La regla actual es conservadora:

- el texto visible conserva la respuesta original;
- el audio no lee URLs crudas, rutas completas ni comandos largos;
- el audio dice que existe un enlace, ruta, comando o bloque tecnico disponible en pantalla;
- la evolucion profesional debe mover la transformacion a backend mediante `VoiceListeningScriptService`, con proveedor configurable y cache por `CoreCommand` o turno.

## Barge-in

La interrupcion mientras Atenea habla se gestiona en dos fases:

1. Android corta la lectura inmediatamente al recibir `input_audio_buffer.speech_started` si habia salida activa.
2. Cuando llega la transcripcion final, la app decide si habia una orden valida con wake word `Atenea`.

Comportamiento:

- `Atenea, nota`: pausa la lectura, abre bloque de nota y no reanuda hasta `Atenea, fin`.
- `Atenea, para`: pausa y queda en espera.
- `Atenea, continua`: reanuda desde el segmento/cursor guardado.
- ruido o habla sin orden reconocible: se guarda el cursor y la app puede reanudar la lectura.

Este diseno evita esperar al final de la locucion para obedecer una orden, pero mantiene la wake word como barrera contra activaciones accidentales.

## Audio Android

`VoiceAudioRouteController` es la autoridad local de audio:

- solicita `AUDIOFOCUS_GAIN`;
- usa `MODE_IN_COMMUNICATION`;
- en Android 12+ intenta `setCommunicationDevice`;
- en versiones anteriores intenta SCO Bluetooth cuando existe;
- aplica volumen al canal `STREAM_VOICE_CALL`;
- publica etiqueta de ruta para UI y diagnostico.

El control de volumen del track WebRTC se mantiene como ganancia adicional, pero no se considera suficiente por si solo.

## Relacion con Atenea Core

Voice Engine no sustituye a Core.

Voice Engine:

- captura voz;
- mantiene foco;
- clasifica interrupciones;
- compone entradas;
- gestiona notas;
- enruta comandos.

Atenea Core:

- interpreta intencion final;
- ejecuta dominio;
- genera respuesta;
- aplica confirmaciones;
- registra comandos.

## Seguridad

Reglas:

- wake word obligatoria: `Atenea`;
- acciones sensibles requieren confirmacion;
- si el foco es ambiguo, preguntar;
- no enviar emails sin confirmacion;
- no ejecutar operaciones destructivas por inferencia;
- registrar trazabilidad de comandos de voz;
- permitir revisar notas antes de enviarlas.

## Privacidad

Decision actual:

- se aceptan APIs externas por calidad;
- no se guarda audio original;
- se guarda transcripcion;
- debe quedar claro que proveedor proceso la interaccion;
- si el proveedor no responde, la sesion debe mostrar error/reconexion en vez de cambiar silenciosamente de motor.
