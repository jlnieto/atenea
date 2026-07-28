## Why

Atenea development still depends on a mutable worktree and Docker workflow on
the production/control-plane host. The runtime contract is now archived and the
previously dirty Atenea work has been reconciled to a clean GitHub branch, so
development can move to AX42 without moving production authority or copying
uncommitted state.

## What Changes

- Pin the relocation base to the reviewed GitHub branch
  `feature/actualizar-conversacion-en-web` and its accepted entry commit, then
  create only GitHub-backed, session-owned Atenea development worktrees on
  AX42.
- Add a schema-valid Atenea runtime manifest and bounded runtime adapter for its
  Java 21, Maven, Node, Compose, PostgreSQL and Playwright development
  lifecycle.
- Use an empty PostgreSQL 16 development database initialized exclusively by
  the versioned Flyway migrations, a synthetic operator fixture and disabled
  external integrations; production data and credentials are never copied.
- Run Atenea build, tests, development runtime, health, DOM and inspected
  desktop/mobile visual checks on AX42, retaining declared logs and artifacts.
- Document AX42 as the normal Atenea development location while keeping the
  existing control-plane worktree available and untouched as rollback.
- Prove that an administrative development session survives laptop disconnect,
  without enabling managed AgentRun routing.
- Exercise rollback by stopping only the AX42 Atenea development runtime while
  preserving its worktree and retained evidence.
- Keep production web/mobile APIs, PostgreSQL, secrets, backups, monitoring,
  deployment authority, current containers and AgentRun routing unchanged.
- Do not remove the legacy executor or make any other project schedulable.

## Capabilities

### New Capabilities

- `atenea-development-relocation`: Defines the canonical-source gate,
  development-only runtime and data boundary, AX42 verification evidence,
  control-plane non-impact and rollback requirements for relocating Atenea
  development.

### Modified Capabilities

- `isolated-project-runtime`: Extends the project activation contract to cover
  Atenea's dedicated relocation gate, real Compose lifecycle and
  session-owned development evidence without granting daemon authority.

## Impact

- Programme repository: OpenSpec contracts, Atenea manifest/adapter support,
  validation suites, relocation evidence and operator documentation.
- Atenea repository during implementation: versioned runtime manifest,
  development-only lifecycle/browser helpers and documentation changes made
  through the selected GitHub branch.
- AX42: one bounded development mirror/worktree, rootless runtime resources,
  synthetic database and retained test/browser artifacts.
- Atenea production: no endpoint, schema, production database, secret,
  container, deploy/rollback or routing change.
