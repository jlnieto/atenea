# Native Runtime Architecture

Este documento fija el runtime nativo de voz para Android.

## Decision 2026-05-15

El runtime movil de Atenea sera `WebRTC-only` para OpenAI Realtime.

No se mantiene WebSocket como fallback en Android.

Matiz importante:

- prohibido: WebSocket en Android como transporte de voz, fallback o camino alternativo a WebRTC;
- permitido y deseable en fase avanzada: WebSocket sideband servidor-proveedor para que Atenea backend controle la misma sesion Realtime.

Motivo principal:

- OpenAI recomienda WebRTC para clientes browser/mobile y WebSocket para casos server-to-server.
- Con WebRTC no tenemos que gestionar audio del modelo a nivel granular como con WebSocket.
- WebSocket obliga a reinventar piezas delicadas: captura, chunking PCM, base64, playback stream, jitter, eco, corte de respuesta y sincronizacion de fin de audio.
- El objetivo de Atenea no es que funcione a ratos, sino una conversacion manos libres profesional y estable.

`SpeechRecognizer` queda descartado como base de manos libres. Puede existir solo para dictado manual acotado si una pantalla concreta lo necesita, pero no forma parte del motor conversacional.

## Arquitectura objetivo

```text
Android
  :voice-runtime
    AteneaVoiceRuntimeService
    WebRtcRealtimeClient
    WebRtcAudioSessionController
    RealtimeDataChannelRouter
    VoicePlaybackOrchestrator
    BargeInController
    AudioRouteController
    VoiceRuntimeStateStore

  :core-console
    VoiceScreen
      muestra estado compacto
      controla modo voz
      muestra foco, notas y lectura

Backend
  /api/mobile/voice/realtime/session
    emite client secret efimero para sesion Realtime de transcripcion
    nunca expone API key real
    aplica instrucciones, modelo, idioma y safety identifier

  /api/mobile/voice/speech
    sintetiza la voz de Atenea para textos ya decididos por Atenea
    no participa en escucha ni decide acciones

  RealtimeSidebandController
    opcional en fase avanzada
    conecta backend con la sesion Realtime
    atiende tool calls, contexto, confirmaciones y auditoria

Providers
  OpenAI Realtime via WebRTC
```

## Flujo WebRTC

Opcion preferida para el primer corte nativo:

```text
Android -> Atenea backend -> OpenAI client secret efimero de transcripcion
Android -> OpenAI Realtime WebRTC con SDP offer/answer
Android -> OpenAI Realtime audio local para transcripcion
Android <-> OpenAI Realtime data channel "oai-events"
Android -> Atenea backend -> /voice/speech para audio controlado de salida
Android reproduce la voz sintetizada localmente
```

El canal de datos se usa para:

- `session.update`;
- `response.create`;
- `response.cancel`;
- eventos de transcripcion;
- eventos de estado de respuesta;
- errores del proveedor.

La entrada de audio se mueve por WebRTC, no por eventos JSON con PCM base64.

La salida de voz de Atenea no se genera en la misma sesion Realtime. Se genera mediante TTS controlado por backend y se reproduce localmente. Esta separacion evita que el modelo conversacional de Realtime pueda hablar por iniciativa propia, responder a ecos, o inventar contenido fuera del texto decidido por Atenea.

## Sideband server-side

El sideband no es fallback de audio.

Es una conexion servidor-proveedor que permite que Atenea backend participe en la misma sesion Realtime que el movil, sin exponer secretos ni logica sensible en la APK.

Uso previsto:

- mantener contexto real de foco: proyecto, WorkSession, conversacion activa, ultimo AgentRun;
- responder `Atenea, donde estas?` con estado real, no con memoria local del modelo;
- ejecutar tool calls reales desde backend;
- pedir confirmacion antes de operaciones sensibles;
- consultar si Codex ya respondio;
- cambiar foco entre trabajo, comunicaciones y operaciones;
- registrar auditoria y costes;
- actualizar instrucciones de sesion sin reconstruir toda la conexion movil.

Regla:

- Android sigue usando solo WebRTC para audio;
- el backend puede usar sideband si aporta control, seguridad y trazabilidad;
- sideband no sustituye al routing canonical de Atenea Core, lo conecta con la sesion de voz.

## Lo que se elimina del runtime movil

La implementacion WebSocket actual queda como spike historico y debe retirarse del codigo Android:

- `OkHttp` WebSocket contra `wss://api.openai.com/v1/realtime`;
- envio manual de `input_audio_buffer.append`;
- `AudioRecord` dedicado al streaming Realtime;
- `AudioTrack` dedicado a reproducir PCM del proveedor;
- conversion manual de audio a base64;
- contadores de finalizacion basados en playback local de PCM;
- textos de UI que indiquen transporte `websocket`.

Esto no afecta al WebSocket interno de `CodexAppServerClient`, que pertenece a la integracion backend con Codex y no al motor de voz movil.

Tampoco afecta al sideband servidor-proveedor, que no es transporte movil ni fallback.

## Responsabilidades que no delega WebRTC

WebRTC resuelve transporte/media. Atenea sigue siendo responsable de:

- foco operativo;
- lectura por segmentos;
- notas activas;
- confirmaciones;
- routing a WorkSession, comunicaciones u operaciones;
- reglas de seguridad;
- persistencia de contexto;
- telemetria de estados;
- UX de comandos como `Atenea, para`, `repite`, `anterior`, `desde el principio`.

## Audio profesional Android

El runtime debe tratar audio como una sesion de comunicacion, no como playback multimedia normal.

Responsabilidades Android:

- foreground service mientras modo voz este activo;
- audio focus explicito;
- ruta de audio para auriculares Bluetooth, altavoz y auricular;
- integracion con controles de headset cuando aplique;
- estado claro de microfono, salida, conexion y error;
- cierre limpio de recursos al salir;
- no mezclar ningun TTS local con Realtime.

WebRTC debe ser el camino principal para AEC, NS, AGC y deteccion de voz de entrada. La voz de salida se reproduce fuera de Realtime para que Atenea mantenga control absoluto del texto hablado.

## Criterio de calidad

Una pieza solo pasa a producto si cumple:

- estado observable en pantalla;
- recuperacion ante error;
- comportamiento repetible con auriculares;
- barge-in inmediato o con latencia medida y aceptada;
- no contamina comandos con la voz de Atenea;
- no mezcla voces ni proveedores simultaneos;
- no depende de timeouts opacos;
- no expone claves privadas en la APK;
- trazas suficientes para saber que paso sin mirar el movil durante minutos.

## Fuentes oficiales

- OpenAI Realtime WebRTC: https://platform.openai.com/docs/guides/realtime-webrtc
- OpenAI Realtime WebSocket: https://platform.openai.com/docs/guides/realtime-websocket
- OpenAI Realtime conversations: https://platform.openai.com/docs/guides/realtime-conversations
- OpenAI server-side controls: https://platform.openai.com/docs/guides/realtime-server-controls
- Android AcousticEchoCanceler: https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler
- Android NoiseSuppressor: https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor
- Android AutomaticGainControl: https://developer.android.com/reference/android/media/audiofx/AutomaticGainControl
