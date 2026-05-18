# Codex Auth And API Costs

Este documento fija el contrato operativo para que Atenea use Codex App Server con cuenta ChatGPT y para que el movil muestre costes sin mezclar conceptos.

## Objetivo

Codex App Server debe usar la sesion ChatGPT del operador. No debe consumir la API key de OpenAI salvo que se cambie explicitamente el contrato operativo.

Esto evita dos problemas:

- gasto de Codex cargado silenciosamente a una API key
- falta de visibilidad desde Atenea cuando el runtime cambia de modo de autenticacion

## Guard De Arranque

Los contenedores de Codex App Server arrancan mediante:

```text
/usr/local/bin/codex-auth-guard codex app-server ...
```

El guard vive en:

```text
docker/codex-auth-guard.sh
```

Reglas actuales:

- `ATENEA_CODEX_REQUIRED_AUTH_MODE=chatgpt`
- `auth.json` debe existir
- `auth_mode` debe ser `chatgpt`
- no puede existir `OPENAI_API_KEY` dentro de `auth.json`
- deben existir tokens ChatGPT completos

Si alguna regla falla, el contenedor no arranca y devuelve `BLOCKED`.

## Estado Saneado

El backend no debe leer tokens de Codex.

Por eso el guard escribe un fichero saneado, sin secretos:

```text
codex-auth-status.json
```

Ejemplo de contenido:

```json
{
  "checkedAt": "2026-05-18T19:20:00.000Z",
  "compliant": true,
  "status": "ok",
  "requiredAuthMode": "chatgpt",
  "authMode": "chatgpt",
  "apiKeyPresent": false,
  "tokensPresent": true
}
```

Atenea lee ese fichero mediante:

- `ATENEA_CODEX_APP_SERVER_AUTH_STATUS_FILE`
- `ATENEA_RESCUE_CODEX_APP_SERVER_AUTH_STATUS_FILE`

Los paths a `auth.json` pueden seguir configurados para diagnostico interno, pero el contrato preferente para la app y para bloqueos operativos es el estado saneado.

## Variables Canonicas

Variables compartidas por dev, preview y prod:

```text
ATENEA_CODEX_REQUIRED_AUTH_MODE
ATENEA_CODEX_APP_SERVER_AUTH_FILE
ATENEA_CODEX_APP_SERVER_AUTH_STATUS_FILE
ATENEA_RESCUE_CODEX_APP_SERVER_AUTH_FILE
ATENEA_RESCUE_CODEX_APP_SERVER_AUTH_STATUS_FILE
```

Regla de naming:

- `ATENEA_CODEX_*` describe integracion con Codex App Server
- `ATENEA_OPENAI_*` describe APIs OpenAI usadas directamente por Atenea
- `ATENEA_OPENAI_COSTS_*` describe permisos administrativos de costes OpenAI
- `ATENEA_DEEPSEEK_*` describe proveedor DeepSeek

## Bloqueo En Backend

Antes de lanzar trabajo real de Codex, Atenea valida el estado con `CodexAuthStatusService`.

Se bloquean:

- turns de WorkSession
- turns de Rescue
- generacion de entregables por Codex

El bloqueo debe ser corto y accionable. No debe devolver un transcript largo ni tokens ni paths sensibles.

## Costes En Movil

La pantalla `Costes API` separa tres cosas:

- `Total`: suma del periodo seleccionado
- `Facturacion real`: importe reconciliable por proveedor y modelo cuando el proveedor lo expone
- `Uso tecnico OpenAI`: requests y tokens por categoria cuando hay actividad real
- `Codex App Server`: estado `ChatGPT activo` o bloqueo operativo

Reglas de UI:

- no mostrar categorias de uso tecnico con todo a cero
- no mostrar desglose bruto de lineas de coste en movil
- no leer rutas, IDs largos o comandos como informacion principal para voz
- mantener los detalles tecnicos en payload/API, no en la lectura principal

## OpenAI Costs Vs Usage

OpenAI expone costes y uso como superficies distintas:

- Costs: importes facturables por periodo
- Usage: volumen tecnico, tokens, requests y dimensiones disponibles por endpoint

No siempre existe una correspondencia perfecta entre importe facturable, API key, proyecto y modelo. Por eso Atenea debe presentar el total facturable como fuente economica principal y el uso tecnico como diagnostico.

## Verificacion Operativa

Comprobaciones esperadas despues de deploy:

```text
codex login status -> Logged in using ChatGPT
OPENAI_API_KEY ausente en contenedores Codex App Server
codex-auth-status.json -> compliant=true, status=ok, authMode=chatgpt
/api/mobile/costs/overview -> codexAuthStatuses primary/rescue ok
```

## Criterio De Listo

La integracion se considera sana cuando:

- los cuatro Codex App Server arrancan con guard OK
- prod y preview exponen health `UP`
- Atenea ve primary y rescue como `ok`
- la pantalla de costes no muestra categorias de uso vacias
- ninguna salida de voz principal incluye desglose bruto, rutas o comandos salvo que el operador pida detalle tecnico
