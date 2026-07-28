## Context

Phases 1–3 established the secure AX42 host, the mediated project-runtime
contract and a reproducible Atenea development worktree. They deliberately did
not route managed AgentRuns. Atenea currently creates local runs directly in
`SessionTurnService`; a backend restart applies the existing local stale-run
policy. There is no durable remote dispatch identity, target affinity, worker
lease or remote reconciliation state.

The phase-entry gate retained and restored a PostgreSQL 16 custom-format backup
from the current Flyway V45 production schema in an isolated, network-disabled
fixture. AX42 identity, protocol source hashes, four rootless slots, the
four-normal/two-heavy capacity envelope, RAID, firewall and non-impact
sentinels were also accepted. Production routing is zero and the legacy
executor remains usable.

This phase is synthetic. It must prove the control path before any real project,
including Atenea or Beautips, becomes authoritative on the worker.

## Goals / Non-Goals

**Goals:**

- Pin each WorkSession to one immutable local or remote execution target at
  creation.
- Make remote dispatch and terminal acceptance idempotent and durable.
- Bound normal and heavy scheduling independently.
- Preserve a live worker execution across an Atenea backend restart and expose
  a bounded reconciling state during a partition.
- Cancel only the exact owned execution and leave unrelated work untouched.
- Provide an executed, non-destructive rollback to local routing for new
  sessions.

**Non-Goals:**

- Route a real project, migrate an existing WorkSession or replace its
  workspace.
- Expose arbitrary commands, a Docker socket, runtime-manager authority or a
  public worker endpoint.
- Implement attachment upload/retention, private previews, localhost
  compatibility or external artifact promotion.
- Change production deployment, PostgreSQL contents, endpoints, credentials,
  notification policy, Beautips or unrelated AX42 slots.
- Remove the legacy local executor or perform a destructive schema down
  migration.

## Decisions

### 1. Pin execution target when a WorkSession is opened

`work_session` gains an execution-target kind, selected worker and immutable
workspace identity. Existing rows and all sessions created while the feature is
disabled are `LOCAL`. Only a newly opened session for the exact synthetic
allowlist may be `REMOTE`.

Changing the feature switch affects later sessions only. An open session is
never silently migrated, reassigned or given a replacement workspace.

### 2. Use an additive V46 expand/contract migration

V46 adds worker registration, WorkSession affinity and AgentRun remote lifecycle
columns with safe defaults. Existing AgentRuns keep their local semantics.
AgentRun status expands to `QUEUED`, `STARTING`, `RUNNING`, `CANCELLING`,
`RECONCILING`, `SUCCEEDED`, `FAILED` and `CANCELLED`; the per-session unique
constraint covers all non-terminal states.

Rollback disables new remote selection and retains the additive columns and
records for audit/reconciliation. Contract/removal is a later separately
approved migration after no remote record depends on them.

### 3. Use a private, versioned and deliberately narrow protocol

The worker exposes protocol `agent-run-worker/v1` only on its Tailscale address.
Requests require a bearer value read by each service from a root-owned file;
the value is never stored in Git, commands or evidence. The firewall permits
only the Atenea control-plane tailnet identity.

The accepted operations are health/capacity, idempotent create, execution
status, lease renewal and cancellation. Phase 4 accepts only the fixed
`synthetic-routing-v1` workload and bounded declarative timing/progress inputs.
There is no command, shell, repository, mount, browser or container field.

### 4. Make dispatch identity the idempotency authority

Atenea creates the AgentRun and a UUID dispatch identity in one transaction
before contacting AX42. The worker durably indexes one execution by that
identity. Repeating an identical request returns the same execution; conflicting
payload reuse fails closed. Atenea accepts a terminal result once using the
remote execution identity and terminal revision.

Network timeouts therefore cause observation/retry, not a second execution.

### 5. Separate run lease from scheduling permits

Each remote AgentRun stores lease owner, generation, expiry and last heartbeat.
Renewal is compare-and-set on the immutable dispatch/execution pair. A lease
allows Atenea to observe one execution; it does not authorize workspace
migration or a second worker.

The AX42 service owns four normal permits and two nested heavy permits. A fifth
normal execution remains `QUEUED`; when two admitted runs hold heavy permits, a
third heavy execution also remains queued. Queue order is durable FIFO with a
stable tie-breaker.

### 6. Reconcile by persisted target after restart or partition

At backend startup, local non-terminal rows follow the existing local
reconciliation policy. Remote non-terminal rows become `RECONCILING` and are
queried by their persisted worker and execution identities. A live matching
execution resumes observation; a terminal matching execution is accepted
idempotently; an unavailable worker remains reconciling until the finite policy
expires and then fails with an actionable reason.

The reconciler never dispatches a new identity, invents ownership, selects a
different worker or turns an unavailable remote run into local work.

### 7. Cancellation is exact and terminal delivery is monotonic

Cancellation addresses the selected worker, dispatch identity and execution
identity. The worker moves through `CANCELLING`, terminates only the matching
synthetic execution and persists `CANCELLED`. Repeated cancellation returns the
same state. Other queued or running executions and all slot runtimes are
fingerprinted before and after.

Lifecycle revisions are monotonic. Atenea ignores stale revisions and never
replaces one terminal outcome with another.

### 8. Keep activation synthetic and production-default-off

Configuration defaults remote routing to false. Enabling it additionally
requires the exact synthetic project allowlist and compatible healthy worker.
If either condition is absent, new sessions remain local with an observable
selection reason. No production configuration or deployed container is changed
in this phase.

Acceptance uses a disposable Atenea test instance and database derived from
committed migrations. It never connects to or dumps production data.

### 9. Resolve only decisions that belong to this phase

- Migration backup/rollback: closed by the retained V45 dump, isolated restore
  proof and expand/contract V46 rollback.
- Lease retention: non-terminal leases and lifecycle events remain in the
  control-plane record; expired leases are not reused. Row deletion is outside
  this phase.
- Attachments and previews: no records are created here; their retention is a
  Phase 5/6 entry decision and does not block synthetic routing.
- Beautips: remains an unrelated workload and is explicitly a non-impact
  sentinel, not the first authoritative routed project.
- Localhost requirements: no preview is exposed here; decide per project before
  Phase 6.
- Artifact promotion and external-backup provider: evidence is immutable and
  checksummed locally; promotion authority and external retention remain later
  programme gates.
- Second Tailscale administrator: current recovery plus public key-only
  break-glass remains accepted; expansion/removal of that path requires the
  separate organisational decision.

## Risks / Trade-offs

- [A retry starts duplicate work] → immutable dispatch identity, durable worker
  index, conflicting-payload rejection and duplicate-dispatch acceptance test.
- [Restart destroys visibility] → persist target/lease before dispatch and
  reconcile remote rows instead of applying the local startup-failure rule.
- [Partition creates false terminal results] → bounded `RECONCILING`, monotonic
  revisions and status lookup by exact execution identity.
- [Synthetic protocol grows into remote shell] → fixed workload schema and
  fail-closed rejection of unknown fields/types.
- [Capacity accounting diverges after service restart] → rebuild permits from
  durable non-terminal executions and prove queue bounds after restart.
- [Feature rollback strands work] → switch changes only new sessions; active
  remote runs remain pinned and must complete or be explicitly cancelled.

## Migration Plan

1. Retain entry evidence, the restorable V45 backup and exact Git/host
   fingerprints.
2. Commit the OpenSpec contract while routing remains disabled.
3. Add V46 and persistence/API tests; restore the pre-migration backup in an
   isolated fixture and apply V46.
4. Add and test the narrow worker service, then install it privately on AX42.
5. Add the default-off Atenea client, target selection, dispatcher and
   reconciler.
6. Run unit/integration/regression suites, including unchanged local
   reconciliation.
7. Enable routing only in a disposable synthetic environment and prove
   duplicate dispatch, capacity, cancellation, restart and partition behavior.
8. Run a multi-turn synthetic session across one backend restart.
9. Disable the switch, explicitly reconcile/cancel remaining synthetic runs,
   remove only exact synthetic fixtures and verify non-impact.
10. Observe, checksum evidence, validate OpenSpec and archive before any real
    project is considered.

## Rollback

Set remote selection false so only future sessions use the legacy executor.
Do not change open-session affinity. Query every non-terminal synthetic remote
record by exact ownership and either let it reconcile to terminal or explicitly
cancel it. Stop/disable the Phase 4 worker endpoint only after no remote run is
non-terminal. Preserve V46 data, worker logs and evidence; do not down-migrate.

The legacy executor, production routing and all pre-existing local WorkSessions
remain usable throughout.
