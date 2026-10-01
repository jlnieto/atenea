# Estabilización de validación y sesión móvil

## Alcance y autoridad

La corrección afecta al contrato Android de renovación y al corredor de
validación App–Platform. No modifica la implementación de monitorización ni
crea otro DevelopmentChange, WorkSession o AgentRun. No incluye despliegue,
publicación Android, cambios de roles, flags, credenciales ni datos PROD.

Los clientes siguen solicitando operaciones simbólicas: nunca comandos,
paths, imágenes, credenciales o slots. Platform conserva la autoridad sobre
el aislamiento, la admisión, los recursos y la limpieza.

## Renovación de sesión

Login declara ANDROID y la etiqueta del dispositivo. La renovación FAMILY_V1
envía únicamente refreshToken, sessionProtocolVersion y singleFlightRefresh.
Si se adopta un token legacy, el backend utiliza sus defaults existentes.
No se relaja la protección contra replay ni se aumenta la duración de tokens.

Activity y FCM comparten una exclusión de renovación a nivel de proceso. Una
respuesta 401 tardía de un acceso anterior no consume otro refresh token. Una
renovación que termina después de logout o de otro login no sobrescribe esa
sesión. Los errores de servidor/protocolo conservan su significado y no se
presentan indiscriminadamente como caducidad.

`src/test/resources/auth-contract/android-family-refresh-v1.json` es una
fixture compartida por los tests Android y la integración backend. No contiene
credenciales reales.

## BACKEND_TEST v2

Platform mantiene una receta y un preparador root-owned, verificados por hash,
y acepta únicamente el hash autorizado del pom. El build recibe sólo esos
archivos; nunca código candidato ejecutable con red. La imagen resultante se
selecciona por su identidad inmutable, no por un tag móvil.

El código candidato y todos los tests Maven se ejecutan offline en un
contenedor rootless, sin capacidades, con no-new-privileges, filesystem de
imagen read-only y red `none`. Sólo se monta el snapshot fuente read-only;
no se exponen sockets Docker, secretos, servicios host, App ni PostgreSQL PROD.
PostgreSQL 16 efímero y los servidores HTTP simulados comparten únicamente el
loopback privado del contenedor. La DB se elimina con ese contenedor.

La preparación de DB, ejecución y limpieza son fases distintas. Los errores
de preparación/herramientas/aislamiento no se atribuyen al código candidato.
Un exit desconocido queda bloqueado como VALIDATION_FAILED, no como un fallo
demostrado de tests. Un fallo de limpieza impide declarar éxito.

Se conserva un diagnóstico root-only por operación, con IDs, fingerprint,
fase, código, exit, duración, identidades de clases de test y hash de salida.
No se conserva ni publica stdout arbitrario, argumentos, variables o paths.
Su hash forma parte del manifest de evidencia. El resumen simbólico seguro
atraviesa el broker y App lo explica en español.

La historia v1 terminal sigue consultable, pero no se reejecuta como v2 ni
satisface las validaciones actuales. El perfil pasa a
`atenea-required-validation-v2`. Siguen siendo obligatorias las cuatro etapas;
no se sustituye ni reduce un plan UFD.

## Validación previa a integración y despliegue

ANDROID_BUILD pasa a `atenea-android-build-v2`: el build de herramientas recibe
únicamente diez archivos de configuración hash-locked, en uno de dos bundles
coherentes revisados (grafo actual y contrato de sesión compartido).
Nunca recibe código candidato, buildSrc, init scripts ni credenciales.
Sólo los literales numéricos versionCode/versionName se normalizan a valores
sintéticos antes del hash y del precache: publicar otra versión de APK no
requiere cambiar Platform. Interpolaciones/código en esos campos se rechazan;
el resto del Gradle permanece hash-locked. La APK candidata conserva su versión
real, que sigue formando parte del fingerprint del código validado.

Platform combina el Dockerfile SDK revisado con su receta/preparador root-owned.
Probes sintéticas compilan APK/tests para precargar los plugins y módulos. Se
sella exclusivamente `caches/modules-2`, sin locks, propiedades globales ni
resultados de tasks. Cada archivo tiene hash y se verifica estructura,
inventario y root ownership. Una segunda compilación sintética fría prueba la
misma receta con caché copiada, UID no root, sin red ni resultados previos.

El snapshot candidato se ejecuta después en otro contenedor rootless con red
none, filesystem read-only y copia privada de la caché verificada en tmpfs.
Se descartan .gradle/build/local.properties previos. La operación construye
`:app:assembleDebug` y ejecuta `testDebugUnitTest` para todos los módulos,
siempre offline. No publica ni firma una APK estable.

La receta real ya superó la prueba Docker, incluido el precache y la
compilación fría; los mocks por sí solos no habrían demostrado esas
postcondiciones. Una configuración no revisada queda bloqueada antes de
ejecutarse: actualizar dependencias requiere revisión explícita de toolchain.

1. Ejecutar los tests focales Platform de backend, broker, schemas, worker e
   installer; incluir los checks root reales que estén omitidos por entorno.
2. Ejecutar la receta BACKEND_TEST v2 real con el pom autorizado, un snapshot
   sintético/candidato aislado y PostgreSQL 16: suite completa, loopback local
   disponible y retirada correcta del contenedor. Maven `-v` no sirve como
   prueba de esta postcondición.
   Ejecutar asimismo la receta Android v2 real, incluyendo su build sintético
   frío y APK/tests candidatos offline; comprobar caché intacta y limpieza.
3. Ejecutar `./scripts/android-build.sh :api:testDebugUnitTest --tests
   com.atenea.android.api.AteneaSessionSecurityClientTest` y la integración
   `OperatorSessionSecurityIntegrationTest` mediante `./scripts/test.sh` en DB
   de test. El modo `ATENEA_TEST_RUNTIME=isolated` permite usar un runtime
   efímero ya preparado sin levantar servicios DEV permanentes; sólo admite
   `atenea_test` por loopback y credenciales sintéticas.
4. Tras commit y antes de PR, ejecutar `./scripts/validate-change` y respetar
   exactamente el plan UFD. No presentar la compilación suplementaria o tests
   simulados como sustitutos de estas comprobaciones.

### Evidencia local y límites (2026-09-30)

- App: compilación main/test, tests focales de validación y 13 tests de
  `OperatorSessionSecurityIntegrationTest` PASS con PostgreSQL efímero y
  Flyway V83. La base de datos compartida DEV/PROD no se tocó.
- Android: `:api:testDebugUnitTest --tests
  com.atenea.android.api.AteneaSessionSecurityClientTest` PASS con el builder
  local. No se publicó APK.
- Platform: imagen BACKEND_TEST v2 construida realmente. La receta precarga
  plugins, dependencias de test y launcher JUnit con identidad Maven fija;
  PostgreSQL 16 privado y la suite Maven arrancan offline sin red. La primera
  ejecución reveló que faltaban un `/workspace` efímero, el workspace root de
  test y límites del pool JDBC. Añadidos al sandbox sin bind del host, los
  26 tests de `WorkSessionFlowIntegrationTest` y la integración de sesión
  móvil pasan. Tests focales Platform e installer PASS. Los checks root y la
  receta Docker Android v2 completa siguen pendientes.
- Diagnóstico inicial: con el sandbox corregido, `main` limpio `4815028`
  reveló 989 tests con 40 fallos y 26 errores; el candidato tenía 993 tests
  y la misma distribución de fallos. Se corrigieron únicamente fixtures y
  aserciones de test: rama base obligatoria, worker sintético registrado,
  observación canónica y contadores relativos al estado inicial del test.
  La suite base ahora termina **989/989 PASS** y la candidata **993/993 PASS**
  mediante `scripts/test.sh` en PostgreSQL 16 efímero y sin red. La última
  ejecución montó el checkout candidato directamente: el preparador descartó
  `.git`, `target/` y cachés/outputs Android antes de compilar de cero; no se
  omitió ningún test.
- La receta Docker Android v2 real aceptó el bundle revisado, precargó las
  dependencias y superó un segundo build sintético frío, offline y sin
  outputs previos: **136 tareas PASS**. La primera ejecución candidata detectó
  que Docker montaba `/work` con `noexec` y AAPT2 no podía arrancar. Platform
  fija ahora `exec` sólo en el tmpfs privado `/work`; `/tmp` conserva `noexec`,
  la fuente sigue en un único bind read-only y el contenedor continúa sin red,
  privilegios ni secretos. Con esa opción, APK debug y tests de todos los
  módulos del candidato terminan **136 tareas PASS** offline.
- Los 15 tests focales Android v2, los 25 tests del mediador con smoke real
  root/systemd/Bubblewrap opt-in, el test del installer, `bash -n` y
  `git diff --check` pasan. No se utilizó App DEV ni PROD.

Estado: **validación local completa; pendiente de integración**. App y
Platform siguen sin commit/PR/despliegue. Tras los commits de App debe
ejecutarse `./scripts/validate-change` antes de abrir la PR y respetarse
íntegramente el plan UFD. El PASS local tampoco sustituye el smoke vertical
posterior al rollout autorizado en la WorkSession 21.

## Prueba de producto después del rollout autorizado

Desplegar coordinadamente primero Platform y después App; publicar Android
sólo con autorización separada y con los pasos anteriores aprobados. No
activar la definición nueva en App antes de que Platform la soporte.

Usar el cambio `59315b6e-59bc-4884-9def-356e1ca86ef4`, WorkSession 21, sin
recrear ticket ni prompt. Confirmar identidad, fuente, workspace y ausencia de
ejecución activa antes de una solicitud normal de Validar cambio. Obtener las
cuatro evidencias actuales completas, persistidas y recuperables tras reinicio.

Confirmar desde el móvil dos renovaciones transparentes de acceso, incluyendo
regreso de segundo plano, sin nuevo login ni revocación inesperada. Confirmar
que un fallo temporal conserva la sesión y que una revocación real sí requiere
autenticación. Si una etapa falla, leer su diagnóstico antes de otro intento.

Sólo esta prueba vertical permite dar el recorrido por operativo. El PASS de
tests focales por sí solo no autoriza afirmarlo ni publicar el ticket Apache.
