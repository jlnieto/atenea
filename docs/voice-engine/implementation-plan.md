# Implementation Plan

Este plan implementa Atenea Voice Engine por fases, empezando por el caso de mayor valor: conversacion de WorkSession con voz, interrupciones y notas.

## Fase 0: contrato y evaluacion tecnica

Objetivo:

- dejar fijado el modelo de producto;
- elegir providers a probar;
- definir interfaces internas;
- preparar spike de audio.

Entregables:

- documentos de `docs/voice-engine/`;
- matriz OpenAI Realtime vs OpenAI + ElevenLabs;
- criterios de evaluacion;
- secretos/configuracion de providers.

## Fase 1: Voice Core backend

Objetivo:

Crear la base persistente del motor de voz.

Estado:

- implementada la primera base backend con `voice_focus` y `voice_note`;
- implementados endpoints moviles iniciales bajo `/api/mobile/voice`;
- implementado envio de notas activas como comando `Core` con canal `VOICE`;
- las notas enviadas pasan a `SENT` y quedan vinculadas al `CoreCommand` consumidor;
- las notas descartadas por el operador pasan a `ARCHIVED` y no se borran fisicamente.

Backend:

- `VoiceSession`;
- `VoiceTurn`;
- `VoiceFocusSnapshot`;
- `VoicePlaybackSegment`;
- `VoiceNote`;
- `VoicePendingConfirmation`.

Servicios:

- `VoiceFocusService`;
- `VoiceNoteService`;
- `VoiceCommandRouter`;
- `VoicePromptComposer`;
- `VoiceSafetyService`.

Endpoints iniciales:

```text
GET  /api/mobile/voice/focus
POST /api/mobile/voice/focus
GET  /api/mobile/voice/notes/active
POST /api/mobile/voice/notes
POST /api/mobile/voice/notes/{noteId}/archive
POST /api/mobile/voice/notes/archive-last
POST /api/mobile/voice/notes/archive-active
POST /api/mobile/voice/notes/send
```

## Fase 2: Android Voice Runtime base

Objetivo:

Crear modulo Android reutilizable.

Estado:

- implementada primera pantalla Android `Voz`;
- integrado acceso desde el menu principal;
- implementado cliente Android para `/api/mobile/voice/focus` y `/api/mobile/voice/notes`;
- la pantalla permite consultar/cambiar foco, crear notas activas y enviar notas como prompt `Core`;
- implementado runtime Android inicial con `SpeechRecognizer` para dictado y `TextToSpeech` para lectura;
- la pantalla permite dictar una nota, guardar la transcripcion y escuchar la respuesta devuelta por Atenea;
- implementado interprete local de comandos con palabra obligatoria `Atenea`;
- separado el flujo entre nota dictada sin activacion y comando con activacion;
- implementado control basico de lectura por segmentos: leer, parar, repetir y siguiente;
- persistido el cursor de lectura en `voice_focus.playback`;
- mejorada la respuesta local de `Atenea, donde estas`;
- anadida confirmacion local para enviar notas sin instruccion concreta;
- ampliado el vocabulario local de comandos para depender menos de frases exactas;
- implementado TTS premium por backend como prueba historica de lectura;
- implementadas aclaraciones sobre el segmento actual;
- implementado cursor de retorno para continuar la lectura original despues de una aclaracion;
- publicado APK `0.4.17` (`versionCode` 23) con esta base.
- retirado `SpeechRecognizer` como base de manos libres; queda solo para dictado manual;
- creado modulo Android `:voice-runtime` con foreground service y captura `AudioRecord`;
- anadido contrato backend `/api/mobile/voice/realtime/session` para tokens efimeros Realtime;
- publicado primer corte nativo `0.5.0` como base de arquitectura profesional.
- implementada conexion Realtime por WebSocket desde Android con token efimero;
- cambiado el runtime a PCM 24 kHz para cumplir el contrato Realtime;
- implementada reproduccion de audio Realtime con `AudioTrack`;
- implementado barge-in base con VAD del servidor, `response.cancel` y truncado de item de audio;
- ampliado el diagnostico de pantalla con estado de entrada, salida, transporte, voz y transcripciones;
- preparado `0.5.1` (`versionCode` 34) para pruebas reales de conversacion voz-a-voz.
- publicado `0.5.19` con lectura continua por segmentos.
- preparado `0.5.20` (`versionCode` 53) con runtime movil WebRTC-only.
- preparado `0.5.23` (`versionCode` 56) con diagnostico enviable y archivo de releases para rollback publicado con versionCode superior.
- diagnosticado cierre nativo en `0.5.26` (`versionCode` 59) en Samsung `SM-M135F`/Android 14 durante `setLocalDescription`; sin crash Java, sin fallo de token, red ni backend.
- preparado `0.5.27` (`versionCode` 60) inicializando contexto EGL y factorias de codificacion/decodificacion WebRTC para estabilizar la generacion/aplicacion del SDP local.
- diagnosticado que `0.5.27` mantiene el mismo cierre nativo en `setLocalDescription`; se descarta falta de recursos como causa principal por ser un fallo determinista en el mismo punto.
- preparado `0.5.28` (`versionCode` 61) cambiando la dependencia Android WebRTC de `io.github.webrtc-sdk:android:144.7559.05` a `io.github.webrtc-sdk:android:125.6422.07` para aislar una posible regresion nativa de la rama `144`.
- corregida la validacion de `0.5.28`: Pixel 7 tambien se cierra al pulsar conectar voz, en el mismo punto `setLocalDescription`; el fallo queda acotado a integracion WebRTC nativa/SDP local, no a dispositivo concreto.
- preparado `0.5.29` (`versionCode` 62) con diagnostico de salidas de proceso Android `ApplicationExitInfo` para capturar `CRASH_NATIVE`, estado/razon de salida y trace nativo si el sistema lo expone tras reiniciar la app.
- diagnosticado en `0.5.29` cierre `CRASH_NATIVE`/`SIGABRT` dentro de `libjingle_peerconnection_so.so`; la pila Java esta en `WebRtcRealtimeClient.setLocalDescription` y el SDP previo contiene una m-line de audio y una de data channel.
- preparado `0.5.30` (`versionCode` 63) anadiendo permiso `ACCESS_NETWORK_STATE` y configuracion ICE explicita/conservadora (`STUN`, `MAXBUNDLE`, `RTCP mux`, sin TCP candidates, gathering once) porque el abort ocurre cuando `setLocalDescription` arranca ICE/network gathering.
- diagnosticado que `0.5.30` ya no se cierra y recibe `HTTP 201` de OpenAI, pero falla en `setRemoteDescription` con `SessionDescription is NULL`; el flujo movil seguia ofreciendo audio `recvonly`, distinto del flujo WebRTC recomendado con track local.
- preparado `0.5.31` (`versionCode` 64) restaurando track local de microfono `sendrecv` antes de crear la offer y validando/resumiendo la SDP answer antes de aplicarla.
- diagnosticado que `0.5.31` recibe una SDP answer valida de OpenAI (`v=0`, audio, data channel, candidates, fingerprint), pero WebRTC `125.6422.07` la rechaza en `setRemoteDescription` con `SessionDescription is NULL`.
- preparado `0.5.32` (`versionCode` 65) volviendo a WebRTC `144.7559.05` manteniendo los fixes que eliminaron el `SIGABRT`: `ACCESS_NETWORK_STATE`, configuracion ICE conservadora y track local `sendrecv`.
- diagnosticado que `0.5.32` mantiene answer SDP valida y el rechazo `SessionDescription is NULL`; se retira del token efimero WebRTC la configuracion heredada de formatos PCM del spike WebSocket para dejar que WebRTC negocie audio por SDP/RTP.
- publicado `0.5.35` (`versionCode` 68) con WebRTC operativo tras normalizar la SDP answer de OpenAI para Android (`msid-semantic`, CRLF, salto final y stream real).
- preparado `0.5.36` (`versionCode` 69) endureciendo el flujo de notas: sin wake word no se guarda nada, las notas requieren intencion explicita, y se anaden acciones para leer, contar, descartar una nota, descartar la ultima y archivar todas con confirmacion.
- preparado `0.5.37` (`versionCode` 70) endureciendo el runtime WebRTC: la app solo considera Realtime listo cuando el data channel esta abierto, los eventos se encolan hasta que el canal acepta mensajes, la configuracion de sesion queda en backend como fuente de verdad, la normalizacion SDP queda aislada y testeada, y los errores recuperables se exponen como estado estructurado.
- preparado `0.5.38` (`versionCode` 71) corrigiendo notas por voz durante lectura: `Atenea, nota ...`, `toma nota ...`, `nueva nota ...` y `crea nota ...` crean una nota real en backend; Realtime deja de autogenerar respuestas por cada turno para evitar falsas confirmaciones del modelo.
- preparado `0.5.39` (`versionCode` 72) corrigiendo orquestacion durante lectura: los ruidos ya no cortan audio por `speech_started`, las interrupciones quedan gated por wake word/intencion, las aclaraciones vuelven al segmento interrumpido en vez de saltar al siguiente y se anade navegacion directa por segmento.
- preparado `0.5.40` (`versionCode` 73) introduciendo modo bloque como motor de captura larga: `Atenea, pregunta`, `Atenea, prompt`, `Atenea, bloque` y notas siempre abren bloque; el cierre exacto es `Atenea, fin`/variantes de wake word; silencios y pausas largas no procesan el contenido.
- preparado `0.5.41` (`versionCode` 74) endureciendo el modo bloque: si `Atenea, fin` llega al final de un chunk con contenido previo se cierra igualmente y se conserva ese contenido; la apertura de bloque tiene confirmacion audible y el bloque se arma despues de la confirmacion para evitar capturar la propia voz de Atenea.
- anadida gestion Android de audio focus y cambios de ruta de audio para auriculares/Bluetooth/interrupciones.
- extraida logica pura de `VoiceScreen` para contexto Realtime, segmentacion de lectura y enrutado de comandos, como primer paso para que la pantalla no concentre toda la arquitectura.
- anadidos tests Android unitarios para normalizacion SDP y clasificacion de comandos de voz.

Decision de cierre de esta fase:

- el runtime WebSocket/PCM fue valido como spike, pero no es arquitectura final;
- no se mantiene WebSocket como fallback movil;
- el siguiente corte reemplaza el transporte por WebRTC-only.

Componentes:

- `VoiceRuntime`;
- `WakeWordGate`;
- `AudioCapture`;
- `SpeechPlayback`;
- `BargeInController`;
- `VoiceSessionClient`;
- `VoiceNotesClient`.

Pantalla inicial:

- foco actual;
- notas activas;
- entrada temporal de texto como sustituto de transcripcion real;
- envio de notas activas como prompt;
- resultado del comando y confirmacion si Atenea la exige.

Pendiente de esta fase:

- elegir e integrar wake word local real;
- conectar los eventos Realtime con acciones reales de Atenea/Core;
- disenar sideband backend para contexto real, herramientas, confirmaciones y auditoria;
- seleccion final de provider premium.

## Fase 2.5: Migracion WebRTC-only

Objetivo:

Reemplazar el spike WebSocket/PCM por una arquitectura WebRTC profesional, sin fallback WebSocket en Android.

Entregables:

- dependencia WebRTC Android seleccionada y fijada por version;
- `WebRtcRealtimeClient` aislado dentro de `:voice-runtime`;
- SDP offer/answer funcionando con OpenAI Realtime;
- data channel `oai-events` para eventos Realtime;
- audio local/remoto gestionado por WebRTC;
- `AteneaVoiceRuntimeState` sin valores de transporte `websocket`;
- `VoiceScreen` sin textos ni diagnosticos WebSocket;
- borrado de `AudioRecord`/`AudioTrack` especificos del streaming Realtime;
- pruebas de comandos durante lectura;
- pruebas con auriculares Bluetooth;
- pruebas de respuesta larga de Codex con lectura completa.

Secuencia:

1. Introducir abstraccion `RealtimeTransport` solo con implementacion WebRTC.
2. Crear conexion WebRTC con token efimero o endpoint SDP backend.
3. Mover eventos Realtime a `RealtimeDataChannelRouter`.
4. Mantener foco, notas y cursor fuera del proveedor.
5. Retirar imports, dependencias y codigo WebSocket del runtime movil.
6. Validar que solo hay una fuente de voz activa.
7. Publicar APK y documentar resultados de pruebas reales.

Criterio de salida:

- `rg "WebSocket|websocket|android.media.AudioRecord|android.media.AudioTrack|input_audio_buffer.append" android/voice-runtime` no debe mostrar uso dentro del runtime Realtime final, salvo documentacion tecnica o tests explicitos de no regresion;
- una lectura larga puede pararse, repetirse, avanzar, retroceder y empezar desde el principio;
- durante lectura, el usuario puede interrumpir sin que Atenea capture su propia voz como parte del comando;
- si Realtime falla, la UI muestra error/reconexion. No se activa otro motor de voz silenciosamente.

Compatibilidad observada:

- Samsung `SM-M135F`, Android 14: cierre nativo repetible durante `setLocalDescription` con WebRTC `144.7559.05` y `125.6422.07`.
- Pixel 7, Android 16: cierre repetible durante `setLocalDescription` con la misma APK `0.5.28`.
- Decision: tratar el fallo como problema de integracion WebRTC nativa/SDP local. El siguiente diagnostico debe capturar `ApplicationExitInfo`/tombstone o logcat nativo; no continuar con parches menores sin nueva evidencia.

## Fase 3: WorkSession voice prototype

Objetivo:

Operar una conversacion de proyecto por voz.

Casos:

- `Atenea, donde estas?`;
- `Atenea, dime el siguiente paso`;
- resumen de respuesta Codex;
- pregunta si quieres detalle;
- lectura por segmentos;
- pausar/continuar/repetir;
- aclarar punto concreto;
- guardar nota;
- enviar notas como prompt.

Restriccion:

Solo `development/worksession`. No comunicaciones aun.

## Fase 3.5: Sideband backend de control

Objetivo:

Conectar la sesion Realtime con Atenea backend para que la voz opere contexto y herramientas reales sin meter logica sensible en Android.

Casos:

- `Atenea, donde estas?` con proyecto, WorkSession, conversacion y AgentRun reales;
- enviar prompt a Codex desde la sesion de voz;
- consultar si Codex ya respondio;
- leer ultima respuesta de Codex;
- guardar notas durante lectura;
- enviar notas al foco activo;
- cambiar foco entre proyectos sin perder estado;
- preparar operaciones sensibles con confirmacion.

Reglas:

- Android continua usando WebRTC-only;
- sideband no es fallback de audio;
- si sideband falla, la sesion muestra que herramientas/contexto avanzado no estan disponibles;
- acciones sensibles pasan por servicios backend y quedan auditadas;
- no se exponen credenciales ni endpoints internos en la APK.

Entregables:

- `RealtimeSidebandController`;
- mapeo de tool calls a servicios Atenea;
- modelo de eventos tipado;
- confirmaciones persistentes;
- trazabilidad de coste y accion;
- pruebas de WorkSession con Codex real.

## Fase 4: Provider premium

Objetivo:

Evaluar y seleccionar la combinacion de audio.

Pruebas:

- OpenAI Realtime speech-to-speech;
- OpenAI comprension + ElevenLabs voz;
- modos separados de transcripcion + TTS, solo si se decide producto aparte.

Resultado esperado:

- elegir provider default;
- dejar provider intercambiable;
- documentar latencia, calidad y coste.

## Fase 5: Communications

Objetivo:

Hacer util el flujo de email por voz.

Casos:

- cambiar foco a comunicaciones;
- revisar emails;
- resumir importantes;
- preguntar por un email concreto;
- preparar respuesta;
- crear tarea a partir de email.

Regla:

Enviar email requiere confirmacion.

## Fase 6: Operations

Objetivo:

Operar servidores por voz.

Casos:

- estado del dedicado;
- comprobar Apache;
- recuperar Apache con confirmacion;
- resumir incidencias;
- explicar validacion.

Regla:

Acciones operativas siempre confirmadas.

## Fase 7: Pantalla apagada y auriculares

Objetivo:

Modo manos libres real.

Android:

- foreground service;
- integracion auriculares;
- wake word obligatoria;
- control de bateria;
- resiliencia a red;
- notificacion persistente cuando el modo voz esta activo.

Esta fase se ejecuta despues de validar la calidad conversacional con app activa.

## Primer corte implementable

El primer corte no debe intentar todo el sistema.

Debe demostrar:

1. foco actual;
2. voz femenina premium;
3. resumen de respuesta;
4. detalle por segmentos;
5. interrupcion para aclarar;
6. nota global;
7. enviar notas como prompt;
8. continuidad por cursor.

Si este corte no es excelente, no se avanza a comunicaciones ni operaciones.
