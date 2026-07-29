## Why

Remote WorkSessions can run and retain browser evidence, but operators still
need host ports, manual tunnels or project-specific commands to reach a live
development UI. Phase 6 introduces an ownership-safe private preview boundary
so web and Android show one truthful readiness state and an expiring
session-scoped route without exposing development services to the Internet.

## What Changes

- Persist preview identity, ownership, lifecycle revision, readiness, route
  expiry and actionable failure state under one WorkSession and optional
  AgentRun.
- Add a versioned authenticated AX42 preview protocol that reconciles only
  persisted session-owned runtime allocations and never accepts arbitrary
  upstream addresses.
- Publish ready previews through an AX42 tailnet-only reverse proxy with
  stable opaque routes; public sharing remains disabled in this phase.
- Generate a session-resolved localhost compatibility tunnel without exposing
  credentials or requiring operators to discover worker ports.
- Register Playwright screenshots through the archived WorkSession attachment
  contract and retain exact DOM plus inspected desktop/mobile evidence after
  preview teardown.
- Add concise web and Android preview read models with one visible state, next
  action and ready URL when applicable.
- Reconcile persisted preview ownership after control-plane, proxy and worker
  restart; expiry or rollback removes only the exact route and runtime
  projection while preserving Git, Codex state and retained attachments.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `private-development-preview`: define fail-closed ownership, readiness,
  tailnet routing, route expiry, localhost compatibility, browser evidence and
  operator-surface behavior.
- `isolated-project-runtime`: bind preview publication and cleanup to the exact
  persisted WorkSession runtime allocation.
- `worker-operational-safety`: constrain the preview protocol, proxy exposure,
  restart reconciliation and ownership-safe cleanup.
- `worksession-attachments`: require preview browser evidence to use exact
  WorkSession and optional AgentRun attachment ownership.

## Impact

- Atenea/PostgreSQL receives an additive preview registry and authenticated
  WorkSession-scoped web/mobile APIs.
- AX42 receives a private preview coordinator/proxy integration and mediated
  lifecycle commands; rootful Docker stays masked and rootless slot ownership
  remains unchanged.
- Existing runtime manifests, allocation records, Playwright tooling,
  attachment storage, web UI and Android client are extended.
- Production routing, public endpoints, production PostgreSQL, deploy
  authority, other WorkSessions and Beautips remain outside the mutation
  boundary.
