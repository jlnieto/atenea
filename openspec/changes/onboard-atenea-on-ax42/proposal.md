## Why

The platform can route synthetic AgentRuns and can build, run and inspect an
administrative Atenea development runtime on AX42, but no real project is
schedulable. The worker protocol still accepts only `synthetic-routing-v1`,
so it cannot prove that an operator prompt changes the exact Atenea
WorkSession worktree or completes the normal publish/close lifecycle.

Phase 8 requires one change per project. Atenea is first because its canonical
source, manifest, synthetic development data and rollback have already passed
the relocation gate, while the production/control plane must remain on the
Atenea host.

## What Changes

- Pin Atenea onboarding to GitHub repository `jlnieto/atenea`, branch
  `feature/actualizar-conversacion-en-web` and an explicitly accepted commit.
- Add an exact-project, default-disabled real Codex workload to the
  authenticated worker protocol without granting arbitrary command, path,
  endpoint or daemon authority.
- Bind dispatch to a persisted Atenea WorkSession allocation, mirror,
  worktree, manifest hash, slot and Codex thread identity.
- Exercise a real operator prompt, tests/build, private runtime/preview,
  desktop/mobile Playwright, disconnect/reconnect, publish/sync/close and
  exact cleanup against disposable control-plane state.
- Keep production routing, production PostgreSQL, deployment authority,
  external endpoints and every non-Atenea project unchanged.
- Roll back by disabling Atenea admission for new sessions, reconciling or
  cancelling only its exact active run, and preserving Git/evidence.

## Capabilities

### New Capabilities

- `atenea-project-onboarding`: Defines canonical source, real prompt
  execution, runtime/preview verification, delivery, close and rollback gates
  for making Atenea the first schedulable real project on AX42.

### Modified Capabilities

- `remote-worker-control`: Adds an allowlisted real-project workload with
  exact workspace and Codex lifecycle ownership.
- `isolated-project-runtime`: Distinguishes completed Atenea relocation from
  real-project scheduling acceptance.
- `worker-operational-safety`: Constrains real Codex execution and cleanup to
  the persisted Atenea WorkSession projection.

## Impact

- Programme repository: worker protocol/runner, install/rollback tools,
  project allowlist, acceptance tests and specifications.
- Atenea source: remote request contract and focused tests, plus only minimal
  manifest/runtime adjustments proven necessary by acceptance.
- AX42: one temporary real Atenea WorkSession in a free rootless slot, private
  runtime and retained sanitized evidence.
- Atenea production: no deployment, schema, endpoint, credential, routing or
  production database change. Acceptance uses disposable control-plane state.
- Other projects: remain unschedulable and untouched.
