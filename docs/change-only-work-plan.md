# Modelo único de trabajo por Cambios

Autorización del operador: 12 de octubre de 2026. Retirar el modelo de
WorkSessions independientes y las pruebas, no mantener compatibilidad ni un
historial navegable de ellas. Conservar el cambio Apache entregado y WS21.

## Secuencia cerrada

1. Inventario read-only exacto de App PROD y AX42, dependencias y recursos.
2. App/Android/web: sólo crear trabajo mediante DevelopmentChange. Retirar
   creadores legacy, sesiones de rescate independientes y recuperación fresh
   que crea sucesoras sin cambio. Mantener las operaciones del cambio actual.
3. Platform: quitar dependencia de WS19 antes de limpiar sus archivos; conservar
   autoridad canónica, ownership, v4, catálogo y árbol Codex gestionado.
4. Tests de aislamiento, rechazo de sesiones sin cambio, flujo móvil, purga
   efímera con FK y conservación de WS21. Validación postcommit y CI normales.
5. Backup verificable de PostgreSQL/archivos afectados; rollout coordinado,
   limpieza por identidades verificadas y publicación Android compatible.
6. Aceptación Samsung: único recorrido, sin fixtures/historias legacy.

No modificar worktrees ajenos, resetear IDs, reescribir Git/Flyway, eliminar
usuarios/sesiones de autenticación, backups, releases Codex ni infraestructura
compartida. Los datos de pruebas se purgan mediante un plan de operador exacto,
no una API de borrado arbitrario. La restricción final exige que cada WorkSession
tenga un DevelopmentChange del mismo proyecto. Un nuevo cambio puede todavía
no tener WorkSession mientras se prepara; un reintento conserva su identidad.

## Progreso

Primera unidad implementada: creadores independientes retirados de API, Core,
Android y web; rescate y sucesoras fresh retirados. El único constructor de
WorkSession de producción permanece en RemoteSessionService y enlaza su Cambio.
La web incorpora el recorrido v2 y Activos/Historial; Android usa Nuevo cambio,
Ver cambios y Continuar sobre la conversación existente. No es todavía la
limpieza completa ni una aceptación de los demás proyectos.

Platform verifica el registro root-owned v4-only por sus bytes e identidad
canónicos, sin depender de un journal o borrador de WS19. No se desplegó.

Inventario PROD read-only: 18 WorkSessions, 16 sin Cambio, WS20 ligada al canary
y WS21 ligada al Apache real entregado. Cinco cambios ABANDONED y uno COMPLETED.
Cero AgentRuns y validaciones no terminales. Los registros legacy incluyen
proyectos 7/9; sus repositorios y servicios reales no se borran. AX42 tiene
además recursos sin correspondencia en PROD y registros de pruebas de otros
runtimes: no se eliminan por asumir que todo un directorio sea descartable.

Validación de esta unidad: backend completo 1173 PASS; Android 166 PASS y
compilación instrumentada PASS; web build y dos recorridos responsive PASS;
installer Platform PASS, bash -n y git diff --check PASS. Los tests del contrato
local de sesiones independiente se retiran con él y se sustituyen por rechazo
de sus entradas, sin efectos, y las suites positivas change-owned existentes.

Segunda unidad: purgador DB de operador implementado, sin API de borrado ni
parámetros de targets. Plan read-only PROD reconoce 17 WS/5 cambios/2 rescates
y sus dependencias; protege WS21 y el Apache real. Backup PostgreSQL16 del mismo
snapshot, permisos DELETE sólo para filas congeladas en transacción no publicada,
restauración byte por byte de guards antes de COMMIT, comparación exacta de
datos retenidos y evidencia root-owned/reconciliable. Tests PostgreSQL efímeros:
19 de purga + 8 de regresión de journals = 27 PASS; bash -n y diff-check PASS.
Véase docs/change-only-data-cleanup.md. No se ejecutó la purga.

Pendiente: productor cerrado Platform para retirar/respaldar recursos físicos y
emitir su recibo (el purgador DB lo exige, no acepta fabricación manual),
restricción NOT NULL final en Flyway, eliminación de ramas legacy restantes y aceptación.
No desactivar triggers de inmutabilidad ni hacer DELETE global como atajo.
Integración, rollout y APK sólo cuando el conjunto esté listo. Ninguna fila,
workspace, enlace Codex o runtime compartido ha sido modificado en esta unidad.

Ramas feature/change-only-model y feature/change-only-runtime; bases App
26a1e620 y Platform 66f1d308. Evidencia de operador fuera del runtime activo:
/home/jose/atenea-change-only-evidence.di6uxy/.
