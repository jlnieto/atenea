# Purga acotada de las pruebas del modelo anterior

Estado: purgadores App/Platform implementados y probados, todavía sin instalar
ni ejecutar. El recibo físico RELEASED real no existe aún; sólo se genera al
terminar la retirada verificada en mantenimiento. No fabricar ese recibo ni
ejecutar `--apply` para saltarse el orden.

Esta es una transición de operador única, no una nueva API de borrado para el
móvil, ni una compatibilidad con las sesiones anteriores. El respaldo queda
fuera del producto; las filas de pruebas desaparecen del runtime tras la purga.
El cambio real Apache y toda su historia permanecen en el producto.

## Conjunto autorizado

`scripts/change-only-cleanup.py` contiene el manifiesto cerrado del inventario
del 12 de octubre: sesiones 2, 3, 4, 6–17, 19 y 20; cambios 1–5 por sus UUID
exactos; rescates 1/proyecto 7 y 2/proyecto 1, ambos «Rescate operativo» OPEN.
La sesión 21 y cambio 6 (`59315b6e-59bc-4884-9def-356e1ca86ef4`) están protegidos.
Los IDs no son parámetros. Sólo hay dos modos exactos: `--plan` y `--apply`.

Se valida proyecto, estado, worker, identidad remota, rama y binding. Se rechaza
un estado parcial, una sesión/rescate adicional sin revisar, ejecuciones u
operaciones no terminales, dependencias cruzadas y nuevas tablas dependientes.
Las dependencias se siguen hacia hijos, nunca hacia usuarios, proyectos,
workers o repositorios reales. Incluye referencias sin FK en consumo, comandos,
telemetría y pushes. Sólo se limpian los punteros SET NULL de contexto/notas
compartidos; no se borran esos objetos por asociación.

El plan read-only real reconoce 17 WorkSessions, 5 cambios, 2 rescates y 102
AgentRuns de pruebas. Conserva los 9 AgentRuns del cambio real, junto con sus
turnos, validaciones, entregas, cierre y recibos. La selección también incluye
adjuntos, previews, notificaciones y journals estrictamente dependientes. La
comprobación de protección cubre todos los registros restantes; el código no
depende de un simple DELETE WHERE development_change_id IS NULL.

## Barreras antes de borrar

1. Código change-only y Platform preparados e integrados como conjunto. La
   verificación del worker no puede seguir requiriendo el borrador físico WS19.
2. Preparación coordinada de recursos: manifiesto de identidades, backup de
   archivos/Git verificable, ausencia de ejecuciones y liberación/retirada de
   sólo los recursos propios de esas pruebas. Los recursos sin correlación,
   repositorios compartidos y servicios de otros proyectos no se deducen ni
   borran por proximidad. Se debe impedir la recreación durante mantenimiento.
3. Platform debe producir el recibo root-owned protegido
   `/etc/atenea-release-v1/change-only-resources.json`, contrato
   `change-only-resource-cleanup-receipt-v1`: operación `change-only-20261012`,
   estado RELEASED, `preservedChangeKey` real, listas exactas ordenadas de
   `trialSessionIds`/`trialChangeKeys`, `nonTerminalExecutions=0` y
   `archiveSha256` verificable. El productor es
   `ops/worker/change-only-resource-cleanup-v1.py` en Platform; su plan real
   reconoce 24 roots, 25 refs y 10 recursos Docker exactos. Instalación/apply/verify
   siguen pendientes. No sustituir el recibo por un fichero manual o un cliente.
4. Backend PROD detenido mediante el procedimiento operativo; script revisado
   instalado en una ruta root-owned sin padres escribibles ni symlinks. No se
   modifican sus permisos para permitir un script de usuario. Sólo el operador
   root local en el VPS `atenea` puede aplicar; el purgador no detiene ni arranca
   contenedores y no obtiene privilegios mediante Docker.
5. Purgador abre una transacción SERIALIZABLE y bloquea escritura de todas las
   tablas públicas. El dump utiliza su mismo `pg_export_snapshot`, PostgreSQL
   16, formato custom, SHA-256 y `pg_restore --list`. Cualquier fallo de backup
   impide la primera mutación. Destino fijo protegido:
   `/srv/atenea/backups/prod/change-only-20261012/atenea_prod.dump`.

## Transacción y evidencia

El backup y `plan.json` guardan el estado previo. Los triggers no se desactivan,
ni se usa replica, TRUNCATE, CASCADE de esquema o DELETE global. Se verifican
los cuatro guards de journals por su hash y binding/estado de trigger.
Dentro de la transacción no publicada se permite DELETE sólo de la fila JSONB
exacta congelada de las pruebas. Las protecciones de UPDATE y de otras filas
siguen rechazando modificaciones. Las definiciones originales se restauran
byte por byte antes de COMMIT; ninguna conexión externa observa una protección
debilitada comprometida.

Se borra por hojas, conservando todas las FK activas. El único ciclo permitido
es la relación consumo plan/operación legacy: sólo se desprenden los tres
campos de consumo de un plan que va a borrarse y cuyo consumidor también es un
target verificado. Cualquier otro ciclo detiene la operación.

Antes del COMMIT se exige: targets ausentes, WS21/cambio real exactos, datos
retenidos sin cambios, hashes PostgreSQL exactos (sin pérdida numérica por
JSON/Python), tablas ajenas sin cambios y guards originales. Un fallo o
desconexión anterior al COMMIT revierte datos y definiciones. Los backups y la
evidencia de fallos no se borran ni se sobrescriben automáticamente.

Se conservan `backup.json`, `plan.json`, `verified-before-commit.json` y
`committed.json`, root-owned/0600. Si se pierde el acuse tras COMMIT, la misma
operación reconoce ALREADY_CLEAN, verifica evidencia/backup/datos/guards y sella
el acuse faltante sin un segundo DELETE. Si el estado previo se conserva tras
un fallo, se detiene ante un archivo existente: no sobrescribe evidencia ni
decide por sí sola cómo repetir una purga fallida.

## Validación y continuación

Validación focal: 19 pruebas del purgador y 8 de los contratos/journals existentes,
27 PASS. `git diff --check` y `bash -n scripts/ci-backend-test.sh`: PASS.

`ChangeOnlyCleanupIntegrationTest`, mediante `./scripts/test.sh`, ejecuta el
purgador real contra esquemas efímeros migrados a V93 y fixtures sintéticos.
Incluye purga exacta, idempotencia, cuatro journals sellados, ciclo de FK,
postcondición/backup fallidos, aislamiento, hash numérico exacto, ausencia de
argumentos arbitrarios y acuse COMMIT perdido. El dump de prueba se valida con
las herramientas PostgreSQL 16 reales. Las pruebas se destruyen con su runtime;
no suben fixtures ni informes a PROD.

El binding obligatorio está implementado en V94 (NOT NULL y mismo proyecto).
V95 retira, sólo vacíos, `remote_close_legacy_{plan,operation,event}`,
`fresh_work_session_operation`, `rescue_session{,_turn}` y los marcadores
`fresh_start_operation_id`/`replacement_work_session_id`. Cualquier dato
restante, estado UNVERIFIED_LEGACY o dependencia de esquema ajena detiene
Flyway sin borrar/adoptar ni aplicar una contracción parcial. Sólo la referencia
al marcador eliminado se retira del guard de cierre; sus demás protecciones
y los guards de journals se conservan.

Orden obligatorio: backup fresco → mantenimiento/instalación Platform →
purgador físico + verify/recibo auténtico → purga DB + acuse COMMITTED verificado →
App/V94/V95 → arranque/smoke → APK/aceptación. Ante COMMIT ambiguo, reconciliar
el mismo journal ANTES de avanzar a V94/V95. El purgador es una operación única
del esquema previo: no reaplicarlo a una App arrancada/esquema ya contraído;
después se conserva su acuse/backup fuera del producto. No volver a servicio
con una versión que permita sesiones independientes ni rollback de esquema.

Los creadores, clientes/flags fresh y cierre/adopción legacy ya están retirados.
Finalizar/Abandonar Cambio y reconciliar su cierre siguen siendo operaciones
durables. Integración/CI, mantenimiento, ambos purgadores, Flyway compartido y
publicación todavía no se han ejecutado. Ninguna fila PROD se cambia al validar
estas migraciones o pruebas efímeras.
