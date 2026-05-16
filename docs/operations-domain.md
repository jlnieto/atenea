# Operations domain

Este documento describe el primer slice runtime del dominio `operations`.

## Objetivo

El caso inicial cubierto es operar servidores gestionados desde Atenea Core y desde la app mobile, con especial atención al runbook de recuperación de Apache cuando quedan procesos colgados y las webs empiezan a responder lento.

## Modelo

La migración `V36__create_operations_domain.sql` añade:

- `managed_host`: servidor o VPS que Atenea puede operar por SSH.
- `managed_service`: servicio gestionado dentro de un host, por ejemplo Apache.
- `managed_website`: webs que Atenea valida desde fuera del servidor controlado.
- `operations_incident`: incidencia operativa abierta, mitigada, fallida o resuelta.
- `operations_action_run`: ejecución concreta de un runbook remoto.

Los checks HTTP de webs se ejecutan desde Atenea. No hace falta instalar un script tipo `atenea-check-sites` en el servidor controlado.

Los runbooks remotos pueden devolver JSON estructurado. Atenea conserva compatibilidad con salidas antiguas, pero si el JSON incluye `summary`, `steps` y `metrics`, Core usa esos datos para responder con:

- qué script se ha ejecutado
- qué pasos se han completado dentro del servidor
- qué métricas relevantes se observaron
- cómo se validó el resultado localmente
- qué checks web externos desde Atenea quedaron correctos o fallidos

## Core

`Atenea Core` expone capacidades del dominio `OPERATIONS`:

- `list_hosts`
- `get_host_status`
- `check_service`
- `recover_apache_hung_processes`
- `list_operations_incidents`
- `close_operations_incident`

`recover_apache_hung_processes` es destructiva y requiere confirmación de Core antes de ejecutar el runbook remoto.

Ejemplos de comandos:

- `lista los servidores gestionados`
- `revisa el dedicado`
- `comprueba apache en el dedicado`
- `recupera apache en el dedicado`
- `lista incidencias abiertas de servidores`
- `cierra incidente 12`

## Mobile

La app mobile recibe incidentes operativos en `GET /api/mobile/inbox` con:

- `type = OPERATIONS_INCIDENT`
- `hostId`
- `hostName`
- `incidentId`

También hay lectura compacta:

- `GET /api/mobile/operations/hosts`
- `GET /api/mobile/operations/hosts/{hostId}/status`
- `GET /api/mobile/operations/incidents`

La acción de recuperación debe seguir pasando por Core para conservar confirmación, auditoría y eventos de comando.

## Servidor controlado

El servidor remoto necesita scripts pequeños y auditables bajo `/usr/local/sbin`, ejecutables por el usuario SSH configurado en `managed_host` mediante `sudo` sin contraseña sólo para esos scripts.

Comandos esperados por Atenea:

- `sudo /usr/local/sbin/atenea-host-status`
- `sudo /usr/local/sbin/atenea-apache-status`
- `sudo /usr/local/sbin/atenea-apache-recover`

Formato recomendado de salida:

```json
{
  "action": "APACHE_RECOVERY",
  "host": "seneca",
  "status": "OK",
  "summary": "Apache reiniciado, procesos sobrantes terminados y validación local correcta.",
  "steps": [
    {
      "name": "snapshot_before",
      "status": "OK",
      "detail": "Antes de intervenir: active=active, procesos=24."
    },
    {
      "name": "verify_service",
      "status": "OK",
      "detail": "systemctl is-active apache2 = active; apachectl configtest = OK."
    }
  ],
  "metrics": {
    "apacheProcessesBefore": 24,
    "leftoverKilled": 2,
    "apacheProcessesAfter": 10
  }
}
```

La clave SSH usada por Atenea debe estar disponible en el host donde corre el backend y su ruta se guarda en `managed_host.ssh_key_path`.

Ejemplo de sudoers:

```sudoers
atenea ALL=(root) NOPASSWD: /usr/local/sbin/atenea-host-status, /usr/local/sbin/atenea-apache-status, /usr/local/sbin/atenea-apache-recover
```

## Alta manual inicial

Ejemplo orientativo:

```sql
INSERT INTO managed_host (name, description, environment, ssh_host, ssh_port, ssh_user, ssh_key_path)
VALUES ('dedicado', 'Servidor dedicado principal', 'prod', '203.0.113.10', 22, 'atenea', '/run/secrets/atenea_dedicado_key');

INSERT INTO managed_service (host_id, name, service_type, systemd_unit, process_pattern)
VALUES (1, 'apache', 'WEB_SERVER', 'apache2', 'apache2');

INSERT INTO managed_website (host_id, name, url, expected_status)
VALUES
  (1, 'cliente-a', 'https://cliente-a.example', 200),
  (1, 'cliente-b', 'https://cliente-b.example', 200),
  (1, 'propia', 'https://propia.example', 200);
```

No guardes claves privadas en la base de datos. `ssh_key_path` apunta a un fichero gestionado fuera de la aplicación.
