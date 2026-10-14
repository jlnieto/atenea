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

## Trabajo desde Atenea

En un DevelopmentChange gestionado, el contrato revisado del worker identifica
explícitamente el modo `ATENEA_MANAGED_CHANGE`. Su base inmutable y ownership
son comprobados por Atenea; no se deduce este modo de nombres de directorios ni
de texto del operador. No se exige que ese worktree tenga un remoto `github`.
No se crean remotos o refs ni se hace fetch/push para imitar el workspace manual.

Codex implementa el cambio y explica qué se hizo y qué no se comprobó. Las
validaciones, PR, integración y publicación pasan por las acciones gestionadas
de Atenea. Si no se ejecutaron pruebas, no se declara que fallaron: se indica
"Pruebas pendientes" y se orienta al operador a Cambio → Validar cambio. Ante
una duda de operación se orienta a "Ayuda con este cambio"; no se solicita otro
cambio, otra sesión ni un prompt duplicado. Terminar un AgentRun no demuestra
que la validación haya pasado. No se sugieren SSH ni comandos privilegiados.

El siguiente apartado se aplica al workspace manual/compartido, no al modo
gestionado explícitamente verificado por el worker. Sus controles no se relajan.

## Autoridad de `main` en el workspace manual

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
