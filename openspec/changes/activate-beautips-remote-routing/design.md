## Context

The archived Beautips onboarding proved the complete project protocol with an
isolated control plane and then deliberately removed its runtime, registration
and admission. The installed AX42 Beautips route is therefore
`selectionEnabled=false`, `executionEnabled=false`, `workspaceCount=0`.

Production Atenea contains the accepted selector, persisted remote affinity and
project payload code, but its remote flags and credential are absent. More
importantly, opening a selected WorkSession persists a new remote UUID while
no component provisions the corresponding AX42 mirror, worktree, allocation
or exact Beautips registration. Dispatch correctly rejects that incomplete
ownership.

AX42 also runs an unrelated manual Beautips stack in slot 1. Its workspace,
containers, network, volumes, listener and secrets remain foreign. Independent
B2 backup acceptance now permits a separate project-routing change to retain
managed non-Git ownership state.

## Goals / Non-Goals

**Goals:**

- make one newly opened production Beautips WorkSession usable remotely without
  an operator SSH step between opening the session and sending its first turn;
- persist and validate exact workspace ownership before dispatch;
- keep provisioning, retry, restart reconciliation and rollback idempotent;
- enable only exact Beautips while preserving all other routing defaults;
- prove one real user-visible turn, continuity, private preview and reversible
  activation from Atenea.

**Non-Goals:**

- adopting, migrating, backing up or changing the manual slot 1 Beautips stack;
- importing existing Beautips data or enabling WhatsApp/external messaging;
- enabling Atenea, another real project, wildcard routing, public preview or
  production deployment;
- changing the canonical Beautips repository, accepted commit or runtime
  manifest during activation.

## Decisions

### Provision lazily from a durable AgentRun

Atenea will persist the selected WorkSession and queued AgentRun before asking
AX42 to ensure the workspace. The coordinator will call a separate
authenticated workspace endpoint immediately before the first project
dispatch and repeat that call safely on retry or restart.

Provisioning during `openSession` was rejected because it would couple an
external mutation to the database transaction before the WorkSession identity
is durably committed. Manual provisioning was rejected because it would not
support normal laptop/mobile operation.

### Use one closed Beautips activation mediator

A root-owned mediator will accept only the exact symbolic action, canonical
remote WorkSession UUID and persisted workspace branch. It derives project,
repository, base branch, accepted commit, manifest, admission class and slots
2–4 from versioned configuration. It invokes the existing workspace,
allocation, admission and Beautips registration tools and returns only
sanitized ownership metadata.

The HTTP worker never accepts a caller command, path, remote, slot, port,
environment or credential. Its service identity may invoke only this exact
mediator through a dedicated sudoers rule.

### Treat provisioning as an idempotent state machine

The operation advances through exact persisted ownership:

1. ensure mirror and worktree;
2. acquire one normal admission and allocation;
3. enable Beautips selection;
4. register the exact workspace;
5. enable execution;
6. return the same ready identity on repetition.

Partial state is not overwritten or inferred. A mismatch fails closed and
requires reconciliation. Compensating rollback removes only state whose
immutable identities and complete labels match the requested WorkSession.

### Keep project activation separate at both ends

Production Atenea will set the global worker gate and exact Beautips gate only.
`project-codex-enabled` and the synthetic allowlist remain disabled/empty. AX42
will enable only the dedicated Beautips config with exactly one registered
workspace. Existing open sessions retain their persisted target.

### Transfer the existing worker credential without exposing it

The already-installed worker credential will be copied directly between the
two authenticated hosts into a root-owned mode-0600 Atenea secret file. No
credential value, hash, command argument or intermediate local file enters
evidence. Deployment configuration references only the file path.

## Risks / Trade-offs

- **Provisioning succeeds but dispatch is interrupted** → the persisted queued
  run is reconciled and repeats the exact ensure operation before redispatch.
- **A partial or foreign workspace already exists** → the mediator rejects it
  without cleanup and routing remains disabled for that run.
- **Production backend rollout interrupts local work** → capture non-terminal
  runs first, require zero, restart only the backend, and verify persisted
  reconciliation before activation.
- **The accepted Beautips commit no longer matches GitHub main** → activation
  stops; no commit is silently advanced during this change.
- **One-workspace registration limits concurrent Beautips sessions** → Atenea's
  existing one-open-session-per-project invariant matches this first
  production activation; multi-session project routing remains out of scope.
- **Rollback after useful work could destroy state** → disable prevents new
  selection/dispatch first; runtime cleanup is never automatic and exact Git,
  logs, attachments and evidence are retained.

## Migration Plan

1. Capture clean Git, backup, routing, worker, slot, service and production
   fingerprints.
2. Implement and double-test the closed provisioning mediator and worker API.
3. Implement Atenea's pre-dispatch ensure call and focused/full tests.
4. Install AX42 changes disabled and deploy Atenea code with all gates false.
5. Copy the credential directly host-to-host, configure the private endpoint
   and restart only the production backend.
6. Enable the global and Beautips selector, open one new WorkSession, and let
   its first turn provision and dispatch.
7. Verify continuity, worktree, private preview, non-impact and backup health.
8. Disable selection, prove rollback/reconciliation, then re-enable the
   accepted route with its exact persisted workspace.
9. Seal evidence, update the ledger, archive and publish.

Rollback first disables the production Beautips selector and AX42 execution.
It never retargets an open session. Exact runtime cleanup requires separate
complete ownership proof; the canonical mirror, WorkSession Git, logs,
attachments, delivery records, backup and evidence remain.

## Open Questions

None. Multi-session Beautips capacity and migration of the manual runtime stay
deferred to separate changes.
