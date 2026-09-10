# AGENTS

This is `jlnieto/atenea`. It owns the Spring Boot application, web and
Android clients, Flyway migrations, and product runtime.

The worker/runtime platform lives in `../platform`. Integrate through
versioned protocols and schemas; do not copy platform implementation or add
a compile-time dependency on it.

Preserve these boundaries:
- clients and AgentRuns do not choose or receive shell commands, host paths,
  slots, credentials, or production authority;
- cross-host effects remain ownership-checked, durable, idempotent, and
  reconcilable;
- AgentRun completion is not validation, review, merge, or deployment.

Use `./scripts/test.sh` for Maven tests and the scripts under `scripts/` for
repo-specific build/run operations.

Después de un cambio committeado y antes de abrir una PR, ejecuta
`./scripts/validate-change`. Si UFD produce un plan, respétalo y no lo sustituyas
manualmente por una selección más permisiva.

## Autoridad de `main`

`github/main` es la autoridad de código e integración de Atenea app. `origin/main`
es únicamente el mirror operativo del workspace compartido. No se propaga ningún
merge automáticamente. Antes de cualquier build, preview, producción o release
desde ese workspace, `origin/main` debe ser ancestro de `github/main`; si no lo es,
se detiene el flujo y se resuelve la divergencia. La única sincronización permitida
es un fast-forward explícito de `github/main` hacia `origin/main`, nunca un force push.
El procedimiento está documentado en `docs/mobile-server-operations.md`.

Creating and testing a new Flyway migration is ordinary development. Applying
migrations outside local/test, deploying or rolling back a shared environment,
publishing an APK, changing real data, or handling secrets/factors requires
explicit authorization.
