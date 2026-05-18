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

## Estado implementado hoy

El runtime actual ya cubre el flujo de `WorkSession` hasta pull request:

- `SessionCodexOrchestrator` envía turns a Codex App Server y conserva `externalThreadId`.
- `WorkSessionGitHubService` prepara la rama de sesión, hace commit, push y crea pull request en GitHub.
- `WorkSessionGitHubService` sincroniza el estado de la pull request.
- `WorkSessionService` sólo permite cerrar de forma limpia cuando no hay runs pendientes, la pull request publicada está fusionada y el repositorio queda reconciliado.
- `Atenea Core` expone capacidades confirmables para `publish_work_session` y `close_work_session`.
- `/api/mobile/*` conserva aliases de compatibilidad para operar sesión desde móvil.

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
- incidencias y runs auditables

## Navegador headless para Codex

La imagen `docker/codex-app-server.Dockerfile` debe considerarse la superficie de ejecución de Codex para proyectos registrados.

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

## Publicación, merge y cierre

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

## Despliegues desde Atenea

El despliegue generalizado no está implementado todavía. El dominio `operations` tiene SSH y runbooks, pero hoy sólo hay runbooks de diagnóstico y recuperación de Apache.

Para desplegar desde Atenea sin depender de escritorio hay que añadir un contrato explícito de deployment:

- registrar todos los servidores en `managed_host`
- registrar servicios desplegables en `managed_service`
- registrar webs de validación en `managed_website`
- instalar scripts remotos allowlisted bajo `/usr/local/sbin`
- permitir `sudo` sin password sólo para esos scripts concretos
- añadir capacidades confirmables en `Atenea Core`
- persistir cada ejecución como `operations_action_run`
- ejecutar checks posteriores desde Atenea
- devolver al móvil resumen, pasos, métricas y rollback recomendado

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

Desde móvil, el flujo mínimo debe ser:

1. pedir a Atenea el estado del proyecto y de la sesión activa
2. pedir a Codex que ejecute la suite del repo usando sus scripts canónicos
3. pedir a Codex que ejecute pruebas de navegador headless si hay UI
4. pedir a Codex que confirme build o empaquetado
5. publicar la `WorkSession` a PR con confirmación
6. revisar y fusionar la PR
7. sincronizar la PR en Atenea
8. cerrar la `WorkSession`
9. ejecutar despliegue confirmado cuando exista la capacidad de deployment
10. revisar checks HTTP externos y estado del servicio

Para Atenea en este repo, el mínimo antes de desplegar es:

```bash
./scripts/test.sh
./scripts/build.sh
./scripts/android-build.sh
```

Si el cambio toca experiencia web o móvil servida en navegador, Codex debe añadir además una prueba headless o una validación explícita con Chromium.

## Criterio de listo

Una versión está lista para desplegar desde operación móvil sólo si:

- no queda `AgentRun` en ejecución
- la `WorkSession` tiene respuesta final revisable
- los tests y builds canónicos han pasado
- las pruebas headless necesarias han pasado
- la PR está publicada y fusionada
- Atenea ha sincronizado la PR como `MERGED`
- la sesión se puede cerrar sin bloqueo
- el host de destino está accesible por SSH
- los runbooks remotos existen y devuelven JSON estructurado
- las webs del servicio están registradas en `managed_website`
- existe plan de rollback verificable
