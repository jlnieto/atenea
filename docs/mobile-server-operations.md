# Mobile server operations

Este documento fija el contrato operativo para trabajar desde el móvil sin depender de un navegador de escritorio local.

## Objetivo

Atenea debe permitir completar el ciclo real de trabajo aunque el operador sólo tenga el móvil delante:

- abrir o continuar una `WorkSession`
- pedir a Codex que implemente y valide cambios
- ejecutar pruebas de backend, frontend y navegador headless en el servidor
- publicar la sesión a pull request
- sincronizar el estado de la pull request
- cerrar la sesión con reconciliación del repositorio
- operar servidores gestionados por SSH con runbooks auditables
- desplegar sólo mediante acciones confirmadas y verificables

El móvil y el portátil son clientes del plano de control. Una vez que Atenea
acepta durablemente un `AgentRun`, desconectar cualquiera de ellos no debe
detener la ejecución en AX42.

## Estado implementado hoy

El runtime actual ya cubre el flujo de `WorkSession` hasta pull request:

- `SessionCodexOrchestrator` envía turns a Codex App Server y conserva `externalThreadId`.
- `WorkSessionGitHubService` prepara la rama de sesión, hace commit, push y crea pull request en GitHub.
- `WorkSessionGitHubService` sincroniza el estado de la pull request.
- `WorkSessionService` sólo permite cerrar de forma limpia cuando no hay runs pendientes, la pull request publicada está fusionada y el repositorio queda reconciliado.
- `Atenea Core` expone capacidades confirmables para `publish_work_session` y `close_work_session`.
- `/api/mobile/*` conserva aliases de compatibilidad para operar sesión desde móvil.

Este estado actual sigue usando el executor y los repositorios alojados en el
servidor Atenea. La producción de Atenea todavía no enruta `AgentRun` al AX42.
El AX42 dispone de un puente administrativo Codex/tmux, cuatro slots rootless y
un piloto manual de Beautips, pero ese piloto no es una WorkSession gestionada.

El dominio `operations` ya cubre el primer slice de servidores gestionados:

- hosts registrados en `managed_host`
- servicios registrados en `managed_service`
- webs externas registradas en `managed_website`
- ejecución remota por SSH mediante `SshOperationsRemoteExecutor`
- runbooks actuales:
  - `sudo /usr/local/sbin/atenea-host-status`
  - `sudo /usr/local/sbin/atenea-apache-status`
  - `sudo /usr/local/sbin/atenea-apache-recover`
- checks HTTP externos desde Atenea
- detección de webs degradadas por latencia, no sólo por código HTTP
- incidencias y runs auditables

## Piloto administrativo de Atenea en AX42

La reubicación aceptada valida desarrollo administrativo de Atenea en AX42,
pero no activa routing gestionado de `AgentRun`. El flujo operativo aceptado
es:

1. usar GitHub como fuente canónica y verificar la rama
   `feature/actualizar-conversacion-en-web`;
2. seleccionar exclusivamente la WorkSession administrativa
   `41c0ff95-e555-4773-b7b4-60903a3af1ad`, su worktree y su asignación
   persistida `slot2/heavy1`;
3. adquirir de nuevo admisión normal y heavy antes de cualquier arranque;
4. validar manifest, commit, tree, ownership completo y recursos rootless antes
   de crear o arrancar el runtime;
5. conservar logs y artefactos bajo la raíz declarada de la WorkSession;
6. mantener producción, preview, deploy, PostgreSQL productivo, secretos,
   monitoring y rollback productivo en Atenea.

El rollback administrativo sólo puede actuar sobre el runtime
`ws-41c0ff95e5554773b7b460903a3af1ad` cuando coinciden la WorkSession, runtime,
proyecto, engine, servicio, slot persistido e ID inmutable observado. Un
recurso sin labels, parcialmente etiquetado, extranjero o ambiguo debe
rechazarse antes de mutar nada. El rollback elimina sólo contenedores, red,
listeners y delivery efímero propios; conserva mirror, worktree, Git e índice,
allocation, volumen PostgreSQL etiquetado, engine state, logs y evidencias.
Repetirlo debe eliminar cero recursos.

El reinicio autorizado de AX42 demostró reconciliación `report-only` desde
registros persistidos. Con admisión `released/released` y runtime ausente no se
debe recrear, arrancar, reasignar slot ni inventar ownership. La sesión tmux
administrativa no es persistente y terminó con el reboot; reanudar trabajo
requiere crear explícitamente una sesión administrativa nueva, sin presentarla
como `AgentRun`, lease o route.

La evidencia no secreta se conserva bajo la WorkSession en:

- `runs/task-7.1-atenea-runtime-rollback`;
- `runs/task-7.2-rollback-idempotence`;
- `runs/task-7.3-restart-reconciliation`;
- `runs/task-8.1-final-non-impact`;
- `runs/task-8.2-final-worker-audit`;
- `runs/task-8.3-operator-handoff`.

Cada raíz contiene `SHA256SUMS`, comandos reproducibles, resultado estructurado
y controles de sanitización. No deben copiarse ni adjuntarse `auth.json`,
historial de Codex, sesiones internas, tokens, cookies, credenciales, dumps de
entorno o datos productivos.

## Navegador headless para Codex

Actualmente `docker/codex-app-server.Dockerfile` es la superficie local de
ejecución de Codex para proyectos registrados. En la arquitectura objetivo, el
manifest versionado de cada proyecto y el executor aislado de AX42 reemplazan
esa imagen como superficie normal de desarrollo. Los contenedores Codex
prod/preview/rescue que hoy siguen en Atenea son estado legado hasta la retirada
controlada del executor antiguo.

Codex App Server debe arrancar con el guard de autenticacion en modo ChatGPT. Si el guard detecta API key o tokens incompletos, bloquea el contenedor antes de aceptar trabajo. El contrato completo esta en `docs/codex-auth-and-costs.md`.

Debe incluir, como mínimo:

- Node.js y npm
- Java y Maven para repos Java
- Git y OpenSSH
- GitHub CLI para inspección manual de PRs cuando Codex lo necesite
- Chromium headless disponible como `/usr/bin/chromium`
- variables estándar para frameworks de navegador:
  - `CHROME_BIN=/usr/bin/chromium`
  - `PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium`
  - `PUPPETEER_CACHE_DIR=/workspace/codex-home/puppeteer`
  - `PLAYWRIGHT_BROWSERS_PATH=/workspace/codex-home/playwright-browsers`

Regla operativa para Codex:

- primero debe usar los scripts del proyecto, por ejemplo `scripts/test.sh`, `npm test`, `npm run test:e2e`, `npm run build` o el contrato equivalente de cada repo
- si el proyecto usa Playwright y falta el browser revisionado, Codex puede ejecutar `npx playwright install chromium`; el cache queda persistido bajo `/workspace/codex-home`
- si el proyecto usa Puppeteer, debe usar el Chromium del sistema salvo que el propio proyecto requiera otra cosa
- las pruebas visuales deben ejecutarse en modo headless y, cuando aporten valor, guardar capturas o trazas dentro del workspace del repo

Para que esto funcione en todos los proyectos, cada repo operado por Atenea debería documentar en su `AGENTS.md` o README:

- comando de instalación
- comando de test unitario
- comando de build
- comando de servidor local para preview
- comando de test navegador headless
- URL local que debe validar Codex
- datos de prueba permitidos

Las capturas, trazas e informes generados por estas pruebas deben registrarse
como adjuntos de la `WorkSession` y del `AgentRun` de origen. “Última captura”,
“penúltima captura” y “las N últimas capturas” se resuelven dentro de esa sesión
y fuente; una carpeta global del servidor o del portátil no forma parte del
contrato remoto.

## Bases de datos de desarrollo

Las bases usadas para build, tests, previews y trabajo interactivo pertenecen al
plano de ejecución AX42 y deben estar aisladas según el manifest del proyecto.
Crear, migrar y sembrar datos puede automatizarse. Reemplazar o restaurar una
base exige confirmación explícita, deja auditoría y sólo puede operar sobre una
identidad clasificada como desarrollo.

El worker debe rechazar antes de mutar cualquier host, credencial, nombre de
base o ruta clasificados como producción. Copiar datos de producción a AX42 no
es un comportamiento implícito; requiere una política separada de
anonimización, autorización y retención.

## Publicación, merge y cierre

## Permisos del workspace

Hoy los repositorios bajo `/srv/atenea/workspace/repos` son un recurso
compartido entre backend, Codex App Server, rescue y operador de host. Las reglas
siguientes describen esa realidad transitoria, no el destino de desarrollo:

- grupo host `atenea` como grupo propietario del workspace operativo
- directorios con bit `setgid` para heredar grupo
- escritura de grupo en working tree y `.git`
- `umask 0002` en procesos que crean archivos dentro del workspace
- `git config core.sharedRepository group` en cada repo registrado
- ACL por defecto cuando `setfacl` está disponible

El script canónico para reparar o aplicar esta política es:

```bash
./scripts/workspace-permissions.sh
```

Debe ejecutarse cuando se registre o clone un repositorio nuevo, y también después de cualquier operación manual que haya creado archivos como `root` o con grupo ajeno al workspace.

En el objetivo, GitHub es canónico y AX42 mantiene mirrors y un worktree aislado
por `WorkSession`. El servidor Atenea no debe conservarse como workspace normal
de build, Codex, preview o desarrollo una vez completada la retirada del executor
antiguo.

El backend ya puede publicar una sesión:

1. preparar o recuperar `workspaceBranch`
2. stage y commit de cambios de la sesión
3. push de la rama
4. creación de pull request
5. persistencia de `pullRequestUrl`, `pullRequestStatus`, `finalCommitSha` y `publishedAt`

El backend ya puede sincronizar y cerrar:

1. leer el estado actual de la PR en GitHub
2. marcar `MERGED` cuando GitHub devuelva la PR fusionada
3. bloquear cierre si la PR no está fusionada
4. volver a `baseBranch`
5. hacer fast-forward contra `origin/baseBranch`
6. borrar rama local y remota de sesión
7. verificar branch final y worktree limpio

Lo que no está implementado todavía es fusionar la pull request desde Atenea. Hoy el merge debe hacerse fuera de Atenea, normalmente desde GitHub, y después ejecutar `sync_work_session_pull_request` y `close_work_session`.

Si queremos merge completo desde móvil, el siguiente bloque debe añadir una capacidad confirmable:

- `merge_work_session_pull_request`
- riesgo `DESTRUCTIVE`
- requiere confirmación explícita
- usa GitHub API para fusionar sólo la PR asociada a la `WorkSession`
- bloquea si hay checks fallidos, PR cerrada, rama divergente o estado no publicado
- registra el resultado en `CoreCommand`
- obliga a ejecutar sync y cierre reconciliado después

## Scripts locales de despliegue de Atenea

El repo ya incluye scripts que hoy despliegan el propio backend Atenea desde el
servidor:

```bash
./scripts/deploy-preview.sh
./scripts/deploy-prod.sh
./scripts/release.sh
```

Contrato:

- `deploy-preview.sh` reconstruye `atenea-backend-preview` con el stack de `/srv/atenea/platform/stacks/preview` y exige health OK.
- `deploy-prod.sh` reconstruye `atenea-backend-prod` con el stack de `/srv/atenea/platform/stacks/prod` y exige health OK.
- `release.sh` ejecuta tests, build backend, deploy preview y deploy prod.
- `build.sh` empaqueta sin repetir tests por defecto; la validacion canónica previa es `test.sh`, que usa la base aislada de test.
- `release.sh` solo compila y publica APK si se invoca con `ATENEA_RELEASE_PUBLISH_APK=true`.

Estos scripts documentan el mecanismo actual, pero no autorizan al Codex normal
a desplegar producción. La arquitectura objetivo compila y valida Atenea en
AX42, publica un artefacto inmutable asociado a un commit revisado y entrega la
promoción a una capacidad de operaciones separada en el servidor Atenea.

Esa capacidad usa permisos restringidos, confirmación explícita, health check,
auditoría y rollback a una versión conocida. Producción, PostgreSQL, secretos,
backups, monitorización y deploy/rollback permanecen en Atenea; el worktree,
build, runtime y base de desarrollo de Atenea se trasladan a AX42.

## Despliegues desde Atenea Core

El despliegue generalizado de proyectos cliente desde comandos de voz no está implementado todavía. El dominio `operations` tiene SSH y runbooks, pero hoy sólo hay runbooks de diagnóstico y recuperación de Apache.

Para desplegar proyectos cliente desde Atenea sin depender de escritorio hay que añadir un contrato explícito de deployment:

- registrar todos los servidores en `managed_host`
- registrar servicios desplegables en `managed_service`
- registrar webs de validación en `managed_website`
- instalar scripts remotos allowlisted bajo `/usr/local/sbin`
- permitir `sudo` sin password sólo para esos scripts concretos
- añadir capacidades confirmables en `Atenea Core`
- persistir cada ejecución como `operations_action_run`
- ejecutar checks posteriores desde Atenea
- devolver al móvil resumen, pasos, métricas y rollback recomendado
- aceptar sólo artefactos inmutables y versionados producidos desde commits
  revisados
- impedir que un `AgentRun` ordinario reciba credenciales o autoridad de
  producción

Runbook remoto recomendado por servicio:

```text
sudo /usr/local/sbin/atenea-deploy <service>
sudo /usr/local/sbin/atenea-deploy-status <service>
sudo /usr/local/sbin/atenea-rollback <service> <release>
```

La salida debe ser JSON con esta forma:

```json
{
  "action": "DEPLOY",
  "host": "vps-atenea",
  "service": "atenea",
  "status": "OK",
  "summary": "Deploy completado y validado.",
  "release": "2026-05-16T12-30-00Z_abc123",
  "steps": [
    {
      "name": "pull_release",
      "status": "OK",
      "detail": "Código actualizado a abc123."
    },
    {
      "name": "restart_service",
      "status": "OK",
      "detail": "Servicio reiniciado correctamente."
    }
  ],
  "metrics": {
    "durationSeconds": 42,
    "healthyWebsites": 3,
    "totalWebsites": 3
  }
}
```

## Mapa de servidores a registrar

Los hosts iniciales deben quedar dados de alta como servidores gestionados:

- `dedicado-iscspain`: dedicado con proyectos `iscspain` en todos los idiomas, `recambios` y `fomasys`
- `dedicado-ediesi`: dedicado con `ediesi`
- `vps-wab-checkpol`: VPS con `wab` y `checkpol`
- `vps-atenea`: VPS con `atenea`
- `vps-beautips`: VPS con `beautips`

Ejemplo de alta:

```sql
INSERT INTO managed_host (name, description, environment, ssh_host, ssh_port, ssh_user, ssh_key_path)
VALUES
  ('dedicado-iscspain', 'Dedicado: iscspain, recambios y fomasys', 'prod', '<host>', 22, 'atenea-ops', '/run/secrets/atenea_dedicado_iscspain_key'),
  ('dedicado-ediesi', 'Dedicado: ediesi', 'prod', '<host>', 22, 'atenea-ops', '/run/secrets/atenea_dedicado_ediesi_key'),
  ('vps-wab-checkpol', 'VPS: wab y checkpol', 'prod', '<host>', 22, 'atenea-ops', '/run/secrets/atenea_vps_wab_checkpol_key'),
  ('vps-atenea', 'VPS: atenea', 'prod', '<host>', 22, 'atenea-ops', '/run/secrets/atenea_vps_atenea_key'),
  ('vps-beautips', 'VPS: beautips', 'prod', '<host>', 22, 'atenea-ops', '/run/secrets/atenea_vps_beautips_key');
```

Las claves privadas no deben guardarse en base de datos. `ssh_key_path` apunta a ficheros montados en el host o contenedor donde corre Atenea.

## Checklist antes de desplegar una versión

Desde móvil, el flujo objetivo mínimo debe ser:

1. pedir a Atenea el estado del proyecto y de la sesión activa
2. pedir a Codex que ejecute la suite del repo usando sus scripts canónicos
3. pedir a Codex que ejecute pruebas de navegador headless si hay UI
4. pedir a Codex que confirme build o empaquetado
5. publicar la `WorkSession` a PR con confirmación
6. revisar y fusionar la PR
7. sincronizar la PR en Atenea
8. cerrar la `WorkSession`
9. seleccionar el artefacto versionado producido por el flujo aceptado
10. ejecutar despliegue confirmado mediante la capacidad separada de operations
11. comprobar health check y conservar el rollback de la versión anterior
12. revisar checks HTTP externos y estado del servicio

Para Atenea en este repo, el mínimo de validación sigue siendo:

```bash
./scripts/test.sh
./scripts/build.sh
./scripts/android-build.sh
```

Si el cambio toca experiencia web o móvil servida en navegador, Codex debe añadir además una prueba headless o una validación explícita con Chromium.

Durante la transición estos scripts pueden seguir ejecutándose en el mecanismo
actual por un operador autorizado. Eso no constituye aceptación del futuro
despliegue controlado ni permiso para invocarlos desde una WorkSession normal.

## Contrato de recuperación Apache

La acción `recover_apache_hung_processes` debe priorizar recuperar el servicio.

Reglas runtime:

- ejecuta primero el runbook remoto `sudo /usr/local/sbin/atenea-apache-recover`
- después sólo hace una validación web rápida y acotada
- la validación post-recuperación usa como máximo 3 webs activas
- cada check post-recuperación queda limitado a 1500 ms
- no debe recorrer todas las webs con timeouts largos antes de devolver control al operador

Si el runbook termina OK pero la validación rápida detecta lentitud o caída, la incidencia queda en `MITIGATING`, no en `RESOLVED`. Esto permite actuar rápido sin vender un falso verde.

## Contrato de salud web

Una web ya no se considera sana sólo por devolver el HTTP esperado.

Cada `managed_website` tiene:

- `timeout_millis`: límite duro del check
- `degraded_threshold_millis`: umbral de latencia aceptable

Estados posibles del check:

- `OK`: HTTP esperado y duración dentro del umbral
- `DEGRADED`: HTTP esperado pero duración por encima del umbral
- `DOWN`: timeout, error de red o HTTP inesperado

`DEGRADED` cuenta como no saludable para que la app no muestre punto verde cuando las páginas cargan lentas. Una revisión de estado que detecta webs lentas o caídas abre o actualiza una incidencia operativa sobre el servicio web.

## Criterio de listo

Una versión está lista para desplegar desde operación móvil sólo si:

- no queda `AgentRun` en ejecución
- la `WorkSession` tiene respuesta final revisable
- los tests y builds canónicos han pasado
- las pruebas headless necesarias han pasado
- los adjuntos de evidencia pertenecen a la WorkSession correcta
- la PR está publicada y fusionada
- Atenea ha sincronizado la PR como `MERGED`
- la sesión se puede cerrar sin bloqueo
- el host de destino está accesible por SSH
- los runbooks remotos existen y devuelven JSON estructurado
- las webs del servicio están registradas en `managed_website`
- existe plan de rollback verificable
- el despliegue usa un artefacto versionado y una capacidad separada de Codex
- ninguna operación de base de datos apunta a producción desde AX42
