# Entrega desde Atenea móvil: código y puesta en marcha separados

El objetivo es trabajar sin portátil después de una instalación inicial:
implementar en una conversación, validar, revisar/integrar y publicar desde
Atenea. Terminar un AgentRun **no** valida, integra ni despliega.

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
5. Pulsar **Preparar Backend PROD**, **Preparar Worker AX42** o **Preparar
   Android estable**. Se obtiene un plan con SHA, hash y caducidad, a partir
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
al cambio. Si la rama antigua no incluye el workflow UFD, detenerse y
actualizarla mediante el procedimiento normal de integración/revalidación;
no considerar un PASS local como un workflow GitHub inexistente.

Desde el móvil: crear/adoptar PR, revisar, integrar, preparar y confirmar un
despliegue aprobado. Cerrar la app mientras publica y volver a abrirla:
debe aparecer la misma operation ID, su commit efectivo y su resultado.
Comprobar login, lectura de proyectos y App→AX42; conservar contenedor/
volumen PostgreSQL. Después publicar e instalar Android con certificado
compatible y versionCode mayor, y comprobar SHA-256/descarga del canal.

El ensayo de rollback se realiza en fixtures aislados; no inducir un fallo
real en PROD para probarlo. La aceptación móvil real y la retirada de toda
dependencia rutinaria del portátil siguen pendientes hasta esta prueba.
