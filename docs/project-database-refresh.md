# Project Database Refresh

Cada proyecto que permita reemplazar su base de datos local desde producción debe declararlo en `ops/atenea-runtime.yml`.

Esta operación es destructiva y no forma parte del flujo normal de pruebas. Atenea solo debe ejecutarla cuando el operador lo pida explícitamente con frases como:

- `Atenea, actualiza bd`
- `Atenea, actualiza base de datos`
- `Atenea, reemplaza bd`
- `Atenea, reemplaza base de datos`

Después de detectar la intención, Atenea debe pedir confirmación antes de ejecutar nada:

`Se va a reemplazar la base de datos local de <proyecto> por la que hay en producción. Confirma para continuar.`

El operador puede confirmar con `Atenea, confirmo` o `Atenea, confirma`, usando el flujo estándar de confirmación de Atenea Core.

## Contrato

Campos soportados:

```yaml
databaseEngine: mariadb
databaseLocalName: iscspain
databaseReplaceMode: explicit_only
dataRefreshEnabled: true
dataRefreshSourceHost: dedicado
dataRefreshSourceAddress: 79.137.66.61
dataRefreshSourceDatabase: iscspain
databaseStartCommand: docker compose -p proyecto -f compose.yaml up -d db
dataRefreshCommand: bash scripts/dev/replace-local-db-from-prod.sh --force
dataRefreshTimeoutSeconds: 1800
```

Reglas:

- `dataRefreshEnabled` debe ser `true`.
- `databaseReplaceMode` debe ser `explicit_only`.
- `dataRefreshCommand` debe estar en la allowlist del backend.
- Si falta contrato, falta comando o el contrato no es explícito, Atenea devuelve `BLOCKED`.
- Si el script falla, Atenea devuelve `FAILED` con resumen corto y no improvisa comandos alternativos.

## Comandos Permitidos

Para reemplazo:

- `bash scripts/dev/replace-local-db-from-prod.sh --force`
- `./scripts/dev/replace-local-db-from-prod.sh --force`
- `bash ops/import-historic-db.sh`
- `./ops/import-historic-db.sh`

Para preparar la base de datos local antes del reemplazo:

- `docker compose ...`
- `docker-compose ...`

## Estado Inicial

- Recambios: habilitado con el script canónico del repo.
- ISC Spain: habilitado con el script canónico del repo.
- Fomasys: usa `scripts/dev/replace-local-db-from-prod.sh --force`, que conecta por SSH al dedicado, ejecuta `/home/fomasys/scripts/backup-fomasys.sh`, descarga `/home/fomasys/copiaBD/fomasys.sql` e importa la copia local.
- Ediesi: contrato creado, pero `dataRefreshEnabled: false` hasta disponer de un script canónico seguro.

Fomasys no debe usar `mysqldump` remoto desde Atenea contra `79.137.66.61:3306`. MariaDB no debe abrirse a red para este flujo. El dump debe generarse dentro del dedicado con el mismo patrón que ISC Spain y Recambios, y viajar por SSH.

Los proyectos Postgres de Atenea, WAB, Checkpol y Beautips deben declarar contratos equivalentes cuando exista una fuente de datos y un script seguro para cada uno.
