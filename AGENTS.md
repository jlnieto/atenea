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

Creating and testing a new Flyway migration is ordinary development. Applying
migrations outside local/test, deploying or rolling back a shared environment,
publishing an APK, changing real data, or handling secrets/factors requires
explicit authorization.
