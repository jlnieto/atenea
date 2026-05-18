# Project runtime verification

Atenea treats browser verification as an explicit project contract, not as an ad-hoc Codex guess.

Each project that must be testable from a mobile-driven work session should declare `ops/atenea-runtime.yml` or `ops/atenea-runtime.json` in its repository.

Minimal YAML contract:

```yaml
version: 1
project: Fomasys
runtimeProfile: local-compose
runtimeType: docker-compose
composeFile: compose.yaml
serviceName: app
hostBaseUrl: http://127.0.0.1:8090
internalBaseUrl: http://app:8080
healthPath: /login.htm
browserTestBaseUrl: http://127.0.0.1:8090
browserTestWorkingDirectory: ops/browser-tests
browserTestCommand: npm test
artifactPaths:
  - target/playwright-report
  - target/test-results
```

Production-grade contracts should make the runner self-contained. Atenea can start the declared runtime, poll its health check, run browser verification from the same Docker network, and optionally clean up afterwards:

```yaml
version: 1
project: Fomasys
runtimeProfile: local-compose
browserTestBaseUrl: http://fomasys-app:8080
healthPath: /login.htm
startCommand: docker compose -p fomasys -f compose.yaml up -d --build
healthCommand: docker run --rm --network fomasys_default curlimages/curl:8.10.1 -fsS http://fomasys-app:8080/login.htm
healthStartupTimeoutSeconds: 240
commandTimeoutSeconds: 480
cleanupAfterVerification: false
browserTestCommand: docker run --rm --network fomasys_default -v /srv/atenea/workspace/repos/internal/fomasys/ops/browser-tests:/tests -w /tests mcr.microsoft.com/playwright:v1.60.0-noble bash -lc 'npm ci && BASE_URL=http://fomasys-app:8080 npm test'
artifactPaths:
  - target/playwright-report
  - target/test-results
```

Projects that do not yet have a self-contained web runtime can still declare a `testCommand`. In that mode Atenea runs the canonical suite and returns `PASSED` or `FAILED` instead of treating the project as undocumented:

```yaml
version: 1
project: Recambios
runtimeProfile: maven-test-only
runtimeType: maven
testCommand: mvn test
commandTimeoutSeconds: 1200
artifactPaths:
  - target/surefire-reports
```

## Core capability

The Core capability is `run_project_verification`.

It persists a `ProjectVerificationRun` and returns a decision-oriented response:

- `PASSED`: runtime health is available and the configured smoke browser command passed.
- `FAILED`: runtime health is available but browser verification failed.
- `BLOCKED`: Atenea cannot run a meaningful verification, normally because the contract is missing or the runtime URL is unreachable.
- `FAILED`: also used when a `testCommand` exists but the canonical suite fails.

The spoken response must be short and decision-first. Technical output, paths, commands and artifacts remain in the structured payload for screen inspection.

Database replacement is deliberately separated from runtime verification. See `docs/project-database-refresh.md` for the explicit-only flow that replaces a local database from production after operator confirmation.

## Execution Boundary

The normal Codex work-session container must not receive broad Docker authority. The first production-grade boundary is:

- Codex can edit and reason over the repo.
- Atenea Core owns the controlled verification runner.
- The runner can execute only an allowlisted command family: Docker Compose, Docker run or exec, npm, npx, Maven/Gradle, project scripts, and curl.
- Atenea starts runtime only when the initial health probe is unavailable and the contract declares `startCommand`.
- Atenea waits up to `healthStartupTimeoutSeconds` for health before running browser checks.
- Browser checks receive `PLAYWRIGHT_BASE_URL`, `BASE_URL`, `ATENEA_RUNTIME_BASE_URL`, and `ATENEA_RUNTIME_REPO_PATH`.
- If the runtime is still unavailable, Atenea returns `BLOCKED` with a short operator decision instead of a long technical transcript.

Use an explicit Docker network alias for browser hostnames. Avoid generic names such as `app`: Chromium may upgrade some names to HTTPS internally, which can make a healthy HTTP runtime fail only in browser checks. A project alias such as `fomasys-app` keeps curl health checks and Playwright navigation consistent.

The production backend image includes Docker CLI and Docker Compose v2. The production backend service mounts:

- the repository workspace at `/workspace/repos` for Atenea's registered project paths;
- the same workspace at `/srv/atenea/workspace/repos` for Docker host bind mounts declared by project contracts;
- `/var/run/docker.sock` with Docker group access for the controlled runner.

Codex app server containers remain separate from this runner boundary. They can ask Atenea Core to verify, but they do not need the production backend's runtime authority for normal work-session editing.

Commands declared in the contract run with the repository as working directory. Do not prefix commands with `cd <repo> && ...`; the allowlist expects the command itself to start with an approved executable such as `docker compose`, `docker run`, `npm`, `npx`, `mvn`, `./mvnw`, `./scripts/` or `curl`.
