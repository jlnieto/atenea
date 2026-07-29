## Context

Phase 3 proved the Atenea project lifecycle administratively. Phase 4 proved
durable authenticated routing with a fixed synthetic workload. Phases 5–7
added attachments, previews and synthetic databases, all default-disabled
after acceptance. The missing composition is a real Codex turn bound to a
canonical project WorkSession and followed through normal Git delivery and
cleanup.

The production Atenea deployment cannot be used as an acceptance control
plane: enabling routing there would mix onboarding with production change.
The accepted approach uses disposable Atenea/PostgreSQL state and the real
GitHub source while continuously fingerprinting production.

## Goals / Non-Goals

**Goals:**

- make only Atenea eligible for a real-project worker workload;
- execute an operator prompt in one exact WorkSession worktree;
- preserve Codex thread continuity and idempotent AgentRun delivery;
- prove build, tests, runtime, private preview and desktop/mobile UI;
- exercise publish, sync, close, disconnect/reconnect and cleanup;
- keep production and every unrelated project unchanged.

**Non-Goals:**

- enabling a cohort or wildcard project selector;
- copying Codex home, auth files, production data or credentials;
- accepting arbitrary shell commands, paths, Git remotes or endpoints;
- deploying to production or changing production routing;
- onboarding Beautips or another project in this change;
- making non-Git artifacts authoritative before external backup is
  restore-tested.

## Decisions

### Exact Atenea workload, not a general remote shell

The worker adds a versioned `project-codex-v1` workload only after resolving
the persisted workspace identity to an allowlisted project record. The request
contains operator text and immutable identities, but no command, path, remote,
socket, endpoint, environment or Docker resource. The worker derives the
runtime and Codex entrypoint from the reviewed manifest.

Alternative considered: expose the administrative `codex-work` tmux helper to
the coordinator. Rejected because administrative continuity is not a managed
AgentRun and the helper has broader operator authority.

### Per-project admission remains default-disabled

The global remote-routing switch and an exact project allowlist are separate.
Only newly opened disposable acceptance sessions may select Atenea. Existing
sessions retain their target, other projects remain local/disabled, and
production configuration is not changed.

### Codex authentication stays outside requests and evidence

The workload consumes only a named, pre-provisioned worker-side authentication
boundary. No `auth.json`, token, cookie, environment dump or copied Codex home
is read by orchestration or retained in evidence. If the exact execution
identity is not already authenticated, acceptance blocks for explicit
operator provisioning rather than borrowing another identity.

### GitHub remains canonical

Every workspace derives from the pinned GitHub repository, branch and commit.
The acceptance prompt makes a deterministic documentation-only change on a
WorkSession branch, then normal publish/sync/close proves delivery invariants.
No production branch is force-updated and no dirty source is overwritten.

### Synthetic state only

The runtime initializes empty PostgreSQL through committed migrations and
declared synthetic fixtures. Attachments and preview evidence are acceptance
records, not authoritative project storage. External backup remains a gate
before any retained real-project database or non-Git artifact becomes
authoritative.

### Rollback is project-scoped

Rollback first disables new Atenea selection, then reconciles or cancels only
the persisted dispatch/workspace identity. Exact runtime/preview/database
resources are stopped and cleaned after evidence is sealed. Mirror, Git,
delivery and sanitized evidence remain; unrelated projects and production do
not change.

## Risks / Trade-offs

- [Real prompts can attempt unsafe actions] → keep workspace sandboxing,
  fixed manifest operations and no daemon/host authority.
- [Authentication could tempt home-directory copying] → require a named
  pre-provisioned execution identity and fail closed if absent.
- [Publishing mutates GitHub] → use a deterministic WorkSession branch and
  draft delivery, verify exact head/base, and clean only the accepted test
  branch after closure if the contract calls for it.
- [Disposable state may diverge from production] → this proves platform
  ownership, not production-data compatibility; production stays unreachable.
- [A project-specific exception could become a wildcard] → require exact
  repository/project/manifest hashes and negative tests for all other IDs.

## Migration Plan

1. Seal entry evidence and exact canonical/project decisions.
2. Add default-disabled real-project protocol/state/runner tests.
3. Add the matching Atenea request contract and disposable-control tests.
4. Install disabled, prove denial, then enable only the exact acceptance
   identity.
5. Execute prompt, build/tests, runtime, preview, visual and continuity gates.
6. Publish/sync/close, disable, repeat rollback, clean exact resources and
   archive before Beautips begins.

## Open Questions

None may be answered implicitly. If a dedicated execution identity is not
authenticated without reading/copying forbidden files, the change stops at
that gate and records the required operator action.
