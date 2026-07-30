## Why

Atenea can now run real Atenea and Beautips Codex sessions on AX42, but the
operator cannot see or control the effective Codex model, reasoning effort or
Codex version from the web or Android conversation. The remote runner accepts
only a prompt and thread identity, invokes Codex with user configuration
ignored, and does not persist the effective execution profile.

Remote runs expose coarse AgentRun lifecycle state, while useful Codex events
are not normalized into a safe, durable progress timeline. When execution
stalls, normal recovery still depends on an administrator inspecting Atenea
and AX42 directly. Atenea already has FCM device registration and event-specific
push delivery, but remote terminal completion does not use that path and the
current dispatch design is not a reusable notification platform.

## What Changes

- Persist an immutable effective model, reasoning effort and Codex version for
  every AgentRun, with explicit platform, project, WorkSession and next-turn
  precedence.
- Expose an allowlisted worker model catalog and let an authenticated operator
  inspect or change settings only for future turns.
- Stream, sanitize, sequence and persist useful intermediate execution events
  without exposing chain-of-thought, raw secrets, unrestricted logs or command
  authority.
- Give web and Android the same current-state, progress and recovery read
  model, with visible cancel, retry and request-reconciliation actions.
- Separate routine operator actions, privileged operations and platform
  administration through authenticated, idempotent mediated commands.
- Generalize the existing FCM baseline into a transactional notification
  outbox with per-device delivery, preferences, deduplication, retry,
  expiration and deep links.
- Notify Android when the latest submitted run completes, fails or requires
  action even when the application is closed or in the background.
- Add a managed Codex inventory, compatibility, staged update, canary and
  rollback workflow. Routine WorkSessions never receive update authority.

## Capabilities

### New Capabilities

- `codex-session-operations`: Effective execution profiles, safe progress,
  recovery controls, privileged-operation boundaries and managed Codex
  versions.

### Modified Capabilities

- `remote-worker-control`: Bind execution profile and sanitized progress to
  the durable idempotent dispatch contract.
- `remote-work-continuity`: Make completion/action notifications durable,
  reusable, preference-aware and exactly-once per device/event.

## Impact

- Atenea backend: additive persistence, migrations, orchestration, outbox,
  APIs, SSE events, authorization and audit.
- Atenea web and Android: execution settings, visible progress, recovery
  actions, notification preferences, deep links and actionable error states.
- AX42 worker: protocol evolution, model catalog, exact profile validation,
  normalized progress and version inventory.
- AX42 administration: separately authorized staged Codex update and rollback
  mediator retaining current and previous verified versions.
- Production: additive schema and backend/mobile rollout; no production data
  copy, project deployment authority, arbitrary host command API or secret
  exposure.
