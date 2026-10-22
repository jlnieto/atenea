# Estabilización de la coordinación del flujo móvil

## Objetivo y alcance

Completar un Cambio desde el Samsung sin ayuda de Codex CLI: implementar,
validar, revisar, integrar, publicar los destinos necesarios y finalizar.
La pantalla debe conservar el contexto y explicar el progreso real; consultar,
recibir un aviso o volver a la app no inicia ni duplica trabajo.

Éste es el seguimiento vigente desde el 22 de octubre de 2026 para la auditoría
y su implementación. Complementa el contrato de
[operación móvil](mobile-only-operations-plan.md) y sustituye sus indicaciones
históricas de siguiente paso. La estabilización completa sigue pendiente.

No se incorporan funcionalidades ajenas a este recorrido. Hay un único runtime
App compartido, PROD; los ensayos usan recursos efímeros. No se recuperan App
DEV ni canary y no se debilitan ownership, aislamiento, firma, permisos, grants
o pruebas obligatorias. Las alertas Apache degradadas deben seguir enviándose
en cada comprobación de cinco minutos: no son las consultas duplicadas que se
pretende eliminar.

## Punto de reanudación

- Rama de continuación: `stabilize/mobile-shared-observations`.
- Worktree manual: `/home/jose/projects/atenea-mobile-observation`.
- Base App: `bb96bab57105f112156dbb2d4029b2ba60df9581`.
- S1 integrado en PR 92, con UFD, backend de merge y siete journeys PASS.
  No está desplegado ni publicado en una APK.
- Bloque activo: S2, consultas coordinadas y ciclo de vida.
- La primera parte de S2 está integrada en PR 93: UFD T3 completo con 320 tests,
  backend de merge y ocho journeys PASS. No cierra todo el bloque S2.
- Siguiente acción: compartir la observación del detalle/progreso de ejecución
  entre Conversación y Recuperación; añadir sus regresiones HTTP/cursor y
  cerrar el propietario STARTED de la vista retenida WorkSessionScreen.
  Conservar las ocho regresiones actuales y el plan UFD íntegro. No modificar
  permisos del dedicado para forzar instrumentación local.
- La rama de continuación contiene sólo el checkpoint documental posterior al
  merge; todavía no hay código nuevo ni PR para los pendientes de S2.
- Despliegues, APK, cambios de configuración y operaciones reales sobre el
  Cambio no están incluidos en este paso de implementación. Requieren su
  autorización y preflight correspondientes.

El trabajo visual y de CI anterior permanece sin commit en
`feature/change-journey-clarity`, worktree
`/home/jose/projects/atenea-change-journey-clarity`. No está desplegado ni debe
copiarse completo sobre esta rama. Revisar y recuperar sólo las partes de S5
después de estabilizar la coordinación; no perder ni sobrescribir ese diff.
El workspace principal también tiene cambios anteriores ajenos a esta rama.

### Referencias operativas observadas el 22 de octubre

Estas referencias identifican el caso real; no sustituyen un preflight fresco.

| Elemento | Evidencia retenida |
| --- | --- |
| Caso | «mejorar vista alertas», Cambio `7a9487aa-66d4-4643-8d9a-d40bf7cf4c7e`, WorkSession 29 |
| App PROD | Fuente `6e3912d0e41b0408a1847eeb738361f963c31257`, healthy |
| Samsung y canal | APK 0.5.135 / 168; fuente del APK `918939a7e44f16459a3834e724ce31e7aa46e588` |
| Cambio | OPEN, revisión 2 validada; PR integrada; backend entregado desde móvil |
| Android | Preparación bloqueada por `APK_VERSION_NOT_NEWER`; solicitudes posteriores rechazadas por `ANDROID_OBSERVATION_BUSY` antes de persistirse |
| Lectura de las 10:22 UTC | Cero AgentRuns activos y cero operaciones `ANDROID_VERSION` de WS29 |

No recrear Cambio, WorkSession, PR ni prompt. No pulsar repetidamente para
sortear BUSY. No fabricar, editar o borrar evidencias para hacer avanzar el caso.

## Hallazgos de la auditoría

### A1 Recepción de avisos y navegación están acopladas

`AteneaFirebaseMessagingService` entrega el aviso al router foreground;
`MainActivity` lo convierte en `requestedConversation`. `AteneaShell` navega y
cambia la clave de composición aunque sea la misma WorkSession.
`ConversationSurface` se recrea en CHAT. La recepción también puede marcar el
aviso como abierto sin una pulsación del usuario.

El mecanismo está demostrado por el código de la APK publicada. Los avisos
durables de WS29 son compatibles con ese recorrido; no hay una traza Samsung
que atribuya cada salto concreto. Un refresco ordinario no cambia por sí solo
el panel seleccionado.

### A2 La lectura de Cambio amplifica observaciones costosas

`MobileDeliveryController.list` llama a `observeDeployment` y a
`observeDelivery`; esta última vuelve a observar el backend si el diff exige
APP_PROD. Una respuesta puede incluir dos OBSERVE_APP y un OBSERVE_ANDROID.
Ayuda vuelve a obtener esas observaciones y Novedades tiene otro sincronizador.

En Platform, `release-control-v1.py` usa el mismo `execution_lock` exclusivo para
observaciones App, Android, Platform y efectos del publicador. Dos lectores
pueden rechazarse con BUSY sin ninguna publicación activa. Una reproducción
local con el fixture cerrado de observación Android confirmó ese caso sin
cambiar evidencia durable ni acceder a PROD. El rechazo también se observó en
las lecturas operativas. No demuestra despliegues duplicados.

### A3 Los consumidores periódicos no tienen un coordinador común

| Consumidor | Comportamiento de la base auditada | Riesgo o límite |
| --- | --- | --- |
| Cambio Android | Repite tras respuesta más 5 s, incluso sin operación activa; respeta STARTED | No agrupa todas las consultas simultáneas |
| Conversación activa | Lanza un refresh cada 3 s | El refresh no se espera y puede solaparse; no está ligado a STARTED |
| Recuperación Android | Dos bucles independientes de 1,5 s | Pueden releer el mismo estado; progreso se solicita desde cursor cero |
| Regreso y consulta manual | Añaden lecturas independientes | Pueden coincidir con las periódicas |
| Validación App | Workflow cada 3 s y reconciliador de resultados cada 5 s | Ambos pueden inspeccionar la misma comprobación RUNNING |
| Novedades App | Scheduler cada 2 s; observación efectiva por página cada 120 s o 30 s tras error | No son tres consultas de red cada 2 s; falta compartir su observación con otros consumidores |
| Relojes de progreso | Actualizan segundos en pantalla | Son locales, no consultas HTTP; conservarlos |

La selección de la respuesta más nueva evita algunas regresiones visuales,
pero no evita las peticiones redundantes. No hay evidencia de ejecución doble
de pruebas por los dos reconciliadores; se ha identificado inspección doble.

### A4 BUSY puede rechazar una intención antes de guardarla

`MobileDeliveryService.requestAndroidVersion` observa el canal antes de crear
`ANDROID_VERSION`. Si la observación falla, no hay operación durable que pueda
continuar sola. La ausencia de esas operaciones en WS29 confirma que los
intentos rechazados no estaban trabajando en segundo plano.

### A5 CI y su progreso no representan el mismo conjunto

Los workflows de merge backend y journeys móviles aceptan `ready_for_review`.
La PR 91 repitió ambos sobre el mismo head y base al retirar el borrador.
`GitHubChecksProgress` no incorpora journeys móviles aunque el gate GitHub sí
los exige. Construir backend y Android en paralelo sí es trabajo válido.
Validar el head, el merge con una base concreta y construir el artefacto de
main son contextos distintos; no pueden deduplicarse sólo por SHA del head.

### A6 Faltan pruebas de convivencia y observabilidad

Las pruebas de componente y los journeys contra pares sintéticos no reproducen
por sí solos el bloqueo real del publicador, push foreground, polling lento y
recreación de pantalla simultáneos. Varios modelos calculan por separado el
siguiente paso; las lecturas pueden dar explicaciones distintas del mismo caso.
Las clases extensas mezclan presentación, consultas y operaciones; el tamaño no
es por sí solo una prueba de mala calidad, pero aquí acompaña responsabilidades
y decisiones duplicadas.

## Plan de implementación y puertas de salida

Estados permitidos en este seguimiento: PENDIENTE, EN CURSO, PROBADO LOCAL,
INTEGRADO, DESPLEGADO y ACEPTADO. No son intercambiables. Cada bloque registra
su commit, comandos/resultados y siguiente acción al terminar una sesión.

| Bloque | Repositorio | Estado vigente | Dependencia |
| --- | --- | --- | --- |
| S1 Navegación estable | App Android | INTEGRADO; PR 92, siete journeys PASS; sin despliegue | A1 |
| S2 Consultas coordinadas del móvil | App Android | EN CURSO; primera parte integrada PR 93, 320 unitarios y ocho journeys PASS | S1 |
| S3 Observación coherente del servidor | App y Platform | PENDIENTE | A2 y A3; protocolo antes de consumidor si cambia |
| S4 Admisión y recuperación durables | App y Platform si el contrato lo requiere | PENDIENTE | S3 |
| S5 CI y progreso completo | App | PENDIENTE; diff previo preservado | Revisar contra S1 a S4 |
| S6 Integración y aceptación móvil | Ambos y dispositivo | PENDIENTE | Todos los anteriores |

### S1 Navegación estable

- Un push recibido sólo informa; no navega ni se marca como abierto.
- Una pulsación explícita abre el destino válido. Abrir un aviso de la misma
  WorkSession no destruye su composición, borrador ni estado del panel.
- Cambiar a otra sesión conserva el aislamiento de estados y permisos.
- Regresiones: dos avisos foreground consecutivos mientras se ve Cambio;
  misma sesión frente a otra; retorno de segundo plano; actualización ordinaria
  conserva panel, texto y scroll. Probar el enlace real entre recepción y
  pantalla, no sólo el parser.

### S2 Consultas coordinadas del móvil

- Un propietario de lectura por recurso y sesión; consultas concurrentes
  comparten o agrupan la misma lectura. Como máximo una petición equivalente
  en vuelo. Los efectos siguen teniendo su identidad independiente.
- STARTED controla el polling visual, no el trabajo durable del servidor.
  Actualizar al volver, con frecuencia adaptada a actividad y errores; una
  observación repetida no habilita y deshabilita acciones por sí sola.
- Reutilizar cursor incremental y no reconsultar detalles terminales sin causa.
- Regresiones con reloj/HTTP controlados: respuesta más lenta que el intervalo,
  consulta manual durante polling, STOP/START, cambio de sesión, cancelación,
  fallos de red y respuesta tardía. Contar peticiones, no sólo mirar el texto.

Pendientes después de PR 93:

1. Un lector compartido del detalle/progreso de ejecución, ligado al cliente
   autenticado, WorkSession y AgentRun exactos. Conversación y Recuperación no
   deben volver a descargar el mismo detalle terminal ni consultar en paralelo
   un progreso equivalente. No compartir datos entre sesiones o identidades.
2. Regresiones instrumentadas de Recuperación con cursor incremental,
   consulta manual durante GET lento, STOP/START y cambio de ejecución.
   Confirmar el número de peticiones, el resultado terminal conservado y cero
   efectos nuevos; no sustituirlas por tests aislados del helper.
3. WorkSessionScreen/WorkSessionRepository: propietario ligado a STARTED,
   consultas manuales y automáticas coordinadas y cancelación propagada. La
   vista retenida no puede seguir consultando en segundo plano por permanecer
   compuesta. Conservar las barreras y acciones existentes en este bloque.
4. Ejecutar las regresiones nuevas junto a las ocho actuales y UFD postcommit
   completo antes de su PR. Sólo entonces evaluar el cierre de S2 y pasar a S3.

### S3 Observación coherente del servidor

- Cambio y Ayuda consumen una proyección coherente del mismo estado. En una
  actualización se observa cada destino como máximo una vez; las lecturas
  simultáneas se agrupan de forma acotada.
- El publicador conserva la exclusión y la verificación de evidencias. Un
  lector no debe convertir otro lector en un fallo funcional. Distinguir
  espera, dato anterior con fecha y fallo de verificación; no convertir caché
  o BUSY en autoridad para publicar.
- Un único reconciliador posee cada validación; el reconciliador residual sólo
  recoge operaciones que no estén ya gobernadas por el workflow activo.
- Acotar IO dentro de transacciones y locks sin perder atomicidad de admisión.
- Regresiones de concurrencia sobre el publicador real en fixture efímero,
  cache/invalidación, pruebas de ausencia de efectos en GET y propiedad de cada
  resultado. No medir carga provocando contención en PROD.

### S4 Admisión y recuperación durables

- Conservar la intención autorizada ante observación temporalmente ocupada y
  continuar la misma operación, con identidad y motivo de espera consultables.
- Revalidar fuente, catálogo/canal, predecesor, ownership y autorización antes
  del efecto. Un cambio real invalida la preparación; no se fuerza.
- Evitar multiplicar operaciones por una respuesta perdida, un push o polling.
- Regresiones: BUSY antes de admitir efecto, pérdida de ACK, reinicio App,
  espera/reanudación, predecesor cambiado y evidencia ajena. Inspeccionar las
  mismas filas/IDs y el número de efectos, no fabricar estados terminales.

### S5 CI y progreso completo

- Evitar la repetición causada únicamente por quitar el borrador de una PR.
  Conservar validaciones de código/base/receta distintos y todos los gates UFD.
- El progreso incluye todas las comprobaciones exigidas, sus propios tiempos,
  paralelismo y causa de espera. No confundir compilación, entrega e instalación.
- Revisar el diff previo de `feature/change-journey-clarity`; sus resultados
  anteriores no acreditan este conjunto modificado. Volver a probar lo afectado.
- Regresiones de eventos CI, base/head cambiados, job omitido/cancelado/fallido,
  construcción paralela y coherencia entre Ayuda y Cambio.

### S6 Integración y aceptación móvil

1. Revisar diffs, ejecutar tests focales de cada bloque y suites exigidas. Tras
   cada commit App, ejecutar `./scripts/validate-change` antes de abrir PR y
   respetar íntegramente su plan UFD. CI y mergeability son puertas separadas.
2. Integrar por repositorio con SHA exactos. Antes de build desde el workspace
   manual, `origin/main` debe ser ancestro de `github/main`; sincronizar sólo por
   fast-forward explícito, nunca force push.
3. Solicitar autorización de rollout; respetar contratos compatibles, backup
   PostgreSQL si procede y firma/canal Android existente. Conservar la secuencia
   de versiones 0.5.x y el incremento mínimo necesario; no publicar aún.
4. Identificar App/Platform desplegados y APK instalada, no sólo main o el canal.
5. Completar WS29 desde Samsung sin recrearla ni reparar estado desde consola.
   Probar push en Cambio y salida/retorno durante trabajo admitido.
6. Completar los tres recorridos representativos exigidos por el plan operativo,
   incluyendo una recuperación controlada en entorno efímero. Si se necesita
   intervención CLI, no contar ese recorrido como aceptación autónoma.

## Métricas de aceptación

- Cero navegaciones iniciadas por recepción/refresco sin interacción humana.
- Máximo una lectura equivalente simultánea por sesión/recurso y consumidor
  coordinado; máximo una observación por destino en cada respuesta agregada.
- Cero efectos, prompts o pruebas nuevos iniciados por GET, avisos o reapertura.
- Cada intención admitida conserva ID, estado durable y causa de espera tras
  segundo plano, timeout y reinicio. No confundir rechazo previo con ejecución.
- Todas las comprobaciones que bloquean GitHub están visibles en el progreso.
- Registrar número de peticiones, duración y causa de espera en ensayos
  reproducibles sin tokens, logs arbitrarios ni otros secretos. No atribuir una
  mejora de rendimiento hasta medir el mismo escenario antes y después.

## Registro de trabajo

### 22 de octubre de 2026 Inicio

Auditoría finalizada y plan autorizado para implementación. Rama nueva desde
main verificado; diff previo preservado. El plan se enlaza desde README y desde
el plan operativo anterior para evitar varios estados actuales contradictorios.

### 22 de octubre de 2026 Implementación S1

`AteneaNotificationState` separa llegada y apertura explícita. El observer FCM
sólo actualiza el identificador de refresco; el aviso permanece visible en el
sistema y su llegada no marca lectura ni navega. Sólo el Intent abierto por el
operador solicita navegación. Shell ya no usa el evento como identidad de la
conversación y conserva el estado guardable por WorkSession, sin mezclarlo con
otra sesión. Operations tampoco se recrea por un identificador de aviso ajeno.

Validación ejecutada mediante el builder normal, sin configuración Firebase,
firma de publicación ni acceso a PROD:

```bash
./scripts/android-build.sh :app:testDebugUnitTest \
  --tests com.atenea.android.push.AteneaNotificationRouteParserTest \
  :core-console:testDebugUnitTest :core-console:assembleDebugAndroidTest
bash -n scripts/test-mobile-journeys.sh
git diff --check
```

Resultado: 9 tests de parser/observer y 188 de core-console PASS, cero omitidos
y cero fallos. Compilación de la APK instrumentada PASS; no es una APK estable
firmada ni publicada.

`NotificationNavigationJourneyTest` añade tres casos sobre Shell, el mismo
estado de avisos que consume MainActivity, conversación y HTTP loopback: dos
avisos sin navegación/lectura, apertura explícita de la misma sesión, y estados
independientes al ir a otra sesión y volver. El primer caso incluye segundo
plano, restauración de estado, borrador y desplazamiento. El corredor existente
exige ahora siete regresiones, conservando las cuatro anteriores.

La instrumentación **no se ha ejecutado**: el corredor normal se detuvo por
falta de acceso a `/dev/kvm`. Un ensayo efímero sin aceleración también se
detuvo antes de arrancar Android por dependencias del emulador
(`libX11-xcb.so.1`), sin llegar a ejecutar tests. No se instalaron librerías ni
se cambiaron grupos/permisos del host. La variante de software se descartó;
el corredor conserva su requisito KVM y la limpieza de su AVD/ADB privados.
No presentar ninguno de estos stops de herramientas como un test funcional
fallido ni como un PASS de pantalla.

La integración posterior de S1 y el avance S2 se registran a continuación.
No hay cambios Platform ni despliegue de esta estabilización. No reintentar
WS29 para comprobar estos cambios locales.

### 22 de octubre de 2026 Primera validación de PR 92

UFD postcommit del head `718565f` PASS: T3, `ufd-policy` y suite
`android-unit-full`, 312 tests. CI UFD y backend combinado con la base
`6e3912d0e41b0408a1847eeb738361f963c31257` PASS. No se redujo su selección.

El run de journeys `39781847316` arrancó en KVM y ejecutó siete casos. Seis
pasaron. El caso de separación de sesiones se detuvo en una aserción que
comparaba Text más EditableText con vacío: el informe contiene EditableText
vacío y el placeholder como Text. Se corrige para comprobar explícitamente la
propiedad EditableText; no se elimina el caso ni sus comprobaciones posteriores
de conservación de borradores. El siguiente run debe completar los siete.

PR: <https://github.com/jlnieto/atenea/pull/92>. El ajuste se mantuvo en esa misma
PR, sin crear otro Cambio.

### 22 de octubre de 2026 Integración S1 y comienzo S2

S1 integrado con merge `8bc9cc35f53c51effc186ce428c4bc6cd76aa3d2` tras validar el
head `756c25da1e542c03f72f680213a18c7456f92721`. UFD local postcommit T3 y suite
Android completa PASS, 312 tests; CI UFD y backend combinado PASS. El run
`39783629382` pasó los siete journeys, incluido el caso completo de separación
de sesiones tras corregir la aserción del placeholder. Informe descargado en
`target/s1-ci-39783629382/target/mobile-journeys/instrumentation.log` del worktree
S1. CI PASS e integración no acreditan despliegue ni aceptación Samsung.

S2 se desarrolla en su rama/worktree separado y avanzó por fast-forward hasta
ese merge. Cambios preparados:

- `CoalescingRead` comparte solicitudes simultáneas de la misma generación y
  serializa generaciones diferentes. No almacena resultados terminados. La
  cancelación de un observador no cancela a otro; destruir el propietario sí
  cancela sus lecturas. Una excepción de lectura no cancela todo el Job de UI.
- `MobileDeliveryUiState` usa esa exclusión conservando ownership, revisión,
  fingerprint y barrera de efectos pendientes. La consulta manual se une a la
  automática; ya no multiplica GETs ni depende de descartar respuestas tardías.
- Cambio espera la respuesta antes del siguiente intervalo: cinco segundos con
  trabajo o evidencia por confirmar, treinta en espera del usuario y quince
  tras fallo de lectura. El historial completado no mantiene polling; una
  reapertura o consulta explícita puede volver a leerlo. STARTED sigue siendo
  requisito del polling visual.
- Conversación espera sus lecturas y unifica arranque, vuelta y seguimiento
  activo bajo STARTED. Los refrescos equivalentes se agrupan y los de distinto
  alcance de perfil se serializan. Los detalles terminales con progreso final
  ya confirmado no se vuelven a descargar en cada refresco.
- Recuperación tiene un solo bucle STARTED, exclusión por generación y cursor
  incremental. Una respuesta anterior a una acción no sustituye la revisión
  posterior; consultar no se trata como una nueva mutación. Se propaga la
  cancelación en las lecturas en lugar de presentarla como error funcional.

Pruebas preparadas y comprobadas localmente:

```bash
./scripts/android-build.sh :core-console:testDebugUnitTest \
  :core-console:assembleDebugAndroidTest
bash -n scripts/test-mobile-journeys.sh
git diff --check
```

Resultado: 196 tests core-console PASS, cero omitidos/fallos, y compilación
instrumentada PASS. La suite incluye seis regresiones de concurrencia,
cancelación, error y generaciones, más agrupación de diez lectores y política
de frecuencia. Las pruebas anteriores de fuente, propiedad y respuesta perdida
siguen pasando. Un test que suponía inicio síncrono de la lectura ahora espera
su señal de comienzo, antes de verificar la misma estabilidad del botón.

`ReadCoordinationJourneyTest` añade HTTP loopback real al ensayo de pantalla:
una consulta manual se une al GET lento de regreso, máximo uno simultáneo,
ningún efecto, ninguna consulta periódica durante STOP y lectura fresca al
volver. Está compilado pero pendiente de ejecución; el corredor exige ocho
casos, sin omitir los siete ya aprobados.

Antes de cerrar S2 quedan pendientes los ocho journeys, las regresiones
instrumentadas específicas de recuperación/cursor y la coordinación compartida
del detalle de ejecución entre Conversación y Recuperación. La vista retenida
`WorkSessionScreen` también tiene un repositorio periódico propio: revisar su
propietario/ciclo de vida antes de afirmar que todas las vistas están cubiertas.
No extender un PASS de componente a esos escenarios aún no demostrados.

S3 a S6 siguen pendientes. No hay cambio de backend, Platform, versión/firma APK,
datos PROD, permisos del host ni operaciones sobre el Cambio real.

### 22 de octubre de 2026 Regresión de temporizador detectada en CI S2

PR <https://github.com/jlnieto/atenea/pull/93>, checkpoint `95088c3`: UFD local y
CI, suite Android completa de 320 tests y backend de merge PASS. El run de
pantalla `39785668932` ejecutó ocho casos; siete pasaron, incluido el nuevo
contador físico de HTTP/STOP/START. El recorrido previo de actualización de la
misma PR se detuvo en su espera de veinte segundos para Integrar cambio.

El intervalo adaptativo se calculaba al comenzar un delay. Al pasar de reposo
a trabajo dentro de esos treinta segundos, el delay no se rearmaba y la app
tardaba demasiado en observar la PR lista. Se sustituye por una observación
reactiva del intervalo con collectLatest: cambia de ritmo al cambiar la
actividad, espera antes de cada lectura y sigue respetando STARTED y la
exclusión de transporte. El timeout y las aserciones del journey se conservan.
La integración de esta primera parte quedó retenida en ese checkpoint hasta
validar las ocho regresiones del head corregido.

El run `39787587315` del head `2530506` confirmó el recorrido completo de
actualización de PR y el nuevo contador HTTP. Falló una prueba anterior de
rechazo de admisión: esperaba polling en quince segundos después de volver al
reposo, que ahora usa treinta. Esa prueba avanza treinta segundos en el reloj
de coroutines de Compose antes de esperar el GET real. Conserva el timeout de
HTTP, el rechazo visible, restauración/segundo plano y la aserción de una sola
solicitud; no acorta el intervalo de producción ni omite la regresión.

### 22 de octubre de 2026 Primera parte S2 integrada

PR <https://github.com/jlnieto/atenea/pull/93> integrada con merge
`bb96bab57105f112156dbb2d4029b2ba60df9581`, validando el head exacto
`0f0e6af3e4c6aa6ab8564368dd19a2a9e8cc8173`. GitHub informó MERGEABLE/CLEAN
y todas las comprobaciones ejecutables estaban PASS antes del merge, sin bypass.

- UFD postcommit local y CI T3 PASS, `ufd-policy` y `android-unit-full` íntegros:
  320 tests, cero fallos. Run CI `39788740869`.
- Compilación instrumentada local PASS. Los 196 tests core-console forman
  parte de la suite Android completa; no son otros 196 tests adicionales.
- Run `39788746792`: `OK (8 tests)`, incluidos los siete anteriores, el
  temporizador reactivo, el rechazo persistente tras refresco pasivo y el
  contador HTTP concurrente con STOP/START. Informe descargado en
  `target/s2-ci-39788746792/target/mobile-journeys/instrumentation.log` del
  worktree S2. Los artefactos GitHub se conservan siete días.
- Backend de merge contra `8bc9cc35f53c51effc186ce428c4bc6cd76aa3d2` PASS,
  run `39788746836`, incluidas las regresiones cerradas del workflow.
- `git diff --check` PASS. `main` y `github/main` locales avanzaron por
  fast-forward hasta el merge. `origin/main` permanece en `6e3912d0...`,
  ancestro del merge; no se propagó éste al mirror operativo.

S1 y esta primera parte S2 sólo están integradas: no hay despliegue, APK nueva,
cambio Platform/configuración/PROD ni acciones sobre WS29. El bloqueo original
ANDROID_OBSERVATION_BUSY requiere todavía S3/S4. Los pendientes de S2 están
enumerados arriba; no cerrar todo S2 por este PASS ni abrir otra PR con los
mismos commits. La rama de continuación parte de este merge y conserva un
checkpoint documental local para retomar sin repetir estas pruebas.

Al retomar: leer este documento y `AGENTS.md`, comprobar status de ambos repos y
el bloque activo, revisar el diff y el último resultado de prueba. Actualizar
estado, comandos, límites y siguiente acción aquí antes de terminar el turno.
