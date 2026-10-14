# Ayuda con este cambio

La conversación de un Cambio sigue siendo el lugar para pedir trabajo a Codex.
`Ayuda con este cambio` es una consulta diferente: explica el punto actual y
orienta hacia las acciones normales de Atenea. No crea Cambios, WorkSessions,
AgentRuns, prompts de trabajo, pruebas, PRs ni publicaciones. No reactiva Core,
App DEV, sesiones independientes ni servidores Codex antiguos.

## Uso móvil

Desde la conversación, pulsa `Ayuda`; también está en el menú y en la pantalla
`Cambio`. Puedes preguntar `¿Qué hago ahora?` y `No lo entiendo; más fácil`.
La respuesta muestra una explicación, la revisión del estado consultado, los
hechos guardados y un acceso al siguiente panel. Ese acceso sólo navega:
`Ir a Validar cambio` abre el panel; no lanza las pruebas. Las acciones reales
mantienen sus propias verificaciones de estado, roles, confirmaciones y factores.

Una consulta nueva vuelve a leer el estado: no confunde una prueba de otra
revisión con el código actual, ni una definición de validación antigua con la
vigente. Un AgentRun SUCCEEDED no es una validación PASS. Una advertencia textual
de Codex sobre Git no es una prueba fallida. La ayuda no sincroniza GitHub ni
declara CI/mergeability o un despliegue sano a partir de un mensaje.

## API `change-help/v1`

Operador autenticado y activo; sólo sesiones realmente vinculadas a un Cambio
con proyecto, worker, rama y workspace coherentes. No se aceptan rutas, modelos,
comandos, hechos o destinos elegidos por el cliente.

- `GET /api/mobile/sessions/{sessionId}/help`: explicación del estado registrado.
  Sólo lecturas de DB; ningún worker, LLM, lock de escritura ni reconciliación.
- `POST /api/mobile/sessions/{sessionId}/help`: cuerpo cerrado
  `{"question":"¿Qué hago ahora?","simple":false}`; `simple` es opcional.
  Pregunta no vacía, hasta 1000 caracteres. Puede pedir una explicación más
  natural al proveedor de explicaciones ya configurado; no ejecutar herramientas.

La respuesta incluye identidad del Cambio/sesión, sourceRevision, observedAt,
stage, answer, simpleAnswer, facts, nextAction, suggestedQuestions y
explanationSource. `nextAction.destination` sólo puede ser CHANGE, ACTIVITY o CHAT.
Nunca es un comando, permiso, URL, host, ruta ni concesión de producción.

## Disponibilidad y privacidad

La orientación determinista está siempre disponible mientras App y su DB estén
sanos, incluso si Codex/AX42 no responde. No se depende de la tarea que ha fallado.
Cada pregunta consulta de nuevo los resultados actuales antes de explicarlos.

El explicador opcional reutiliza DeepSeek y el modelo/activación de `atenea.briefing`
existentes. Esta entrega no añade ni cambia credenciales, proveedor o flags en
servidores. GET no llama al modelo; una pregunta POST explícita puede hacerlo si
ya está configurado. El coste se registra como `change_help`, sin AgentRun.
Ante proveedor deshabilitado, timeout, respuesta inválida o sugerencias de
comandos, se mantiene la explicación basada en el estado. El modelo no decide
los hechos, la revisión, la etapa ni la navegación; sólo aporta texto explicativo.
El prompt no incluye logs, rutas, credenciales, conversación completa o URLs.
Los errores guardados se presentan con referencias y secretos redactados.
La entrega administrativa respeta el rol y su flag existente; una explicación
no los habilita ni sustituye las comprobaciones normales de factores/operación.
El catálogo de cuatro validaciones está confirmado para Atenea (projectId=1).
Para otros proyectos sin un catálogo confirmado, se ofrece orientación de estado
sin inventar pruebas o publicaciones. Esto no anuncia soporte de entrega universal.

## Contrato de Codex gestionado

El runner Platform añade `atenea-managed-change-guidance/v1` sólo después de
validar source/ownership del workload project-codex-v4 y el AGENTS.md exacto.
No se selecciona ese modo por ruta, nombre de rama, texto del operador o campos
del request. No se reescriben los bytes revisados del repo, refs o remotos.
Los guards del workspace manual github/main → origin/main siguen vigentes.

El contexto indica que la topología del mirror gestionado no imita la del
workspace manual, diferencia pruebas pendientes/fallidas y pide una respuesta
final con un paso móvil concreto. Ante una discrepancia de ownership real se
sigue deteniendo el trabajo; no se relajan permisos ni se fabrican evidencias.

## Aceptación previa a publicar

Tests de decisiones por estado, autenticación/cuerpo cerrado, revisión/definición
vigentes, redacción, timeout del explicador, restricciones del contexto worker,
cliente Android sin fallback a ejecución y navegación sin envío/validación.
Tras integración y rollout autorizado de App + Platform + Android, comprobar
desde el mismo Cambio real: ayuda → pregunta → más fácil → abrir Validar cambio.
La aceptación móvil y una validación real no se declaran PASS sólo por estos tests.
