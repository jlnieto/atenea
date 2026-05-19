# Telemetria de comandos de voz

## Objetivo

Atenea registra los comandos de voz que no se pudieron ejecutar para dejar de corregir casos aislados a ciegas.

La telemetria guarda la transcripcion exacta recibida por la app, la intencion que el cliente Android calculo, y el contexto funcional necesario para reproducir el problema.

No guarda audio.

## Contrato backend

Endpoint de escritura:

```http
POST /api/mobile/voice/command-telemetry
```

Endpoint de lectura reciente:

```http
GET /api/mobile/voice/command-telemetry?limit=50
```

Endpoint de resumen agrupado:

```http
GET /api/mobile/voice/command-telemetry/summary?limit=200
```

Todos requieren autenticacion de operador movil.

## Eventos registrados

La app Android registra eventos cuando:

- la transcripcion parece un comando de Atenea, pero la wake word no esta al inicio
- el comando empieza por Atenea, pero no se puede resolver a una intencion
- la intencion existe, pero no es ejecutable desde Realtime
- el cliente esta ocupado y bloquea temporalmente un comando

Campos principales:

- `transcript`: texto exacto devuelto por Realtime
- `normalizedTranscript`: texto normalizado para agrupar fallos
- `intentType`: intencion Android calculada, por ejemplo `Empty`, `ReadNote`, `SendNotes`
- `outcome`: `IGNORED`, `UNRECOGNIZED`, `BLOCKED` o `FAILED`
- `reason`: causa concreta, por ejemplo `empty_intent`, `wake_word_not_at_start`, `client_pending`
- `domain`, `projectId`, `projectName`, `workSessionId`, `workSessionTitle`
- `activeNoteCount`, `pendingSendIntentId`
- `realtimeConnected`, `voiceState`

## Tabla

La migracion `V42__create_voice_command_telemetry.sql` crea:

```sql
voice_command_telemetry
```

Consulta operativa recomendada:

```sql
select
  created_at,
  reason,
  intent_type,
  transcript,
  normalized_transcript,
  project_name,
  work_session_title,
  active_note_count
from voice_command_telemetry
order by created_at desc
limit 100;
```

Consulta API recomendada para priorizar mejoras:

```http
GET /api/mobile/voice/command-telemetry/summary?limit=200
```

La respuesta agrupa por transcripcion normalizada, resultado, motivo e intencion detectada. Para cada grupo devuelve:

- transcripcion normalizada
- ejemplo real escuchado
- `outcome`
- `reason`
- `intentType`
- proyecto y `WorkSession`
- numero de notas activas
- numero de repeticiones
- ultima fecha vista

## Uso para mejorar Atenea

El ciclo correcto es:

1. Consultar `/summary` para agrupar por `normalizedTranscript`, `outcome`, `reason` e `intentType`.
2. Elegir los comandos repetidos que deberian haber funcionado.
3. Añadirlos como tests en `VoiceCommandInterpreterTest`.
4. Ajustar `VoiceCommandInterpreter`.
5. Publicar nueva APK.

Los comandos de alto valor son los de notas, envio a Codex, confirmaciones, lectura de respuestas y navegacion por segmentos.
