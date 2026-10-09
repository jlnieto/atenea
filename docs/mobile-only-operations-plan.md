# Operación completa de Atenea desde el móvil

El objetivo es desarrollar y operar los proyectos autorizados usando únicamente
el Samsung: implementar, resolver bloqueos, validar, revisar, integrar, publicar
y recuperar operaciones. Una recuperación que necesita esta sesión de Codex CLI
no cuenta como aceptación móvil. App tiene un único runtime compartido, PROD;
los tests y ensayos de fallos usan entornos efímeros, no un App DEV permanente.

Este plan fija cuatro entregas consecutivas. Sólo hay una entrega activa y no se
declara completa porque compila, tiene CI verde o dispone de botones. Debe pasar
su prueba de aceptación con el dispositivo real.

## Estado actual y siguiente paso

Entrega activa: 1, resolución de conflictos desde la WorkSession.

Caso de aceptación: el ticket de monitorización de Apache, WorkSession 21 y
[PR 47](https://github.com/jlnieto/atenea/pull/47). Conservamos el mismo
DevelopmentChange, WorkSession, rama y PR. La funcionalidad del ticket sigue
pendiente de integración y publicación; crear la PR no la despliega.

Al comenzar este trabajo, el head publicado es
`d364ccef201526821b65600dd7301a90586eeb9a`, la revisión 3 conserva 4/4
validaciones y GitHub declara la PR con conflictos. App main es
`2b271aacd5f23532eadc8946a958667eb4bba690` y Platform main es
`3c953b5639c4a68b860782391545c1c5e0980de3`. Son referencias iniciales de
diagnóstico, no autorizaciones para desplegar ni para usar un main móvil sin
comprobarlo.

Primera unidad implementada y probada: observación read-only de la PR exacta,
rechazo de conflictos separado de CI pendiente o fallido y protecciones
pendientes, y bloqueo de integración en Android ante conflictos o evidencia
no disponible. No está integrada ni desplegada y aún no constituye aceptación
en el Samsung.

Segunda unidad implementada y probada en Platform: contrato
`development-change-source-update/v1`, preparación con main y publicación
predecesora retenidos, consulta sin efectos y recuperación de la misma
operación. Conserva HEAD y la base original; prepara los archivos editables
sin publicar un commit ni validar la nueva fuente. Tampoco está integrada o
desplegada.

Tercera unidad implementada y probada en App: intención durable de resolución,
preparación recuperable y un único resolver en la misma WorkSession. Registra
la revisión preparada y la fuente resultante; las validaciones anteriores quedan
como historial. Android incorpora la acción cerrada Resolver conflictos y su
estado dentro del panel existente. No está integrada ni desplegada.

Cuarta unidad implementada y probada en App y Platform: finalización autorizada
de la fuente validada, commit que incorpora main sin reescribir historia y
actualización de la misma PR. La acción móvil Actualizar la misma PR conserva
WorkSession, rama y número; GitHub/UFD comprueban el nuevo head antes de integrar.
No está integrada ni desplegada.

Unidad 5 en curso: reintento explícito del resolver fallido, observación de sus
archivos parciales y pruebas combinadas de interrupciones implementados localmente.
Conserva el intento fallido, el mismo prompt y la preparación. No está integrada
ni desplegada.

Continuación ante otro avance de main implementada localmente: parte de una
preparación completada, antes de publicar sus archivos o después de actualizar
la misma PR. Conserva archivos, historial y recibos; exige validación vigente.
No está integrada ni desplegada.

Siguiente paso: cerrar la revisión de interrupciones cuando main avanza con una
preparación pendiente o una publicación incierta. Esos estados siguen fijados a
su intención original y no se sustituyen automáticamente. Después, integración
conjunta. No resolver manualmente PR 47 para sustituir estas capacidades.

## Reglas para terminar sin ampliar el alcance

- Cada cambio debe cubrir un criterio de la entrega activa o un bloqueo que
  impida comprobarlo. Registrar ese vínculo antes de ampliar el diff.
- Las mejoras no necesarias se dejan en una lista posterior. No mezclar
  rediseños visuales, funcionalidades nuevas o cambios de runtime Codex.
- Ante un fallo, conservar código, mensaje, fase e identidades de la operación.
  Diagnosticar antes de reintentar; no recrear el ticket para ocultarlo.
- No repetir suites completas ya pasadas sin una obligación del repositorio o
  un cambio que invalide su evidencia.
- Mantener separados implementación, tests, integración, despliegue y
  aceptación. Un PASS de una fase no representa las siguientes.
- Después de un commit y antes de abrir PR App, ejecutar
  `./scripts/validate-change` y respetar el plan producido.
- Actualizar el registro de progreso al terminar cada unidad. Indicar siempre
  resultado verificado, bloqueo y siguiente paso; no pedir al usuario que
  recuerde el historial de esta conversación.

## Entrega 1 Resolución de conflictos desde el móvil

### Alcance

App observa y explica el estado de integración de la PR exacta. Platform
prepara la incorporación de un main aprobado al workspace propio del cambio.
Codex resuelve el contenido en esa misma conversación y App registra la nueva
fuente antes de validarla y actualizar la PR existente.

La operación debe conservar una intención durable y continuar al cerrar el
móvil o reiniciar App. La consulta no crea, ejecuta ni reintenta operaciones.
Un doble toque o una respuesta perdida no crean otra actualización o AgentRun.

El main objetivo y el head predecesor se fijan antes del efecto. No se aceptan
repositorios, rutas, comandos, hashes o credenciales elegidos por el cliente.
Un cambio concurrente de referencias, ownership ambiguo, workspace con cambios
no incorporados o ejecución activa bloquea la preparación con una causa clara.

No reescribir historia, hacer force push, sustituir la base original sellada ni
relajar ownership. La versión incorporada de main es evidencia nueva y separada
de la identidad de creación. Los conflictos se preparan mediante un mecanismo
que permita verificar el estado retenido y recuperar una interrupción.

Codex puede resolver archivos y explicar sus decisiones, pero no elegir
credenciales ni ejecutar acciones de producción. Ante una ambigüedad funcional
debe preguntar en la conversación. Su finalización no equivale a validación,
commit publicado, integración ni despliegue.

### Secuencia de desarrollo

1. Estados de integración efectivos y UI que no confunda conflicto con espera.
2. Contratos versionados y operación durable de preparación y recuperación en
   Platform, con ownership, head y main retenidos.
3. Orquestación App de la resolución en la misma WorkSession y observación de
   una revisión nueva, conservando las evidencias anteriores como históricas.
4. Actualización de la misma PR por el publicador autorizado, validación del
   head nuevo y habilitación de integración sólo con evidencia vigente.
5. Tests de interrupción, duplicados, referencias movidas y recursos ajenos;
   integración App y Platform conforme a sus reglas.
6. Despliegue coordinado y APK autorizados por separado; aceptación en Samsung.

### Criterios de aceptación

- [ ] Una PR conflictiva se muestra como conflicto, no como GitHub ejecutándose.
- [ ] La integración queda deshabilitada ante conflictos o evidencia ilegible.
- [ ] Una rama desactualizada sin conflictos se actualiza sin reescribir historia.
- [ ] Codex resuelve los cinco archivos de tests de PR 47 desde WorkSession 21.
- [ ] La nueva fuente exige validación nueva; no reutiliza el 4/4 anterior.
- [ ] Se actualiza la misma PR y conserva el historial de publicación anterior.
- [ ] Doble toque, respuesta perdida y reapertura no duplican efectos o runs.
- [ ] Un estado ajeno o movido se rechaza sin cambios parciales no reconciliables.
- [ ] El usuario completa resolución y validación desde el Samsung sin una
      reparación manual de la rama en CLI.
- [ ] Tras revisión y autorización independientes, integra y publica el ticket
      Apache desde el móvil y comprueba su comportamiento funcional.

## Entrega 2 Mantenimiento de App Platform y proyectos autorizados

Completar desde el móvil el desarrollo, validación, PR, revisión, integración y
publicación de App y Platform. El publicador AX42 actual instala Platform main
ya integrado; eso no representa una entrega completa del repositorio Platform.

Añadir la sincronización y promoción comprobadas del mirror y configuración
canónicos para que las nuevas WorkSessions nazcan de la fuente aprobada.
Publicar una imagen no supone haber actualizado esas autoridades de Git.

Cada proyecto utiliza un registro autorizado de repositorios, recursos,
validadores y acciones de publicación. No se convierte un proyecto nuevo en
una selección libre de hosts, rutas o comandos. La habilitación inicial de
recursos y credenciales necesita autorización de operador.

Los diagnósticos y resultados se localizan por proyecto, ticket y operación;
el usuario no necesita trasladar UUIDs del móvil al portátil. La revisión debe
ser practicable desde el móvil y las acciones sensibles conservan la
confirmación específica, no permisos por cada edición de fichero.

Aprobación de esta entrega:

- [ ] Un cambio que modifica App y Platform completa ambos recorridos desde el móvil.
- [ ] Contratos, orden de despliegue y compatibilidad de predecesores quedan verificados.
- [ ] Una nueva WorkSession usa el código aprobado tras la sincronización controlada.
- [ ] Al menos otro proyecto autorizado completa su recorrido con sus recursos reales.
- [ ] Los fallos conservan su evidencia y ofrecen consulta o recuperación soportada,
      sin pedir copiar secretos ni identificadores en conversaciones externas.

## Entrega 3 Recuperación independiente de App

Proporcionar acceso de operador desde el navegador del Samsung aunque App esté
caída. Reutilizar el ejecutor independiente de publicaciones y mantener
autenticación fuerte, autoridad mínima y auditoría fuera del backend afectado.
No crear un segundo App DEV ni entregar SSH o root de producción a AgentRuns.

El catálogo de emergencia se limita a salud y diagnósticos sanitizados,
consulta y reconciliación de operaciones, reinicio de componentes autorizados y
retorno a una versión verificada cuando sea compatible con la base de datos.
Un rollback fallido no se convierte en permiso para forzar otra publicación.

Restaurar PostgreSQL es una operación distinta: backup verificado, plan de
restauración, compatibilidad, exclusión de ejecuciones y autorización explícita.
No incluir una restauración destructiva como efecto implícito de recuperar App.

La pérdida completa de un host necesita acceso de emergencia al proveedor
desde el móvil y copias recuperables. La página alojada en ese mismo host no
puede solucionar por sí sola su pérdida. Verificar acceso y factores sin
exponerlos en documentación, Git o conversaciones.

Aprobación de esta entrega:

- [ ] El navegador del móvil consulta y autoriza recuperaciones sin depender de App.
- [ ] La recuperación conserva las identidades durables y no repite efectos aplicados.
- [ ] Un ensayo aislado recupera App y comprueba salud, datos y versión efectiva.
- [ ] La vía del proveedor y el procedimiento de copias están accesibles desde el móvil.
- [ ] No se debilitan ownership, autenticación ni protecciones de datos para desbloquear.

## Entrega 4 Aceptación completa antes de depender sólo del móvil

El usuario realiza en Samsung el recorrido completo: implementar, corregir una
prueba fallida, resolver conflictos, validar, revisar, integrar, publicar,
instalar Android cuando corresponda y hacer el smoke. Cerrar y volver a abrir
la aplicación durante una operación debe recuperar su resultado sin repetirla.

Los fallos peligrosos se prueban en fixtures o entornos efímeros. No provocar
una caída, corrupción o rollback fallido en PROD para demostrar recuperación.

El informe final debe vincular cada aceptación a su prueba, operación, versión
y resultado. Debe listar cualquier limitación restante y verificar que ningún
paso rutinario exigió CLI, portátil o intervención manual oculta del asistente.

- [ ] Recorrido funcional completo desde el Samsung con resultado publicado y verificado.
- [ ] Recuperación de fallos de tests, Git, transporte y publicación demostrada.
- [ ] Acceso de emergencia practicable y recuperaciones ensayadas de forma segura.
- [ ] No quedan intervenciones de escritorio necesarias dentro del alcance aceptado.

## Registro de progreso

2026-10-08: plan de ejecución establecido. Entrega 1 en desarrollo; entregas
2, 3 y 4 pendientes. Primera unidad implementada en la rama
`feature/mobile-conflict-recovery`: 54 tests focales backend y API PASS en
PostgreSQL 16 efímero sin acceso a red; 13 tests Android API y UI PASS;
`git diff --check` PASS. La validación postcommit del repositorio se ejecuta
antes de abrir cualquier PR de esta entrega.

La PR 47 y su fuente original no se han actualizado, integrado ni desplegado
durante este inicio. El entorno efímero de tests se elimina tras conservar sus
resultados; App, Platform instalados y bases de datos compartidas no se modifican.

2026-10-08: segunda unidad de la entrega 1 implementada en Platform, rama
`feature/mobile-conflict-recovery`. PASS: 32 tests nuevos de preparación,
recuperación, idempotencia, ownership, referencias movidas, autenticación,
exclusión de ejecuciones y retención de objetos ante Git GC; 29 tests existentes
del workspace/publicador; 32 tests focales del worker y admisión v4. Installer,
schemas Draft 2020-12, `bash -n` y `git diff --check` PASS.

La intención y los árboles se guardan antes de modificar el workspace. La
recuperación de una preparación parcial sólo admite los bytes originales o
preparados y no pisa ediciones posteriores. Un ref privado conserva el árbol
frente a Git GC; no se mueve ninguna rama. La consulta nunca materializa, y
un replay terminado conserva las ediciones del resolver.

Precondición de preparación: el main retenido debe coincidir con GitHub y con
el mirror canónico, y la rama publicada con el head predecesor. Esta operación
no hace un fetch ni promueve configuración canónica de forma implícita. Una
discrepancia bloquea con `SOURCE_UPDATE_REF_MOVED`; no se elige otro main ni se
fuerza un ref. Un lock Git de propietario desconocido también bloquea sin
borrarlo. Estas precondiciones se comprobarán antes de la aceptación real.

2026-10-08: tercera unidad de la entrega 1 implementada en App, rama
`feature/mobile-conflict-recovery`. PASS: 113 tests backend focales mediante
`scripts/test.sh` en PostgreSQL 16 efímero, migración V86 incluida; 18 tests
Android API y estado del panel; `git diff --check`.

La petición móvil es vacía: App fija main, publicación predecesora, operador,
ownership e identidades de operación antes del efecto. Un doble toque devuelve
la misma intención; una respuesta perdida se consulta y reconcilia con los
mismos identificadores. La preparación y los resultados del resolver se
persisten con revisiones distintas; el registro de publicación anterior y las
validaciones históricas permanecen intactos. Sólo el turno ATENEA ligado a esa
intención puede admitir un nuevo AgentRun en una WorkSession publicada.

Mientras la preparación o resolución están pendientes no se puede promover
una validación histórica, publicar, cerrar esa WorkSession ni iniciar otra
operación de su workspace. La admisión de runs y validaciones queda serializada
con la barrera de publicación existente. Si hay atención pendiente, el resolver
ya admitido conserva su vía de reconciliación y cierre terminal; no se crea otro.
La respuesta HTTP tiene límite de tamaño y plazo, incluido un cuerpo incompleto.

Terminar Codex no demuestra que los conflictos estén resueltos ni permite
integrar. Un fallo se conserva y no lanza otro resolver automáticamente. La
recuperación explícita de un resolver fallido y las pruebas combinadas de
interrupciones pertenecen a la unidad 5; esta unidad no ofrece una reparación
manual ni una segunda ejecución implícita.

2026-10-08: cuarta unidad implementada en ambas ramas
`feature/mobile-conflict-recovery`. PASS: 141 tests backend focales en
PostgreSQL 16 efímero, migración V87 incluida; 19 tests Android API/panel;
220 tests Platform y del validador, con cinco tests previos de operaciones
root omitidos al ejecutarse como usuario sin privilegios; installer,
schemas Draft 2020-12, `bash -n` y `git diff --check`.

La nueva intención exige autorización administrativa de publicación y las
cuatro comprobaciones con sus definiciones vigentes para la fuente actual.
V87 conserva por separado preparación, publicación predecesora, validación y
recibo final. Una respuesta perdida consulta la misma intención antes de
reanudar. Las barreras impiden validar, admitir nuevas ejecuciones, cerrar la
sesión o operar su workspace mientras esa publicación es incierta.

Platform sella y retiene el candidato antes de mover la rama. El commit tiene
dos padres exactos: head publicado anterior y main fijado. Sólo permite push
fast-forward normal, sin force, conservando base, registros anteriores y
archivos resueltos. App no crea una PR sustituta si la original desaparece o
cambia de identidad. Integrar exige evidencia de GitHub/UFD para el nuevo head,
no la finalización de Codex ni un resultado histórico.

La observación del worker y el validador consumen la misma autoridad de
preparación sellada para reconocer el head predecesor al validar archivos
resueltos. La base de creación permanece inmutable. Un registro inválido
bloquea sin elegir otro commit ni recurrir a una regla más permisiva. Para una
preparación limpia, App consulta el fingerprint real en vez de conservar el
hash histórico o implementar el algoritmo del worker.

Pendiente: unidad 5 y después despliegue/aceptación autorizados. La recuperación
explícita del resolver fallido, las pruebas combinadas con cambios de refs y
la transición a otra preparación siguen formando parte de ese cierre.
WS21 y PR47 permanecen fuera de estas pruebas. No hay despliegue ni aceptación
móvil de la entrega 1.

2026-10-09: primera parte de la unidad 5 implementada localmente. PASS: 174
tests backend focales con PostgreSQL 16 efímero y Flyway V88; 21 tests Android
API/panel; 30 tests Platform de finalización, incluidos dos recorridos nuevos
de interrupción combinada y main concurrente; `git diff --check`.

Reintentar resolución es una autorización explícita del administrador para
un intento fallido concreto. V88 añade un registro inmutable por reintento,
sin reemplazar el run original, el turno, el recibo de preparación ni la
publicación anterior. Reutiliza el coordinador, el perfil y el prompt existentes.
La acción genérica de reintento no se ofrece para esos resolvers ni admite
una vía alternativa sin la autorización específica. La consulta y una petición
repetida no admiten otro run; dos solicitudes
simultáneas conservan un único reintento. Un callback perdido deja un run QUEUED
committeado para el coordinador normal. Terminar ese run sigue exigiendo nueva
validación antes de actualizar la misma PR.

El reintento verifica ownership y fuente efectiva antes de admitirlo. Un
bloqueo determinista no resuelto, otra ejecución activa o archivos cambiados
lo rechazan sin reset ni reparación manual. Esta primera parte sólo recupera
la fuente intacta del intento fallido. Falta autorizar y observar una fuente
parcial nueva y encadenar otra preparación cuando avance main; ambos casos
siguen pendientes antes de integrar la entrega.

2026-10-09: recuperación de archivos parciales implementada localmente en App.
PASS: 180 tests backend focales en PostgreSQL 16 efímero, incluidos 44 de
preparación/reintento/finalización y el upgrade V88 a V89 con historial existente;
`git diff --check`. Los informes se conservan fuera del checkout.
V89 vincula cada reintento a su fuente observada, sin reemplazar la preparación,
el primer resolver ni los registros V88. La observación fija HEAD y ownership;
un hash o estado limpio/sucio distintos producen una revisión nueva e invalidan
las proyecciones anteriores. No hay reset ni escrituras remotas durante esta
observación. La admisión y el coordinador normales usan ese nuevo binding.

La respuesta móvil muestra la revisión del último reintento, no la preparada
original. Un doble toque bloquea la sesión antes de leer la fuente y recupera
el mismo run; no duplica la observación ni el prompt. La fuente limpia conserva
el hash de observación en App y usa ownership limpio en el protocolo del runner.
Su validación sigue exigiendo cuatro comprobaciones actuales antes de publicar.

La migración preserva los bindings V88 existentes y mantiene explícitamente
ausente la observación HTTP histórica; no fabrica evidencia retrospectiva.
Las pruebas cubren archivos parciales, fuente limpia, dos fallos consecutivos,
peticiones simultáneas, rechazo de HEAD/ownership/hash ajenos, validación nueva
y actualización de la misma PR en fixtures. Falta la transición a otra
preparación ante un nuevo avance de main, incluidos los estados previos a
publicar. La entrega 1 aún no está lista para integrar o aceptar en el móvil.

2026-10-09: continuación versionada implementada localmente. PASS: 185 tests
backend focales con PostgreSQL 16 efímero, Flyway V90 y upgrade V89 a V90;
10 tests nuevos de continuación Platform, 32 de preparación v1, 30 de
finalización v1, 21 del validador privilegiado (otros 5 requieren root y quedan
omitidos), installer y `bash -n`. UFD posterior al commit se registra por separado.

V90 conserva la preparación predecesora mediante una relación inmutable. App
deriva el nuevo main, revisión y recibos; el móvil sólo solicita Actualizar base
con main. Si main no cambió, devuelve la misma operación. La capability
`development-change-source-update/v2` conserva el contrato v1 y añade exactamente
el ID/recibo de preparación predecesora y la revisión publicada.

Platform sella cada continuación en su propio journal y conserva un checkpoint
privado de los archivos resueltos antes de materializar. Una autoridad activa
sellada enlaza la preparación actual; los recibos anteriores no se reemplazan.
Después de publicar, el predecesor es el nuevo head publicado de la misma rama.
Los tests incluyen tres generaciones, GC, doble solicitud, interrupción antes
de materializar y rechazo de archivos, hashes y refs ajenos sin reset.

Límite pendiente: una preparación QUEUED/PREPARE_CLAIMED/UNCERTAIN no adopta otro
main; una publicación no confirmada PUBLISHED impide otra preparación. Falta
cerrar la recuperación explícita de esas fronteras sin borrar evidencia ni
relajar la comprobación del main retenido. La entrega 1 aún no está lista para
integración conjunta o aceptación móvil. WS21 y PR47 no se han operado.

## Autorización de efectos reales

La autorización actual cubre desarrollo y tests. Aplicar migraciones
compartidas, instalar componentes, cambiar recursos o secretos, desplegar,
publicar APK, restaurar datos o ejercitar recuperaciones reales necesita una
autorización específica. Solicitarla para un plan concreto, no por cada fichero.

## Referencias

- [Flujo móvil existente](mobile-delivery-v1.md).
- [Autoridades Git y procedimientos existentes](mobile-server-operations.md).
- Platform: `ops/release/README-release-control-v1.md` y contratos bajo
  `runtime-contract/`; la implementación del worker permanece en ese repo.
