# Novedades y entrega completa

El menú principal de Android incluye **Novedades**, independiente de cualquier
Cambio. Muestra las mejoras integradas en los dos repositorios de Atenea, con
filtros Móvil / Backend / Worker, paginación y una explicación de la acción
necesaria. No necesita abrir un cambio para preguntar por una actualización.
Toda la vista es de lectura: refrescar, filtrar o consultar una PR no inicia
pruebas, publicaciones ni AgentRuns.

## Integrado no significa publicado

El historial se obtiene de PRs integradas en los dos repositorios fijos. Se
muestran notas revisadas vinculadas al merge exacto o la sección explícita de
producto declarada en español (`## Novedades para el usuario` con el marcador
`<!-- atenea-news:es -->`). Sin esa copia se usa una descripción genérica en
español, no el título técnico. No se muestran cuerpos completos, logs,
credenciales ni instrucciones de terminal. Los textos orientados al usuario
deben explicar la mejora y qué hacer, no detalles internos de despliegue.

Los destinos de cada PR de App se derivan del diff completo y ligado al head;
los renombrados consideran ambos nombres. Android requiere APK; backend requiere
App PROD; los archivos compartidos desconocidos requieren ambos. Platform
requiere Worker AX42. Una lectura incompleta deja el destino sin confirmar.

Cada destino se comprueba contra su versión realmente instalada/publicada,
mediante observaciones root de protocolos separados:

- `atenea-app-observation/v1`: imagen ejecutada y health.
- `atenea-android-observation/v1`: APK del canal, firma, checksum y recibo durable.
- `atenea-platform-observation/v1`: instalación verificada y recibo durable AX42;
  su health se obtiene por separado mediante la conexión autenticada existente.

GitHub debe confirmar que la versión observada contiene la PR integrada. Un
backend sano no demuestra que el APK esté publicado. Tampoco se inventan
recibos de publicación móvil para despliegues hechos por el operador.

`GET /api/mobile/atenea/updates?page=1` requiere un operador autenticado activo.
La selección de repositorios y las operaciones son cerradas; no acepta rutas,
fuentes, comandos ni destinos aportados por el cliente. Una observación fallida
se presenta como **sin confirmar**, nunca como éxito. El historial cacheado se
identifica como tal. Las observaciones se renuevan en segundo plano, con su fecha
real; el GET no consulta GitHub ni al worker. Android conserva la primera página
por operador y la muestra al abrir. Esta copia sólo sirve para presentar
Novedades y **no** autoriza ninguna publicación. Las acciones de entrega y
finalización conservan su verificación fresca. Los detalles del read model V104
y del aviso SSH destacado están en [Novedades y avisos de operaciones](product-news-and-monitoring.md).

El panel Cambio muestra el canal Android y la versión instalada incluso antes
de validar o en historia cerrada. Ayuda puede abrir directamente Ajustes →
Actualizaciones o el panel de publicación. No manda al usuario a validar una
pregunta de diagnóstico ni a buscar un menú oculto. Finalizar requiere evidencia
fresca de **todos** los destinos exigidos por el diff, antes y después del cierre.

## Versiones estables revisadas

La versión del producto se revisa en Git, en `android/app/build.gradle.kts`.
Las actualizaciones conservan la serie `0.5.x` e incrementan `versionCode`;
los números de ejecución CI no son versiones de la app. Reejecutar el build
del mismo SHA conserva la versión. No se reutiliza un código ya publicado.

Los manifests leen la metadata del APK realmente construido. El publicador
root vuelve a verificar versión, firma y monotonía de forma independiente. Los
builds locales conservan una versión de desarrollo y no autorizan publicaciones.

## Imágenes en conversaciones de Cambios

Las nuevas WorkSessions con ownership exacto de Cambio reciben
`atenea-change-attachments-v1` al crearse, si los flags y el proyecto real ya
lo permiten. El broker anuncia `change-session-attachment/v1`; comprueba la
identidad contra el workspace durable y retiene el propietario al primer upload.
No se cambia el formato de imagen, los límites, el sandbox ni el manifiesto v4.

Una conversación antigua **abierta**, READY y sin AgentRuns activos ofrece
**Activar imágenes → Activar imágenes aquí**. Esa acción usa el mismo ID,
comprueba de nuevo ownership y broker, y persiste auditoría idempotente en
`work_session_attachment_enablement` (V97). Una lectura de capacidades no modifica
datos. No se recrean conversaciones y no se reabren las cerradas.

## Aviso explícito de fin de publicación

`POST /api/mobile/atenea/updates/announce` acepta exclusivamente `{}` y exige
PLATFORM_ADMINISTRATOR efectivo. Antes de enviar comprueba App sana, APK del
mismo SHA, Platform observada, worker v4 sano y adjuntos reales compatibles.
Usa exclusivamente los dispositivos Android activos del mismo operador y FCM
ya configurado; no crea un AgentRun ni un evento de tarea ficticio.

La intención se confirma en V98 **antes** del envío. La clave única de operador,
SHA y versionCode evita repetir un aviso ante respuesta perdida. `ACCEPTED`
significa que FCM lo aceptó, no que el usuario ya lo haya visto; `UNCERTAIN` o
`CLAIMED` no se reenvían automáticamente. La evidencia conserva IDs y pruebas,
nunca tokens. El aviso dirige a Novedades e indica cómo actualizar la app.

## Orden de rollout

1. Integración y CI de Platform y App, incluidos UFD y pruebas focales.
2. Actualizar mediante sus installers soportados ambos publicadores y el broker
   de adjuntos AX42. El actualizador del publicador VPS requiere operador root;
   no se suple con mounts privilegiados, credenciales nuevas ni permisos amplios.
3. Publicación Platform normal con recibo durable y verify, sin cambiar Codex.
4. Backup PostgreSQL 16 fresco con checksum y TOC; publicar sólo backend exacto
   de main, aplicando V97/V98. Preservar PostgreSQL y los demás servicios.
5. Publicar APK inmutable del mismo SHA, con firma existente; verificar metadata,
   descarga/checksum y observaciones. Smoke de login/proyectos, novedades y
   App→AX42 autenticado. No crear tickets ni prompts de prueba en PROD.
6. Sólo tras todos los PASS, solicitar una vez el aviso durable al operador.

La aceptación final en el dispositivo comprueba Novedades y adjuntar una imagen
al siguiente prompt real de un Cambio abierto; no exige otra WorkSession.
