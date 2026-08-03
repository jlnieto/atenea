# Atenea Web Console

Frontend profesional de operador para Atenea.

## Estado

La web es una SPA React/TypeScript servida por Spring Boot desde `src/main/resources/static`.

El código fuente vive en `web/` y el build produce:

- `src/main/resources/static/index.html`
- `src/main/resources/static/assets/*`

## Comandos

Desde la raíz del repo:

```bash
./scripts/web-build.sh
./scripts/build.sh
```

`./scripts/build.sh` ejecuta el build web antes de empaquetar el backend. Para saltarlo en casos excepcionales:

```bash
ATENEA_BUILD_SKIP_WEB=true ./scripts/build.sh
```

Durante desarrollo frontend puro:

```bash
cd web
npm install
npm run dev
```

## Superficie funcional

La consola web cubre:

- acceso de operador con login, refresh y logout
- shell modular
- Inicio
- Proyectos
- WorkSession
- Conversación de sesión
- Atenea Core
- Estado
- Operaciones
- Archivos
- Costes API
- Diagnóstico
- Ajustes
- Rescate

Las mutaciones sensibles de WorkSession se enrutan por Atenea Core con scope `SESSION`, siguiendo el estándar de la app Android nativa.
