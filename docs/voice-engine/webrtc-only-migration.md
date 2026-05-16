# WebRTC-only Migration

Fecha: 2026-05-15.

## Decision

El runtime movil de voz abandona WebSocket completamente.

La implementacion final sera OpenAI Realtime por WebRTC, sin fallback WebSocket en Android.

## Por que

OpenAI documenta WebRTC como opcion recomendada para clientes browser/mobile y WebSocket como opcion natural para conexiones server-to-server.

La implementacion WebSocket actual nos obliga a gestionar piezas de audio que no son diferenciales de Atenea:

- captura continua;
- troceado PCM;
- base64;
- buffer de entrada;
- playback PCM;
- sincronizacion de fin de audio;
- eco;
- ruido;
- interrupciones;
- ruta de audio.

Estas piezas son el nucleo tecnico de WebRTC. Mantenerlas en Atenea aumenta riesgo y consume tiempo en problemas que ya estan resueltos por una pila profesional.

## Alcance

Incluido:

- `android/voice-runtime`;
- pantalla Android `Voz`;
- contrato backend de sesion Realtime si hace falta ajustarlo;
- documentacion de estado y pruebas.

Excluido:

- WebSocket backend con Codex App Server;
- WorkSession, SessionTurn y AgentRun;
- operaciones sobre servidores;
- comunicaciones/email.

Incluido como diseno de fase avanzada, no en el primer corte:

- sideband server-side de OpenAI Realtime para control backend de la misma sesion.

El sideband no contradice `WebRTC-only` porque no transporta la voz del movil ni actua como fallback. Es un canal backend-proveedor para contexto, herramientas, confirmaciones y auditoria.

## Arquitectura destino

```text
VoiceScreen
  -> AteneaVoiceRuntimeController
     -> AteneaVoiceRuntimeService
        -> WebRtcRealtimeClient
           -> PeerConnection
           -> DataChannel "oai-events"
           -> local audio track
           -> remote audio track

Atenea backend
  -> /api/mobile/voice/realtime/session
  -> OpenAI Realtime session/call setup
  -> optional sideband control channel
```

## Componentes nuevos

`WebRtcRealtimeClient`

- crea `PeerConnectionFactory`;
- crea `PeerConnection`;
- crea local audio track;
- recibe remote audio track;
- abre data channel `oai-events`;
- negocia SDP;
- expone eventos tipados al servicio.

`RealtimeDataChannelRouter`

- serializa eventos cliente;
- parsea eventos servidor;
- enruta transcripciones, errores, respuesta iniciada/finalizada y cancelaciones.

`AudioRouteController`

- solicita audio focus;
- selecciona modo de comunicacion;
- observa cambios de auriculares/Bluetooth;
- libera recursos al parar.

`VoicePlaybackOrchestrator`

- conserva cursor de lectura;
- decide segmento actual;
- aplica `parar`, `repite`, `siguiente`, `anterior`, `desde el principio`;
- no reproduce audio por su cuenta si Realtime esta activo.

`RealtimeSidebandController`

- se activa en una fase posterior al WebRTC movil;
- recibe tool calls del modelo;
- consulta foco real, WorkSession, AgentRun, comunicaciones y operaciones;
- ejecuta acciones solo mediante servicios backend;
- exige confirmacion cuando aplica;
- devuelve resultados a la sesion Realtime;
- registra auditoria y coste.

## Codigo a retirar

Del runtime movil:

- `OkHttpClient` usado para Realtime WebSocket;
- `WebSocket` y `WebSocketListener` de Realtime;
- URL `wss://api.openai.com/v1/realtime`;
- `input_audio_buffer.append` para audio de microfono;
- `AudioRecord` usado como captura Realtime directa;
- `AudioTrack` usado como salida Realtime directa;
- conversion de audio a base64 para streaming;
- estado `realtimeTransport = "websocket"`;
- mensajes de UI que hablen de transporte WebSocket.

## Contrato de error

No hay fallback silencioso.

Si WebRTC falla:

1. el estado pasa a `reconnecting` o `error`;
2. la app explica el problema de forma legible;
3. se intenta reconexion solo si el error es recuperable;
4. no arranca TTS local;
5. no arranca WebSocket;
6. no mezcla dos voces.

Esta regla aplica al transporte movil de voz. Si existe sideband backend, su fallo degrada herramientas/contexto avanzado, no arranca otro motor de audio.

## Compatibilidad de dispositivo

El cierre nativo durante WebRTC puede ocurrir sin excepcion Java/Kotlin y, por tanto, sin `lastCrash` capturable por la app. En mayo de 2026 se observo:

- Samsung `SM-M135F`, Android 14: cierre repetible al ejecutar `setLocalDescription`, despues de crear `PeerConnection`, data channel, transceiver de audio y offer SDP.
- Pixel 7, Android 16: la misma APK `0.5.28` tambien se cierra al pulsar conectar voz.

Interpretacion operativa:

- no tratar este patron como falta de recursos si el punto de caida es siempre el mismo;
- no tratarlo como incompatibilidad de un dispositivo concreto si se reproduce en Pixel y Samsung;
- no seguir ajustando parametros sobre la misma libreria sin nueva evidencia nativa;
- para dispositivos incompatibles, la app debe evitar reintentos ciegos y ofrecer diagnostico/subida de reporte;
- para resolver el bloqueo, capturar `ApplicationExitInfo`/tombstone/logcat nativo o evaluar una integracion WebRTC Android distinta.

## Estado de diagnostico

Versiones publicadas durante la migracion:

- `0.5.29`: diagnostico de cierres nativos con `ApplicationExitInfo`.
- `0.5.30`: permiso `ACCESS_NETWORK_STATE` y configuracion ICE conservadora; deja de cerrarse, pero falla al aplicar remote SDP.
- `0.5.31`: recupera track local de microfono `sendrecv` antes de crear la offer.
- `0.5.32`: vuelve a `io.github.webrtc-sdk:android:144.7559.05`.
- `0.5.33`: registra SDP local final y SDP answer remota saneadas para diagnosticar por que Android rechaza `setRemoteDescription`.
- `0.5.34`: normaliza la answer SDP para Android WebRTC cuando OpenAI devuelve `a=msid-semantic:WMS *` sin el espacio requerido por parsers SDP estrictos.
- `0.5.35`: endurece la normalizacion SDP para Android WebRTC: CRLF con salto final, sustitucion de `WMS *` por el stream real y resumen de candidatos ICE sin exponer IPs.
- `0.5.36`: mantiene WebRTC y corrige el flujo de notas para que comandos no reconocidos o transcripciones sin wake word no se guarden automaticamente.
- `0.5.37`: se formaliza el hardening del modulo:
  - readiness real = data channel `OPEN`, no solo ICE conectado;
  - cola bounded de eventos Realtime hasta que el data channel acepte mensajes;
  - sesion Realtime configurada en backend al crear el token efimero, sin `session.update` duplicado desde Android;
  - `RealtimeSdpNormalizer` aislado y cubierto por tests;
  - comandos de notas priorizados antes de comandos genericos de lectura;
  - deduplicacion de transcripciones por secuencia, no por texto exacto;
  - errores recuperables expuestos como estado estructurado;
  - audio focus y observacion de ruta de audio integrados en el foreground service.
- `0.5.38`: se corrige el contrato operativo de notas por voz:
  - `Atenea, nota ...`, `toma nota ...`, `nueva nota ...` y `crea nota ...` se enrutan a `SaveNote`;
  - `semantic_vad.create_response=false` evita que el modelo Realtime confirme acciones que no ha ejecutado;
  - las acciones reales siguen pasando por Android/Core y backend.
- `0.5.39`: se corrige la experiencia de interrupcion durante lectura:
  - `speech_started` ya no cancela la lectura por si solo, para evitar cortes por carraspeos o ruido;
  - solo se cancela cuando llega una transcripcion con wake word e intencion real;
  - las aclaraciones guardan un cursor de retorno al mismo segmento interrumpido;
  - `Atenea, segmento tres`, `parte tres` o `punto tres` saltan directamente a ese segmento.
- `0.5.40`: se introduce captura larga por bloque:
  - las notas pasan a modo bloque siempre;
  - `Atenea, pregunta`, `Atenea, prompt` y `Atenea, bloque` abren captura;
  - durante un bloque, transcripciones sin control exacto se acumulan como contenido;
  - `Atenea, fin` cierra solo si la frase completa es wake word + `fin`;
  - `Athenea`, `Atenia`, `Antenea` y variantes ya aceptadas sirven como wake word;
  - `Atenea, lee bloque`, `resume bloque`, `estado bloque` y `descarta bloque` operan sobre la captura abierta.
- `0.5.41`: se endurece el cierre/confirmacion de bloque:
  - si el proveedor transcribe contenido y `Atenea, fin` en el mismo chunk, se guarda el contenido previo y se cierra el bloque;
  - `Atenea, fin` sigue siendo exacto cuando va solo, pero tambien se acepta como control trailing al final del chunk;
  - la apertura se confirma por voz y el bloque se arma cuando termina esa confirmacion;
  - al guardar una nota de bloque, Atenea confirma `Nota guardada`.

Estado actual:

- `0.5.35` conecta correctamente con OpenAI Realtime WebRTC y permite barge-in durante la lectura;
- el diagnostico de `0.5.33` muestra que la answer trae `a=msid-semantic:WMS *`, mientras la offer Android usa la forma estandar `a=msid-semantic: WMS ...`;
- `0.5.34` aplica esa normalizacion antes de `setRemoteDescription`;
- el diagnostico de `0.5.34` confirma que esa normalizacion se aplica, pero Android sigue rechazando la answer;
- `0.5.35` corrige dos puntos adicionales de compatibilidad SDP: terminadores de linea y wildcard de stream;
- el siguiente trabajo ya no es transporte, sino producto de voz: gestion fina de notas, sideband backend, contexto y herramientas.
- los diagnosticos de SDP quedan resumidos por defecto; el cuerpo SDP saneado solo se conserva en fallos de `setRemoteDescription`.

## Pruebas obligatorias

Antes de publicar:

- conectar Realtime;
- leer ultima respuesta de Codex de mas de 5 segmentos;
- `Atenea, para`;
- `Atenea, repite`;
- `Atenea, siguiente`;
- `Atenea, anterior`;
- `Atenea, desde el principio`;
- enviar una nota a Codex;
- leer la respuesta posterior;
- auriculares Bluetooth;
- altavoz del telefono;
- perdida breve de red;
- salir y volver a entrar en pantalla `Voz`;
- comprobar que no aparece `websocket` como transporte movil.

## Fuentes

- OpenAI Realtime WebRTC: https://platform.openai.com/docs/guides/realtime-webrtc
- OpenAI Realtime WebSocket: https://platform.openai.com/docs/guides/realtime-websocket
- OpenAI Realtime conversations: https://platform.openai.com/docs/guides/realtime-conversations
- Android audio focus: https://developer.android.com/media/optimize/audio-focus
- Android AcousticEchoCanceler: https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler
- Android NoiseSuppressor: https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor
