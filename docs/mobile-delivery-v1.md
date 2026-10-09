# Entrega desde Atenea móvil: código y puesta en marcha separados

El objetivo es trabajar sin portátil después de una instalación inicial:
implementar en una conversación, validar, revisar/integrar y publicar desde
Atenea. Terminar un AgentRun **no** valida, integra ni despliega.

La aceptación completa y el trabajo pendiente para eliminar la intervención
desde CLI se siguen en
[Operación completa desde el móvil](mobile-only-operations-plan.md).

Este cambio añade el control móvil y el protocolo de publicación. No instala
servicios, habilita flags, configura secretos de GitHub ni publica una APK.
No debe declararse operativo en PROD hasta completar el bootstrap y la prueba
de aceptación descritos al final.

## Uso previsto

La conversación reserva el espacio para los mensajes y el cuadro de instrucciones.
**Cambio** abre la validación, PR, integración y publicación de la misma WorkSession;
volver a la conversación conserva el borrador y la posición del historial.
El menú **⋮** contiene el detalle de ejecución, el perfil de la próxima ejecución,
la ayuda de adjuntos y Actualizar. Cambiar de vista no inicia ni duplica operaciones.

En **Cambio → Consultar resultado** se consulta exclusivamente la evidencia
persistida de validación mediante `GET /api/sessions/{id}/validation-evidence`:
fase, resumen, exit code e identidad durable del intento. No llama al worker,
no observa/promueve la fuente y no ejecuta ni reintenta pruebas. Un intento de
una fuente anterior se muestra como histórico y no cuenta para validar la actual.
Si un fallo antiguo sólo conservó un resumen genérico, no se inventa su causa.
**Validar cambio** sigue siendo una acción explícita distinta de consultar.

1. Continuar el cambio y su WorkSession actuales. Pedir la implementación y
   dejar terminar Codex.
2. Pulsar **Validar cambio**. Sólo una observación durable CURRENT y una
   aceptación VALIDATED permiten continuar.
3. Pulsar **Crear PR**. Atenea publica la rama change-owned mediante el
   mecanismo existente, espera UFD para su SHA exacto y crea la PR. Una PR
   previamente publicada con la misma identidad se adopta; no se duplica.
4. Usar **Revisar PR** para inspeccionarla en GitHub desde el móvil y pulsar
   **Integrar cambio**. Ese enlace exige acceso personal a GitHub; el panel
   no incorpora un visor diff ni entrega el token del backend. La confirmación solicita
   integrar sólo esa PR y ese commit. Atenea comprueba UFD, checks y
   mergeability; no fuerza protecciones. Integrar no despliega.
5. Pulsar **Preparar Backend PROD** si todavía no contiene el ticket. Con
   recuperación habilitada, selecciona explícitamente el main aprobado que
   contiene el merge, aunque main haya avanzado. Worker y Android están
   separados bajo **Otras publicaciones (Worker / Android)**. Se obtiene un plan con SHA, hash y caducidad, a partir
   de un artefacto de GitHub main. Si su build está en curso, el mismo plan
   espera, sin crear otra operación ni desplegar.
6. Pulsar **Confirmar** y usar el código del autenticador. Es una autorización
   para esa publicación concreta, no permisos para cada edición o comando
   de Codex. El factor no se guarda en la conversación.
7. Consultar el resultado. La operación continúa fuera del proceso App y se
   reconcilia aunque se cierre el móvil o se reinicie el backend. Después de
   publicar Android, usar el actualizador interno e instalar la APK.
8. Hacer el smoke funcional de la capacidad publicada y cerrar el cambio
   mediante el mecanismo existente; no cerrar antes de publicar.

Las acciones requieren el rol efectivo PLATFORM_ADMINISTRATOR. El backend
relee el rol, no confía en el valor almacenado por el móvil. Publicar App o
Android exige la integración durable del propio cambio y que su merge siga
siendo el SHA canónico de main; si main avanzó se detiene, no se elige otro
commit silenciosamente.

### Recuperar la publicación de un ticket ya integrado

Con recuperación habilitada, **Preparar Backend PROD** utiliza la preparación
recovery (antes rotulada **Preparar Backend PROD actualizado**), no presenta
dos botones equivalentes. Su petición es vacía:
el móvil no elige commits, ramas, repositorios, rutas ni comandos. App exige
la integración durable de esta misma WorkSession, su aceptación
INTEGRATION_READY y la identidad publicada exacta; deriva el SHA actual de
github/main y comprueba por lecturas autenticadas que contiene el merge del
ticket. Una PR ajena, un merge distinto o una historia divergente se rechazan.

El plan conserva operation/execution IDs, el recibo de integración original,
su head y merge, y el SHA seleccionado. El móvil muestra esa versión y permite
revisar en GitHub los cambios posteriores al ticket. Incluye esos cambios
aprobados de main, no sólo el diff original del ticket. Preparar no publica:
el ejecutor existente aún exige el artefacto inmutable y su CI, y Confirmar
mantiene TOTP y el grant vinculado al plan/hash y commit seleccionados.

Antes de autorizar y confirmar se vuelven a comprobar la identidad y la
ancestría. Si main volvió a avanzar, el plan no confirmado queda BLOCKED con
CANONICAL_MAIN_MOVED, sin consumir el factor/grant ni elegir otra versión.
Otra preparación es una nueva intención explícita. Un doble toque mientras
el plan está activo, una respuesta perdida o una consulta conservan la misma
identidad; una publicación ya completada de ese mismo SHA devuelve su recibo.
La evidencia de origen se conserva cuando llegan los recibos del ejecutor.

La comparación GitHub debe ser completa y está acotada a 100 commits; ante
truncamiento se bloquea, no se presume inclusión. Esta recuperación sólo
prepara Backend PROD de Atenea; no altera la selección normal de Platform ni
Android, no reabre ni reintegra la PR y no crea otra WorkSession o AgentRun.

Se conserva también la salvaguarda ALREADY_CURRENT del publicador: si esa
versión ya está ejecutándose, no se vuelve a desplegar ni se fabrica un recibo
de publicación móvil. Instalar esta mejora no demuestra, por sí solo, que el
ticket haya completado su aceptación de publicación desde el móvil.

### Estado de PROD observado, separado del historial

La lectura normal `GET /api/mobile/sessions/{id}/delivery` incluye `deployment`.
App consulta `OBSERVE_APP` por el socket existente del publicador VPS: imagen
real, revisión OCI inmutable, health actual y recibo durable root-owned. El
contrato independiente es `atenea-app-observation/v1`. App comprueba además la
propiedad de esta revisión y que el commit observado contiene el merge exacto
de su integración durable. No infiere despliegue de un PASS CI o de main actual.

La consulta no crea planes, operaciones, grants ni recibos; no ejecuta efectos,
no requiere que AX42 esté idle y no cambia los estados de WorkSession o
DevelopmentChange. La prueba positiva de ancestría se conserva como máximo 60
segundos, ligada a integración, PR, head, merge y commit instalado; health,
runtime, rol e identidad se comprueban en cada lectura.

Si el recibo raíz no tiene una operación móvil con los mismos IDs/commit,
se muestra **Backend PROD desplegado por operador**, sin fabricar un RELEASE
móvil SUCCEEDED. Si existe esa operación móvil exacta, se identifica como
publicación desde Atenea. Un fallo, observación caducada o evidencia contradictoria
no permiten declarar desplegado ni preparar otra publicación del backend.
Una versión comprobada que no contiene el merge sí permite preparar la nueva.

Después de integrar se retiran del paso principal los mensajes/acciones de
conflictos antiguos. El historial y sus recibos siguen disponibles en **Ver
historial y recibos**. Cuando PROD contiene el cambio y está healthy, no se
ofrece preparar/confirmar otra vez ese backend. Worker y Android son publicaciones
distintas: no se declaran completadas sólo porque el backend está desplegado.
Una integración antigua tampoco completa una revisión nueva del workspace.

Orden de puesta en marcha de esta ampliación (con autorización independiente):
instalar/verificar primero el publicador **VPS** desde Platform; después App
PROD y la APK estable. No necesita Flyway ni actualizar/reiniciar el worker.
Si el publicador anterior no reconoce OBSERVE_APP, App indica UNAVAILABLE y
conserva la auditoría; no inventa estado ni intenta publicar para comprobarlo.

## Qué es nuevo y qué se reutiliza

Se reutilizan la conversación, la validación cerrada, la publicación
change-owned, GitHubClient, las familias de autenticación, TOTP y los grants
de un solo uso. Lo nuevo es:

- el panel **Entrega del cambio** en Android;
- la cola durable `mobile_delivery_operation` (Flyway V84);
- la integración de la PR con precondición SHA y observación reconciliable;
- el cliente local `atenea-release/v1` y los builders de artefactos main;
- el ejecutor root Platform independiente de App.

El panel no envía rutas, comandos, SHA elegidos por el usuario ni credenciales
de producción. App crea las identidades y deduce los commits de la evidencia
de GitHub/publicación. El scheduler de entrega es separado del scheduler de
notificaciones para no bloquear el push por esperar a GitHub.

La cola se confirma en PostgreSQL antes del primer efecto externo. Los
reintentos de transporte conservan plan/operation IDs. Un recibo
contradictorio entra en QUARANTINED. ROLLBACK_FAILED conserva el bloqueo de
publicaciones y de AgentRuns; no se desbloquea por pulsar otra vez.

## Límite explícito de este primer slice

Crear PR e integrar desde este panel corresponde al repositorio App de la
WorkSession change-owned Atenea. El destino Worker AX42 publica **Platform
main ya integrado**; no crea ni integra una PR del repository-role Platform.
No debe presentarse esto como entrega multi-repositorio completa. Este
procedimiento no autoriza cambios destructivos de DB ni implementa una restauración automática
de PostgreSQL. La compatibilidad de las migraciones con el predecesor debe
revisarse antes de integrar; una migración incompatible requiere un cambio
de protocolo/política, no saltarse este publicador.

Tampoco automatiza el avance del mirror/configuración canónicos del worker.
Antes de nuevos tickets hay que verificar que la fuente del workspace sea
la aprobada, sincronizar sólo mediante el procedimiento soportado y parar
ante una promoción de canonical commit no autorizada. No inferir que
publicar una imagen actualiza automáticamente los repositorios operativos.

UFD es actualmente el piloto Android descrito en `.delivery/README.md`. Su
PASS no sustituye los tests backend: el build de artefacto App ejecuta la
suite Maven en una DB efímera antes de producir la imagen.

## Preparación inicial pendiente

El operador puede hacer esta instalación una vez desde Codex en su portátil,
que ya tiene SSH al VPS y al dedicado. No necesita dar al worker una clave
SSH de PROD. El runbook cerrado está en el repo Platform:
`ops/release/README-release-control-v1.md`.

Precondiciones:

- integrar/revisar ambas PRs, CI verde y SHAs exactos aprobados;
- configurar en GitHub `ATENEA_UFD_READ_TOKEN`, con lectura del release UFD
  privado; los datos públicos Firebase `ATENEA_FIREBASE_API_KEY`,
  `ATENEA_FIREBASE_PROJECT_ID`, `ATENEA_FIREBASE_APP_ID`,
  `ATENEA_FIREBASE_GCM_SENDER_ID`; y el secreto
  `ATENEA_ANDROID_UPDATE_MANIFEST_URL` del canal actual;
- token de App con lectura Actions y las facultades existentes de PR/merge;
- executor/tokens/configuración root del VPS y AX42, baseline Platform
  adoptado y verificado, canal APK existente y mismo certificado de firma;
- operador con TOTP ya inscrito, `ATENEA_AUTH_TOTP_ENABLED=true` y
  `ATENEA_AUTH_PRIVILEGED_ACTIONS_ENABLED=true`; se reutiliza el mecanismo
  existente, no se crea un usuario ni un factor alternativo;
- backup PROD fresco, despliegue inicial App V84 desde artefacto inmutable,
  con `ATENEA_RELEASE_CONTROL_ENABLED=true` y sólo el directorio del socket
  montado read-only; publicar la primera APK compatible por el canal normal.

Los cambios reales de infraestructura, flags, factores, secretos, networking
y publicación siguen necesitando autorización explícita para ese bootstrap.
No hay un backend App DEV permanente en este procedimiento.

## Prueba de aceptación antes de depender sólo del móvil

La recuperación de conflictos en desarrollo incorpora Reintentar resolución
para un resolver FAILED de la preparación actual. La petición identifica
únicamente la WorkSession, la operación y el intento ya observados, con cuerpo
vacío. App fija el binding y comprueba la fuente efectiva; conserva el turno y
el run fallido, y registra la autorización y el nuevo run por separado. Una
respuesta perdida o un doble toque sobre el mismo intento recuperan ese
registro, no otra ejecución. Consultar sigue siendo read-only.

Si el resolver fallido dejó archivos parciales, App los observa sin reset y
registra una revisión nueva cuando cambia la fuente. V89 conserva por separado
la observación autenticada, su hash, el estado limpio/sucio y la autorización
de cada reintento. El móvil muestra la revisión actual; el turno, el intento
original y la preparación siguen en el historial. Dos solicitudes simultáneas
admiten un único run y una única revisión observada.

Un HEAD u ownership distintos se rechazan sin tocar archivos. Tras una
preparación completada o la actualización de la misma PR, Actualizar base con
main solicita una continuación v2 con predecesor y main exactos derivados por
App. Conserva los archivos resueltos, la rama, PR y evidencia anteriores; si
main no cambió, devuelve la misma operación. V90 enlaza las preparaciones sin
reemplazar sus recibos. La fuente nueva exige otra validación de cuatro checks.

Una preparación todavía pendiente conserva su intención original. Una
publicación incierta bloquea la continuación: no se descarta evidencia ni se
elige otro main implícitamente. Recuperar operación reautoriza la intención
original mediante V91 y reanuda su preparación o publicación, sin crear otra
operación ni PR. Platform exige que GitHub y mirror coincidan en un descendiente
exacto del main retenido y sella la comprobación antes del efecto. Si el push
ya ocurrió, sólo completa su recibo; nunca fuerza ni repite una publicación
confirmada. Después se puede incorporar el main nuevo con otra preparación y
validación. El botón sólo aparece para una operación pendiente reconocida por
el servidor, no por cualquier fallo CI. No está desplegada ni aceptada en el
móvil todavía.

Antes de publicar el ajuste de conversación, ejecutar los tests focales del
backend y cliente API de `validation-evidence`, y los tests Compose de
`ConversationWorkspaceLayoutTest` y `WorkSessionAttachmentComposerTest`.
En una ventana pequeña, con fuente ampliada y teclado abierto, los mensajes y
el composer deben seguir visibles sin paneles operativos ni insets duplicados.
Comprobar también rotación, volver de Cambio conservando el borrador y leer
historial mientras llegan mensajes sin un salto automático. Consultar un fallo
debe conservar las operation IDs y no iniciar otro intento de validación.

Usar el ticket/WorkSession existentes, no crear un prompt duplicado. Confirmar
que los nuevos botones están disponibles, que Validar pasa en el runtime
desplegado, que el SHA publicado tiene UFD en GitHub y que la PR corresponde
al cambio. Si una rama change-owned antigua no incluye el workflow UFD pero
conserva su harness/engine-lock, la misma operación durable solicita
`atenea-ufd-owned-head-v1` mediante `repository_dispatch`. El workflow se toma
de `main` y comprueba el SHA exacto publicado, la rama/UUID del cambio, la
autoridad main retenida y el harness idéntico al de su merge-base. Ejecuta
`./scripts/validate-change` de ese checkout: ni cambia la base o el código,
ni sustituye/reduce el plan UFD. No hay comandos/rutas/repositorios elegibles
por el móvil y no se concede autoridad PROD al job.

La intención y el claim de envío se guardan en `mobile_delivery_operation`
antes de llamar a GitHub. Una respuesta perdida/reinicio se resuelve buscando
el run ligado a requestId+SHA; nunca reenviando a ciegas. Un envío no
confirmado conserva ese claim también ante una nueva intención para el mismo
head: se consulta toda la historia durable, no sólo las últimas filas de la UI.
Un inicio no
confirmado durante diez minutos queda bloqueado, no «ejecutándose» para
siempre. Un run sólo vale si es del workflow/repo esperado en `main`, con
autoridad ancestro del main canónico y la identidad exacta del head. Un PASS
local sigue sin sustituir UFD GitHub. Workflow ausente/deshabilitado, autoridad
movida, harness alterado, CI fallido o evidencia ajena no autorizan crear PR.

`repository_dispatch` necesita Contents: write, ya requerido por el publicador;
las lecturas de runs siguen usando Actions: read. No requiere Actions: write,
Checks: write ni cambiar el token dedicado de lectura del ejecutor Platform.
Referencias: [evento repository_dispatch](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#repository_dispatch)
y [API de dispatch](https://docs.github.com/en/rest/repos/repos#create-a-repository-dispatch-event).

Desde el móvil: crear/adoptar PR, revisar, integrar, preparar y confirmar un
despliegue aprobado. Cerrar la app mientras publica y volver a abrirla:
debe aparecer la misma operation ID, su commit efectivo y su resultado.
Comprobar login, lectura de proyectos y App→AX42; conservar contenedor/
volumen PostgreSQL. Después publicar e instalar Android con certificado
compatible y versionCode mayor, y comprobar SHA-256/descarga del canal.

El ensayo de rollback se realiza en fixtures aislados; no inducir un fallo
real en PROD para probarlo. La aceptación móvil real y la retirada de toda
dependencia rutinaria del portátil siguen pendientes hasta esta prueba.
