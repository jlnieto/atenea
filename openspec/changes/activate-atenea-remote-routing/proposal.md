## Why

Atenea passed complete disposable onboarding on AX42, but production routing
still leaves new Atenea WorkSessions local and the worker registry is
deliberately disabled. Unlike Beautips, the Atenea route does not yet create
its persisted mirror, worktree, admission and allocation automatically before
the first remote AgentRun.

## What Changes

- Add an authenticated, idempotent and Atenea-scoped workspace activation
  operation using the existing reviewed ownership contracts.
- Update the accepted Atenea source pin to the current canonical branch commit
  while retaining the unchanged reviewed runtime manifest.
- Make the control plane ensure an exact Atenea workspace before first
  dispatch, as it already does for Beautips.
- Enable only the global worker gate and exact Atenea gate in production while
  retaining Beautips and every unrelated route unchanged.
- Open one real Atenea WorkSession, run a bounded no-change validation turn and
  prove continuity, isolation, rollback and re-enable.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `atenea-project-onboarding`: Promote the accepted Atenea project from its
  disabled onboarding state to normal production-control-plane routing.
- `remote-worker-control`: Provision either accepted real project through its
  own exact mediator before dispatch.

## Impact

- Programme branch: worker mediator, installer, tests, contracts and evidence.
- Atenea branch: exact project identity, workspace ensure orchestration and
  focused tests.
- AX42: one new Atenea WorkSession in slot 2 with heavy admission retained for
  its declared runtime; no automatic runtime start.
- Production Atenea: backend-only rollout and two existing routing flags; no
  schema, endpoint, production database or deployment-authority change.
