# Retirada de trabajo y conservación del historial

Completar un cambio entregado y abandonar trabajo descartado son operaciones
distintas. Retirar una prueba nunca integra su PR ni publica su código.

## Sin WorkSession activa

Se utiliza la operación existente `POST /api/v2/projects/{projectId}/development-changes/{changeKey}/abandon`
con `Idempotency-Key`. El cambio pasa a `ABANDONED`; el código y los recibos no se
borran. Sus workspaces pueden permanecer preparados como evidencia conservada.

## Con WorkSession remota

`POST /api/mobile/sessions/{sessionId}/retirement`, con cuerpo exactamente `{}` y
`Idempotency-Key`, requiere un operador activo `PLATFORM_ADMINISTRATOR`, el gate
existente de liberación remota y un propietario Atenea/AX42 exacto. Se admite una
sesión OPEN o CLOSING sin operación de cierre previamente solicitada. Una sesión
DRAFT_BLOCKED utiliza su flujo específico de recuperación de borrador; este
procedimiento no cambia ni elude sus restricciones.

La migración V93 persiste un único intent por sesión, actor, key y propietario
inmutable. No se aceptan comandos, rutas, factores, versiones ni un propietario
aportado por el cliente. La admisión de AgentRuns y validaciones queda bloqueada
por ese intent. Se comprueba inactividad antes de solicitar y antes de liberar.

El reconciliador usa el protocolo instalado de liberación de workspace: comprueba
ownership, conserva worktree/Git/borradores/conversación/adjuntos/artefactos y
libera sólo recursos efímeros autorizados. No necesita una PR integrada, un árbol
limpio ni evidencia de publicación. El UUID de cierre es el UUID durable del
intent. Una interrupción de transporte retiene el mismo intent; no crea otro.

Sólo un recibo validado RELEASED de revisión final permite cerrar la WorkSession y
marcar su DevelopmentChange ABANDONED. La evidencia guarda el recibo y declara
`ABANDONED_NOT_DELIVERED`; no altera las proyecciones de entrega para fingir éxito.

Un rechazo conserva estado BLOCKED y sus intentos. `GET .../retirement` consulta
sin ejecutar. `POST .../retirement/reconcile` reutiliza la key y el actor originales
y vuelve a comprobar autoridad, propietario e inactividad. No hay reparación SQL,
borrado forzado de worktrees ni reapertura del trabajo.

## Android

Cambios separa Activos de Historial (COMPLETED y ABANDONED). Las sesiones CLOSING
se muestran como cierre pendiente, no como trabajo que puede continuar. Los
cambios abandonados con un recibo de retirada conservan acceso a su conversación.
Abandonar requiere confirmación explícita. Preparar un cambio existente utiliza
su identidad, nunca crea otro ticket ni duplica un prompt.

## Rollout

La validación usa PostgreSQL efímero; no aplica V93 en PROD. Integrar, aplicar V93,
desplegar App y publicar una APK compatible son pasos operativos separados. Después
pueden retirarse las sesiones 19 y 20 mediante estas operaciones, comprobar los
recibos y confirmar que sus borradores/Git permanecen intactos.

Preflight real del 12 de octubre: la sesión 20 admite liberación. La sesión 19
queda bloqueada por `WORKSPACE_RELEASE_PREFLIGHT_REJECTED`: el liberador legacy
exige selection/execution activados y commit del propietario, mientras la
configuración instalada conserva WS19 pero usa selection=false y canonical
07fdf9f56333ff8002450d7bc2719ac4000fe4a7. Antes de retirarla hace falta adaptar
Platform al propietario retenido exacto, manteniendo v4 habilitado y su borrador.
No reactivar configuración legacy ni forzar una liberación.
