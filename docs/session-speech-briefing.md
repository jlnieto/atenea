# Session speech briefing

## Objetivo

La lectura de respuestas de Codex debe estar optimizada para escucharse desde movil.

El operador no necesita oir rutas, comandos, URLs, hashes, trazas ni listas tecnicas largas. Necesita saber:

- resultado real
- bloqueo actual, si existe
- verificacion realizada
- si Codex sigue trabajando
- siguiente decision o accion util

## Flujo runtime

1. Atenea obtiene el resumen movil de la `WorkSession`.
2. `SessionSpeechPreparationService` localiza la ultima respuesta util de Codex.
3. Atenea calcula un hash SHA-256 del contenido fuente.
4. Si existe cache para `WorkSession` + modo + proveedor + modelo + version de prompt + hash, reutiliza el briefing persistido.
5. Si no hay cache y `ATENEA_BRIEFING_ENABLED=true`, se usa el proveedor configurado en `ATENEA_BRIEFING_PROVIDER`.
6. El proveedor canonico actual es `deepseek`.
7. `DeepSeekSessionSpeechBriefingClient` llama a `/chat/completions` con `ATENEA_BRIEFING_MODEL`.
8. DeepSeek devuelve JSON con un unico campo `speech`.
9. Atenea limpia el texto final, aplica limites de longitud y guarda el resultado en cache.
10. OpenAI TTS sintetiza el audio a partir del texto ya optimizado.

La generacion del texto escuchable y la generacion del audio son piezas separadas:

- DeepSeek: reescribe la respuesta tecnica para TTS.
- OpenAI TTS: genera el audio final.

## Fallback

El fallback determinista sigue existiendo por robustez operativa.

No es la via principal cuando el briefing esta habilitado. Solo se usa si:

- el briefing esta deshabilitado;
- no hay proveedor compatible;
- falta la API key;
- DeepSeek falla;
- DeepSeek devuelve contenido invalido.

Esto evita que la app deje de poder leer respuestas por una incidencia externa del proveedor LLM.

## Variables

```env
ATENEA_BRIEFING_ENABLED=true
ATENEA_BRIEFING_PROVIDER=deepseek
ATENEA_BRIEFING_MODEL=deepseek-v4-flash
ATENEA_BRIEFING_PROMPT_VERSION=session-speech-briefing-v1
ATENEA_BRIEFING_MAX_INPUT_CHARACTERS=18000
ATENEA_BRIEFING_BRIEF_MAX_OUTPUT_CHARACTERS=430
ATENEA_BRIEFING_FULL_MAX_OUTPUT_CHARACTERS=3500
ATENEA_BRIEFING_MAX_OUTPUT_TOKENS=900
ATENEA_BRIEFING_TEMPERATURE=0.1
```

DeepSeek usa:

```env
ATENEA_DEEPSEEK_API_BASE_URL=https://api.deepseek.com
ATENEA_DEEPSEEK_API_KEY=...
```

## Costes

Cada briefing generado por DeepSeek registra uso en `api_usage_record` con:

- `provider=deepseek`
- `feature=session_speech_briefing`
- modelo configurado
- tokens de entrada
- tokens de cache hit/cache miss si el proveedor los devuelve
- tokens de salida
- coste estimado

Esto permite ver el gasto de los resumenes TTS en la pantalla de costes por proveedor, modelo y rango de fechas.

Un cache hit no llama a DeepSeek y no registra coste nuevo. El cache se invalida automaticamente cuando cambia la respuesta fuente, el modo, el proveedor, el modelo o `ATENEA_BRIEFING_PROMPT_VERSION`.

## Cache persistente

La migracion `V43__create_session_speech_briefing_cache.sql` crea:

```sql
session_speech_briefing_cache
```

El cache guarda:

- `work_session_id`
- `mode`
- `provider`
- `model`
- `prompt_version`
- `source_hash`
- `source_turn_id`, si el texto viene de un turno conversacional
- `latest_run_id`, si el texto viene de un run
- texto final listo para TTS
- marca de truncado
- `created_at` y `last_used_at`

## Contrato de calidad

El prompt exige:

- espanol natural
- resultado primero
- frases cortas
- informacion accionable
- nada de markdown
- nada de rutas, URLs, comandos, hashes ni codigo
- no inventar pruebas, despliegues, PRs ni verificaciones
- en modo `brief`, dos o tres frases como maximo

El resultado debe sonar como una respuesta de operador senior, no como un resumen tecnico leido en voz alta.

## Evaluacion de calidad

La suite de `SessionSpeechPreparationServiceTest` cubre:

- prioridad de DeepSeek sobre fallback cuando el briefing esta habilitado
- reutilizacion de cache persistente antes de llamar al proveedor
- limpieza de markdown, rutas, URLs y comandos antes de TTS
- fallback determinista si no hay proveedor o si el proveedor falla
- preferencia por el ultimo `SessionTurn` completo frente a snapshots truncados

Cada cambio en prompt, limpieza o cache debe mantener esos contratos. Si aparece una mala lectura real, se debe convertir en un caso de test con el texto fuente que la produjo.
