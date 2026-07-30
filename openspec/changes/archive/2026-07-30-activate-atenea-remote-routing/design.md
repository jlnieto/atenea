## Context

The archived Atenea onboarding proved real Codex execution, build, runtime,
preview, delivery and cleanup from a manually prepared disposable workspace.
Production Atenea now has the generic project selector and exact workload
identity, but its first run cannot create the matching AX42 ownership state.
Beautips subsequently established the production pattern: persist the
WorkSession and AgentRun, then idempotently ensure the exact workspace before
dispatch.

## Goals / Non-Goals

**Goals:**

- make a newly opened production Atenea WorkSession usable without SSH;
- derive all workspace state from persisted WorkSession identity;
- preserve exact project separation, retry safety and retained Git state;
- prove a real response without changing application files;
- keep production, Beautips and unrelated slots unchanged.

**Non-Goals:**

- deploying changes made by the new WorkSession;
- starting its development runtime or preview automatically;
- migrating production PostgreSQL, secrets or deploy authority to AX42;
- enabling wildcard routing or another project.

## Decisions

### Use a separate closed Atenea activation mediator

The worker selects the mediator only after an exact Atenea identity match. The
mediator accepts only `ensure`, a canonical remote session UUID and its exact
`atenea/session-<uuid>` branch. Repository, base branch, accepted commit,
manifest, slot and helper paths are derived from reviewed configuration.

### Persist first and provision before first dispatch

Atenea creates the WorkSession and queued AgentRun before calling AX42. The
coordinator repeats the ensure operation safely until exact ownership is
ready, then dispatches. Existing remote executions are only observed and are
never provisioned or redispatched again.

### Pin the current canonical source

The accepted branch now resolves to
`d5ea39e7b575b63c6fff3a66a0400c5af5e9ff2b`. The runtime manifest remains
byte-identical with SHA-256
`3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3`.
No unpushed or control-plane-only state is admitted.

### Reserve slot 2 and heavy admission

Atenea's manifest declares a heavy workload, so activation acquires exact
normal ownership of free slot 2 and one heavy permit before allocating the
runtime record. It does not start containers, networks, listeners or preview.
Any ownership conflict fails closed.

### Keep activation independently reversible

Production selection and AX42 execution can be disabled for Atenea without
moving an open WorkSession or affecting Beautips. Cleanup remains a separate
ownership-proven operation; mirror, worktree, conversation, logs and artifacts
are retained.

## Risks / Trade-offs

- A partial provision may retain admission until retry or reconciliation;
  every step is idempotent and foreign state is never replaced.
- The first run takes longer because it creates the mirror/worktree before
  Codex starts.
- One open session per project and one registered Atenea workspace remain the
  current production limits.

## Migration Plan

1. Capture Git, routing, worker, slot, RAID and service fingerprints.
2. Run worker and Atenea focused tests with both routes disabled.
3. Install the Atenea mediator and reviewed worker version.
4. Deploy Atenea with its project gate disabled.
5. Enable only the exact Atenea worker and control-plane gates.
6. Open one production-control-plane Atenea WorkSession and send a read-only
   validation turn.
7. Verify response, continuity, Git isolation, Beautips non-impact and health.
8. Disable and re-enable Atenea once to prove project-scoped rollback.
9. Seal evidence, validate OpenSpec, commit and push both branches.

## Open Questions

None.
