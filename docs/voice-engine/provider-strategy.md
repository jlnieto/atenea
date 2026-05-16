# Provider Strategy

Este documento fija la estrategia inicial para proveedores de voz.

Decision de producto actual:

- prioridad inicial: voz femenina ultra humana;
- se aceptan APIs externas;
- la latencia se evaluara con pruebas reales;
- el proveedor debe ser reemplazable por arquitectura.
- para el runtime movil conversacional no se permite WebSocket como fallback.
- sideband backend-proveedor queda permitido para control avanzado, porque no es transporte movil ni fallback.

## Candidatos

### OpenAI Realtime

Uso decidido para el primer runtime profesional:

- conversacion speech-to-speech;
- baja latencia;
- deteccion de turnos;
- interrupciones;
- comprension multimodal y tooling futuro.

Motivos:

- OpenAI documenta Realtime API como una API para comunicacion de baja latencia con modelos que soportan interacciones speech-to-speech y entradas/salidas de audio y texto.
- OpenAI documenta WebRTC como el camino recomendado para clientes browser/mobile.
- OpenAI documenta WebSocket como buen encaje para server-to-server.
- OpenAI indica que con WebRTC el peer connection gestiona buena parte del manejo de audio que con WebSocket hay que implementar manualmente.

Riesgos a validar:

- naturalidad real de voz femenina en espanol de Espana;
- control fino del tono de Atenea;
- coste por hora;
- comportamiento en Android con auriculares y ruido.

Estado Android actual:

- proveedor conversacional: OpenAI Realtime WebRTC;
- voces expuestas inicialmente en app: `marin`, `cedar`, `coral`;
- `marin` queda como default operativo;
- velocidad configurable por sesion entre `0.75x` y `1.5x`;
- cambiar voz o velocidad requiere nueva sesion Realtime cuando ya hay audio emitido.

Referencia oficial revisada:

- la API Realtime soporta voces integradas como `alloy`, `ash`, `ballad`, `coral`, `echo`, `sage`, `shimmer`, `verse`, `marin` y `cedar`;
- OpenAI recomienda `marin` y `cedar` para mayor calidad;
- la velocidad Realtime tiene maximo documentado de `1.5`.

### ElevenLabs

Uso candidato futuro:

- salida de voz femenina premium;
- voz muy expresiva;
- posible capa TTS en arquitectura hibrida.

Motivos:

- ElevenLabs documenta modelos orientados a expresividad, calidad y baja latencia.
- `eleven_v3` se presenta como modelo expresivo.
- `eleven_flash_v2_5` se presenta como opcion de baja latencia.

Riesgos a validar:

- latencia total si se usa despues de comprension en otro proveedor;
- interrupcion limpia de audio en curso;
- coste por uso intensivo;
- consistencia de voz en respuestas largas.

## Estrategias

### Estrategia A: OpenAI Realtime WebRTC completo

```text
Audio usuario -> WebRTC -> OpenAI Realtime -> Atenea Core/backend -> OpenAI Realtime audio
```

Ventajas:

- menor complejidad;
- mejor encaje para interrupciones;
- menor latencia probable;
- menos sincronizacion entre STT/TTS.

Riesgos:

- si la voz no alcanza el nivel premium esperado, no vale como default final.
- requiere integrar WebRTC nativo Android con disciplina de audio profesional.

### Estrategia B: Hibrida premium

```text
Audio usuario -> OpenAI Realtime/STT -> Atenea backend -> ElevenLabs TTS -> audio usuario
```

Ventajas:

- mayor probabilidad de voz femenina ultra humana;
- control fuerte sobre seleccion de voz;
- separa comprension y expresion.

Riesgos:

- mas latencia;
- mas puntos de fallo;
- interrupciones y cursor requieren mas logica propia.

Estado:

- no se implementa como fallback automatico del runtime conversacional;
- solo se evaluara como modo de producto separado si WebRTC + Realtime no alcanza la voz objetivo.

## Decision inicial

Decision revisada:

- el primer runtime profesional sera OpenAI Realtime por WebRTC;
- se elimina el WebSocket movil como camino de producto;
- se permite sideband server-side en backend para herramientas, contexto real y seguridad;
- ElevenLabs queda como evaluacion futura de voz premium, no como parche;
- el backend mantiene las claves y la configuracion de proveedor;
- la APK no contiene claves privadas.

Las pruebas reales siguen siendo necesarias:

1. misma conversacion de WorkSession;
2. misma voz objetivo;
3. mismas interrupciones;
4. mismas notas;
5. medicion con auriculares;
6. evaluacion caminando o con ruido;
7. lectura de respuestas largas de Codex;
8. comandos durante la lectura.

## Fuentes oficiales

- OpenAI Text to speech: https://platform.openai.com/docs/guides/text-to-speech
- OpenAI Realtime API: https://platform.openai.com/docs/guides/realtime
- OpenAI Realtime WebRTC: https://platform.openai.com/docs/guides/realtime-webrtc
- OpenAI Realtime WebSocket: https://platform.openai.com/docs/guides/realtime-websocket
- OpenAI Voice Activity Detection: https://platform.openai.com/docs/guides/realtime-vad
- OpenAI Realtime transcription: https://platform.openai.com/docs/guides/realtime-transcription
- OpenAI Voice agents: https://platform.openai.com/docs/guides/voice-agents
- ElevenLabs overview: https://elevenlabs.io/docs/overview/intro
- ElevenLabs Text to Speech API: https://elevenlabs.io/text-to-speech-api

## Estado historico retirado del objetivo

El corte anterior de TTS backend + fallback local Android fue util para validar lectura premium, pero no es la base de la conversacion manos libres.

No debe mezclarse con Realtime en la pantalla `Voz` porque puede producir doble voz, interrupciones tardias y estados inconsistentes.

Regla actual:

- una sesion de voz usa un unico motor de salida activo;
- en modo conversacional profesional ese motor es Realtime por WebRTC;
- si Realtime falla, la sesion entra en estado de error/reconexion, no cambia silenciosamente a otro motor.
