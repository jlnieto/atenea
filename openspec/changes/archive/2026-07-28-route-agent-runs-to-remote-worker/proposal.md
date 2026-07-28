## Why

The AX42 runtime contract and Atenea development relocation are archived, but
Atenea still executes every managed AgentRun in the legacy local executor.
Before any real project can become authoritative on AX42, the control plane
needs a durable, restart-safe and reversible remote-dispatch path whose
ownership and capacity rules can be proved with synthetic work only.

## What Changes

- Add an authenticated worker registry with protocol, heartbeat, capability and
  bounded-capacity state.
- Persist the execution target and immutable workspace identity on each newly
  opened WorkSession; existing sessions remain pinned to the local executor.
- Persist a unique AgentRun dispatch identity, selected worker, workload class,
  lease state, lifecycle timestamps and remote execution identity.
- Add a private versioned worker protocol for idempotent dispatch, status,
  progress, lease renewal and cancellation.
- Add a four-normal-slot queue and two-heavy-operation permits without granting
  arbitrary shell or project execution.
- Reconcile non-terminal remote runs after backend restart or network
  interruption without duplicating work or terminal delivery.
- Add a default-disabled feature switch restricted to an allowlisted synthetic
  project and affecting only newly opened WorkSessions.
- Exercise duplicate dispatch, restart, partition, cancellation, capacity,
  multi-turn continuity and an executed rollback while production, Beautips and
  all unrelated worker resources remain unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `remote-worker-control`: Makes dispatch identity, target affinity, leases,
  lifecycle delivery, capacity admission, cancellation and reconciliation
  precise enough to implement and test.
- `remote-work-continuity`: Defines exactly-once visible terminal delivery and
  multi-turn continuity across a backend restart for synthetic remote runs.

## Impact

- Atenea repository: additive Flyway migration, persistence/API model, remote
  worker client, scheduler/reconciler, feature configuration and tests.
- Programme repository: versioned synthetic worker service, installation and
  verification automation, protocol fixtures, operator/rollback documentation
  and retained acceptance evidence.
- AX42: one private root-owned worker service and durable synthetic execution
  state; no real project runtime is activated.
- Production: no deployment, endpoint, database mutation, secret rotation,
  routing activation or project-authority change. The production feature switch
  remains disabled and the existing executor remains available.
