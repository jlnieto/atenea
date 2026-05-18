# Android WorkSession Premium Operation

Este documento fija el contrato de calidad para operar una `WorkSession` completa desde Atenea Android cuando el operador solo tiene el movil delante.

## Principio

La app nativa no debe ser una copia de la app no nativa. La app nativa debe ser la consola de delivery:

- lectura rapida del estado real de la sesion
- acciones seguras ejecutadas por Atenea Core
- conversacion con Codex solo cuando hace falta instruccion
- entregables, cierre, PR y facturacion en la misma unidad operativa
- sincronizacion explicita y degradacion visible si la red falla

## Fuentes

Backend:

- `GET /api/mobile/projects/overview`
- `POST /api/mobile/projects/{projectId}/sessions/resolve`
- `GET /api/mobile/sessions/{sessionId}/summary`
- `GET /api/mobile/sessions/{sessionId}/conversation`
- `GET /api/mobile/sessions/{sessionId}/deliverables`
- `GET /api/mobile/sessions/{sessionId}/events`
- `GET /api/mobile/sessions/{sessionId}/events/stream`
- `POST /api/core/commands`
- `POST /api/core/commands/{commandId}/confirm`

Android:

- `android/api/src/main/java/com/atenea/android/api/AteneaApiClient.kt`
- `android/core-console/src/main/java/com/atenea/android/coreconsole/ProjectsScreen.kt`
- `android/core-console/src/main/java/com/atenea/android/coreconsole/WorkSessionScreen.kt`
- `android/core-console/src/main/java/com/atenea/android/coreconsole/WorkSessionRepository.kt`
- `android/core-console/src/main/java/com/atenea/android/coreconsole/WorkSessionConversationScreen.kt`
- `android/core-console/src/main/java/com/atenea/android/coreconsole/CoreCommandUi.kt`

## Fase 1. Modelo local

Implementado:

- DTOs Android para `MobileSessionSummary`, acciones, insights, entregables, presupuesto aprobado y eventos.
- Parser robusto para `WorkSessionConversationViewResponse`, tanto cuando llega envuelto como cuando llega como vista directa.
- `MobileWorkSession` ampliado con branch, PR, fechas de apertura/publicacion/cierre y bloqueo de cierre.

Regla:

- JSON solo se parsea en `:api`.
- Las pantallas no deciden contratos HTTP.
- Las acciones permitidas vienen de `MobileSessionActions`, no de heuristicas de UI.

## Fase 2. Vista WorkSession

Implementado:

- entrada desde `Proyectos` hacia `Sesión`
- `Sesión` como destino interno, no global del drawer
- cabecera con estado, sync, branch, PR y bloqueo de cierre
- lectura operativa con progreso, bloqueo y siguiente paso
- panel de acciones Core
- panel de entregables
- presupuesto aprobado con referencia de factura
- timeline reciente de eventos

La vista responde a la pregunta operativa principal: "que falta para poder entregar o cerrar esta sesion".

## Fase 3. Mutaciones por Core

Implementado:

- publish: `publica la pr`
- sync PR: `sincroniza la pr`
- close: `cierra la sesion`
- generar ticket: `genera el ticket de trabajo`
- generar desglose: `genera el desglose de trabajo`
- generar presupuesto: `genera el presupuesto`
- aprobar: `aprueba el deliverable {id}`
- facturar: `marca el deliverable {id} como facturado con referencia {ref}`
- conversacion: cualquier instruccion se envia a Core con scope `SESSION`

Regla:

- La app nativa premium no llama endpoints mobile legacy para mutaciones.
- Toda accion sensible pasa por `CoreCommandResponse`.
- Confirmaciones y aclaraciones se resuelven con `CommandCard`.

## Fase 4. Sincronizacion

Implementado en `WorkSessionRepository`:

- snapshot inicial de summary + entregables
- timeline incremental con cursor temporal de eventos
- refresco adaptativo:
  - rapido mientras hay run, accion o comando pendiente
  - mas lento en reposo
- refresh forzado despues de comandos Core completados
- conservacion del ultimo snapshot si falla la red
- estado stale visible en la pantalla

Esto es robusto para desarrollo y uso interno porque evita pantallas obsoletas sin bloquear al operador.

Estandar premium pendiente antes de produccion final:

- persistir eventos de sesion en backend con `sequence` monotona
- devolver `eventId` estable por evento
- aceptar `afterSequence` en `/events`
- mantener SSE como canal primario y polling como fallback
- incluir `clientRequestId` en comandos Core para idempotencia movil
- deduplicar confirmaciones server-side
- exponer `syncRevision` en `summary` para detectar snapshots antiguos

La arquitectura Android ya centraliza la sincronizacion en un repositorio, por lo que el cambio de polling a SSE persistido no afectara a las pantallas.

## Fase 5. Delivery y cierre

Implementado:

- la vista muestra si los entregables core estan presentes y aprobados
- permite generar cada entregable core desde Core
- permite aprobar entregables desde Core
- muestra presupuesto aprobado
- permite marcar presupuesto facturado si backend habilita la accion
- muestra bloqueo de cierre y accion recomendada
- solo habilita cerrar cuando backend lo marca como permitido

Regla:

- la UI no fuerza cierres ni inventa permisos
- el backend decide `canClose`
- Core conserva la interpretacion y la confirmacion de acciones criticas

## Fase 6. Validacion desde movil

Checklist antes de desplegar una version Android nueva:

1. Ejecutar build Android:

```bash
./scripts/android-build.sh
```

2. Si hubo cambios backend:

```bash
./scripts/test.sh
```

3. Verificar manualmente desde la APK:

- login
- `Inicio`
- `Proyectos`
- abrir `Sesión`
- ver status, insights, PR y timeline
- abrir `Conversacion`
- enviar una instruccion scope `SESSION`
- volver a `Sesión`
- ejecutar `Sync PR` si hay PR
- generar un entregable en una sesion no cerrada
- comprobar que errores de red dejan snapshot stale y no pantalla vacia

4. Publicar APK si procede:

```bash
./scripts/android-publish-apk.sh
```

## Criterio de no regresion

Una WorkSession nativa se considera operable cuando desde Android se puede:

- abrir o resolver la sesion
- entender el estado actual
- instruir Codex
- publicar o sincronizar PR
- generar y aprobar entregables
- preparar facturacion de presupuesto aprobado
- cerrar cuando backend lo permita
- ver si el estado esta fresco o degradado

Si alguno de esos puntos no funciona, no se debe publicar una version movil como version diaria.
