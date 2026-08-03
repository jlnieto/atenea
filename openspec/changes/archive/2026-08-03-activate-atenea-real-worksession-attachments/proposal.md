## Why

Atenea already stores bounded synthetic WorkSession attachments on AX42, but
production creation is disabled and an uploaded screenshot cannot be bound to
a turn or delivered to Codex. The external backup prerequisite is now met, so
the next safe step is to activate real screenshots only for newly eligible
Atenea WorkSessions without widening other projects or worker authority.

## What Changes

- Replace display-name-based synthetic-only admission with a global kill
  switch plus an exact canonical-project allowlist; activate only `atenea` in
  this change and snapshot one policy revision on newly created WorkSessions.
- Keep synthetic acceptance separate from real attachment ownership and make
  operator-upload classification server-derived rather than caller-selected.
- Add an additive V62 binding between an operator turn and an ordered, bounded
  set of immutable image attachments, including idempotent turn submission and
  an AgentRun attachment-manifest fingerprint.
- Extend the closed worker workload as `project-codex-v3` so it carries only
  exact attachment UUID, media type, size and SHA-256 references—never bytes,
  filenames or filesystem paths.
- Extend the installed attachment service compatibly for real project/session/
  workspace ownership and make the fixed Atenea runner verify, materialize and
  pass only those images through `codex exec --image` inside the existing
  sandbox.
- Integrate upload, clipboard paste, selection and removal into the web
  conversation composer with one clear Send action and an actionable
  capability state.
- Preserve list/download and immutable history when creation is disabled;
  treat retention dates as minimum keep/eligibility boundaries and defer
  destructive general deletion to a separate change.
- Deploy schema, backend and worker support disabled, prove rollback-image
  compatibility, then require separate production-rollout authorization and
  one operator-assisted real Atenea screenshot canary before enabling the
  exact project.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `worksession-attachments`: Add governed real-project eligibility, immutable
  turn binding, safe operator classification and state-first web composition.
- `remote-worker-control`: Add the exact `project-codex-v3` image-reference
  workload and closed runner materialization boundary.
- `remote-work-continuity`: Preserve bound image identity across disconnect,
  retry and backend/worker restart without re-upload or duplicate execution.
- `worker-operational-safety`: Require a non-empty attachment backup/check/
  isolated-restore canary before authoritative Atenea activation.
- `atenea-project-onboarding`: Permit authoritative Atenea screenshots only
  after the project-specific gate, new-session policy snapshot and complete
  activation acceptance pass.

## Impact

- Atenea repository: additive V62 migration, attachment/project policy,
  idempotent turn contract, immutable binding/read models, dispatch v3,
  authenticated APIs, web composer and focused/full tests.
- Programme/worker repository: compatible attachment-service extension,
  project runner/worker v3 schema, materialization cleanup, installers,
  verification, rollback and evidence tooling.
- AX42: controlled updates to the attachment and AgentRun services plus a
  bounded ephemeral image-materialization root; no project runtime or
  unrelated slot authority.
- Production rollout: one new backend secret mount and private endpoint,
  default-off global/project gates, V62 expand-only migration and one exact
  Atenea-only activation after explicit authorization.
- Unchanged: production PostgreSQL content, deployment authority, preview
  routing, Beautips eligibility/resources, Android native upload, non-image
  Codex inputs and automatic attachment deletion.
