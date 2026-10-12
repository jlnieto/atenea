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

Tercera unidad App: V94 exige el vínculo NOT NULL Sesión→Cambio del mismo
proyecto; rechaza explícitamente datos antiguos sin limpiar. No adopta ni borra
filas. El cierre administrativo legacy se retira del backend, incluidos su
reconciliador periódico y la inferencia de una sesión histórica predecesora.
Las pruebas de persistencia pasan a fixtures change-owned. Esta adaptación
detectó dos guards de replay/retry de adjuntos que aún exigían workload v3;
ahora reconocen el snapshot v4 del mismo Cambio sin duplicar turno/run y sin
relajar el binding inmutable de adjuntos. El validador de adjuntos sigue siendo
una autoridad independiente; no se ha ampliado su catálogo ni desplegado nada.

Validación tercera unidad: backend completo 1169 PASS, sin fallos ni errores,
incluidos cuatro tests V94 y las suites de purga/journals. Diff-check PASS.
La validación UFD postcommit se ejecuta antes de cualquier PR del conjunto.

Platform incorpora manifiesto cerrado y plan read-only de los 25 roots. El
smoke real está BLOCKED: nueve roots sin correlación PROD y sandbox WS4/Beautips
con tres contenedores, red y cuatro volúmenes; su rama WIP se protegió en esa
unidad hasta recibir la autorización explícita de retirada de la unidad 4.
WS6/19 requieren respaldo de archivos no committeados. No se emite un recibo
RELEASED ni se deduce que un recurso sea descartable por no aparecer en PROD.
Véase ops/worker/CHANGE_ONLY_CLEANUP.md en el repositorio Platform.

Cuarta unidad Platform: el operador autoriza también retirar la rama WIP
antigua de Beautips, no mantenerla activa. Correlación de los nueve recursos
cerrada con las cinco DB App, metadata/journals y fixtures documentados.
Productor físico cerrado implementado: backup verificable de código/datos,
bundles, retiro por IDs/SHAs, cuarentena fuera del producto, contracción del
índice worker conservando Apache/validaciones, journal/replay y recibo real.
Installer instala/verifica pero no ejecuta ni delega cleanup al worker.
Plan real READY_FOR_MAINTENANCE: 24 roots, 25 refs, 10 recursos Docker.
Tests: 17 productor + 12 preflight PASS; installer/schema/bash/diff-check PASS.
No se ha aplicado. El mantenimiento y la instalación de la verificación
independiente de WS19 siguen siendo obligatorios para la retirada real.

Pendiente: eliminación de clientes/flags/tablas legacy restantes; integración
del conjunto, instalación/mantenimiento y ejecución de ambos purgadores,
rollout, publicación Android y aceptación. El productor físico está preparado,
pero su recibo real todavía no existe y el purgador DB sigue exigiéndolo.
No desactivar triggers de inmutabilidad ni hacer DELETE global como atajo.
Integración, rollout y APK sólo cuando el conjunto esté listo. Ninguna fila,
workspace, enlace Codex o runtime compartido ha sido modificado en esta unidad.

Ramas feature/change-only-model y feature/change-only-runtime; bases App
26a1e620 y Platform 66f1d308. Evidencia de operador fuera del runtime activo:
/home/jose/atenea-change-only-evidence.di6uxy/.
