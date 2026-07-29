## Why

Atenea has completed the first real-project onboarding, but all real-project
selection remains disabled. Beautips is the next declared pilot and already
has a healthy administrative runtime on AX42. That runtime is not a managed
WorkSession: it owns persistent slot 1 data, a manual secret boundary and fixed
container identities that must not be adopted or modified implicitly.

The three previously different Beautips source copies are now clean
fast-forwards of canonical GitHub `main`. A dedicated onboarding change is
required to prove a separate disposable WorkSession without turning the
administrative pilot, its data or its credentials into shared platform state.

## What Changes

- Pin Beautips to GitHub `jlnieto/beautips`, branch `main`, commit
  `5044a3b07b3db82895e9c8ff47bc4bc9b0e97130` and reviewed runtime manifest.
- Treat the existing slot 1 administrative runtime, PostgreSQL, Redis,
  assets/imports volumes and manual secret file as foreign retained resources.
- Adapt the manifest and exact-project allowlist to one managed disposable
  Beautips WorkSession in another admitted slot.
- Use empty migrated PostgreSQL, disposable Redis, deterministic synthetic
  fixtures and synthetic assets/imports while disabling all WhatsApp network
  and credential paths.
- Exercise real Codex continuity, build/tests, private preview, Playwright,
  delivery, close, restart, rollback and exact cleanup without changing
  production or the administrative pilot.
- Keep all selection disabled until the complete change passes and is
  archived.

## Capabilities

### New Capabilities

- `beautips-project-onboarding`: Defines canonical source, foreign
  administrative-runtime isolation, synthetic acceptance state, managed
  WorkSession execution, verification, delivery and rollback for Beautips.

## Impact

- Programme repository: Beautips manifest adapter, exact allowlist, tests,
  evidence and operator documentation.
- Beautips source: only reviewed manifest/fixture/test changes proven necessary
  by the managed acceptance.
- AX42: one temporary Beautips WorkSession outside slot 1; the existing manual
  runtime remains intact.
- Atenea production/control plane: no deployment, endpoint, database, secret or
  global routing change.
- External systems: no WhatsApp, production database or public preview access.
