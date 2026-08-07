## Context

Read-only inspection on 2026-08-03 established the following current state:

- canonical Atenea is clean on `main` at
  `615e539d1f2622a4ac2568ba7697b876d49ae33e`;
- WorkSession 16 is closed in the control plane but remote session
  `7151dce0-69ab-4614-86e4-f93f1af825e4` still owns the active allocation
  `af69156b9a6935cb11c96e0b7bdd73b950ec97959281a97b870bdad0c691a80f`,
  `slot2`, `heavy1` and the sole Atenea worker registration;
- WorkSession 17 is open at the same canonical commit and remote session
  `18c00753-6080-42f7-ac05-18c47b236cac`; its clean workspace record exists at
  SHA-256
  `97b41b63e425eb483175b96bce875ac3190300cedb089b176aa2fdaedd515cbb`,
  while its allocation and admission are absent;
- AgentRun 96 is failed before dispatch, has no remote execution identity and
  retains one operator attachment; its prompt and attachment content were not
  read;
- AX42's worker is active and healthy. It attempted the same workspace
  activation 81 times, each rejected because the requested fixed slot was
  already owned;
- production, preview and Beautips remain up.

The code explains both defects. `WorkSessionService.closeSession()` performs
repository reconciliation and then writes `CLOSED`, but calls no worker close
operation. The worker offers `/v1/project-workspaces/ensure` but no matching
release operation. Its error responses already contain bounded JSON
`error`/`message` fields; `RemoteWorkerClient.exchange()` discards them for all
non-2xx responses. `RemoteAgentRunCoordinator.observe()` then sends every
remote exception through the unavailability reconciliation loop.

## Goals / Non-Goals

**Goals:**

- close the remote ownership lifecycle through Atenea itself;
- distinguish transport failure, transient capacity, deterministic admission
  rejection and ambiguous ownership without exposing raw worker output;
- make close and legacy reconciliation idempotent across lost responses,
  backend restart and worker restart;
- remove only exact-owned ephemeral resources and active capacity while
  retaining all declared source, delivery, conversation and evidence state;
- give web and Android operators a truthful primary next action;
- repair the current closed-owner residue through the new mediated path and
  prove the next session can become ready without executing its prompt.

**Non-Goals:**

- automatically retrying AgentRun 96 or any user prompt;
- changing Codex model/profile, attachment semantics, preview policy,
  development-database retention or production deployment policy;
- enabling Beautips or another project for this lifecycle successor;
- deleting worktrees, Git refs, conversation turns, attachments, logs,
  artifacts, retained database volumes or backup snapshots;
- accepting caller-supplied commands, paths, slots, ports, services, endpoints,
  labels, resource names or credentials;
- adopting, repairing, deleting or rebuilding foreign or ambiguous state.

## Decisions

### Keep transport and protocol failures distinct

The worker error response becomes a strict, bounded schema with stable
`code`, `category`, `retryable` and `nextAction` fields. Optional blocker
identity is admitted only as a canonical WorkSession UUID over the
authenticated worker-to-control-plane channel and is never copied directly to
operator text. Atenea maps it to a local session only after worker, project and
workspace ownership all match.

I/O failure, request timeout, interruption and compatible 5xx responses remain
transport unavailability and use the existing finite reconciliation window.
An authenticated 4xx ownership, schema, policy or source rejection is terminal
for that admission attempt and is persisted immediately. Transient capacity
owned by a known non-closed session remains visibly queued with bounded
backoff. Capacity owned by an exact known `CLOSED` session becomes
`CLOSED_SESSION_OWNS_CAPACITY` with `RECONCILE_REMOTE_CLOSE` as the next action.
Unknown, malformed, oversized or incompatible error bodies become a generic
safe protocol failure; raw response bodies and mediator stderr are never
stored or shown.

### Add one additive V63 lifecycle projection

V63 adds safe remote-close state to `work_session` and a stable failure code to
`agent_run`. Remote close states are:

- `NOT_REQUIRED` for local sessions;
- `NOT_STARTED` for open remote sessions;
- `REQUESTED`, `RECONCILING`, `BLOCKED` and `RELEASED` for the durable close
  operation;
- `UNVERIFIED_LEGACY` for remote sessions already closed before V63.

The session persists one immutable operation UUID, monotonic revision, safe
error code, optional receipt SHA-256 and update timestamps. V63 also extends
the existing next-action constraint with `RECONCILE_REMOTE_CLOSE` and
`CONTACT_PLATFORM_ADMINISTRATOR`. It does not infer that any historical worker
state is clean.

The migration is expand-only. Before production sees V63, a current backup is
restored into an isolated PostgreSQL fixture, V63 is applied twice through the
normal Flyway lifecycle, and an exact rollback-compatible application image is
proved able to read the expanded schema with all new gates disabled.

### Use one closed worker release schema

`POST /v1/project-workspaces/release` accepts only:

- immutable operation UUID and idempotency key;
- persisted session UUID and workspace identity;
- accepted project, repository, branch, commit, manifest and workspace-branch
  identities.

It accepts no resource locator or operational authority. The worker validates
the static project route and exact persisted registry/workspace state, proves
that its durable execution store has no non-terminal run for the session, and
invokes only the reviewed project finalizer. The response is a closed
`project-workspace-release-v1` receipt containing exact ownership identity,
monotonic revision, safe removed/released/retained projections, an ownership
fingerprint, a receipt SHA-256 and `valuesExposed=false`.

Reusing an operation UUID with different input fails unchanged. Repeating
identical input after a lost response returns the same receipt without another
mutation.

### Serialize ensure and release under one lifecycle lock

Workspace ensure and release share a persistent finite-timeout lifecycle lock.
This prevents a first turn from activating while close releases the same
session and prevents two sessions from interleaving registration/admission
changes. Lock timeout is actionable and mutates nothing.

The finalizer performs a complete preflight before its first mutation. It
validates every present session-derived runtime, preview, broker/proxy,
listener, image-materialization and browser-process candidate. An unlabelled,
partial, foreign, production-like or ambiguous candidate rejects the whole
operation unchanged.

### Make release monotonic and crash-resumable

After preflight, a private session-owned journal records the immutable request
fingerprint and advances monotonically through:

1. `PREPARED`;
2. `EPHEMERAL_RELEASED`;
3. `UNREGISTERED`;
4. `ADMISSION_RELEASED`;
5. `ALLOCATION_RETIRED`;
6. `RELEASED`.

Each continuation accepts only the exact expected state for its current stage.
A process or host interruption therefore resumes the same operation rather
than rolling ownership backward or starting a replacement. A changed or
unexplained partial state blocks without further mutation.

Exact ephemeral runtime containers, networks, session images, preview
listeners/proxies, execution brokers and temporary Playwright/Chromium or
materialized-image processes are stopped and removed through existing reviewed
boundaries or a narrowly versioned successor. The project registration is then
removed, heavy admission is released before normal admission, and the active
allocation is renamed on the same filesystem to
`runtime-allocation-v1.retired.json` with bytes, inode, owner, group, mode,
size and mtime preserved. Filesystem-managed atime/ctime may advance only from
required reads and the intrinsic namespace change.

The workspace record, worktree, branch and Git objects, turns, AgentRuns,
attachments, logs, artifacts, release journal/receipt, backup evidence and any
volume explicitly retained by database policy are not deletion targets.

### Close locally only after the remote receipt

Normal close retains the existing ordering for delivery and Git reconciliation.
Only after those checks pass does Atenea persist `CLOSING` plus the immutable
remote-close operation and call the worker. A validated `RELEASED` receipt is
persisted before the session becomes `CLOSED`.

If Atenea stops after worker release but before the final database write,
startup reconciliation repeats the same operation and consumes the same
receipt. If the worker is unreachable, the session remains `CLOSING` and
`RECONCILING`; it is never reported closed. A deterministic ownership problem
leaves it `CLOSING/BLOCKED` with a safe reason and exact next action.

### Reconcile historical closed ownership only by confirmed administration

V63 marks old remote closures `UNVERIFIED_LEGACY`. Startup does not scan them,
release them or infer ownership. A `PLATFORM_ADMINISTRATOR` can request one
fixed `RECONCILE_REMOTE_CLOSE` action for a selected closed session, with an
explicit, single-use, finite confirmation bound to session, worker, project and
the read-only ownership fingerprint.

The control plane requires `CLOSED`, zero non-terminal AgentRuns, unchanged
delivery/Git identity and a matching remote ownership diagnosis. The worker
then uses the same release operation. Success changes only the remote-close
projection to `RELEASED`; `closedAt`, turns, runs, branch and artifacts remain
unchanged. Foreign, partial or ambiguous ownership yields
`CONTACT_PLATFORM_ADMINISTRATOR` and never invokes cleanup.

The current WorkSession 16 repair follows this path. WorkSession 17 and failed
AgentRun 96 are not altered. After release, an idempotent no-run ensure may
prove WorkSession 17 ready; the operator must separately choose whether to
retry the preserved prompt.

AgentRun 96 predates the V63 typed capacity failure fields, so its immutable
null failure projection is not rewritten. While the Atenea-only reconciliation
gate is enabled, the shared read model may instead consider only the immediate
older `CLOSED` WorkSession in the same canonical project. It must prove the
current run is remote, terminal and pre-dispatch, prove both sessions carry the
exact canonical Atenea/AX42 identity, and obtain a read-only diagnosis for that
specific predecessor before projecting `CLOSED_OWNER_BLOCKS_CAPACITY`. A
missing, partial, foreign or ambiguous diagnosis disables retry and requires
administrative review; it never searches for a different owner.

The diagnosis endpoint accepts no operator-chosen command, path, slot, port,
service, endpoint, label, credential or resource. The authenticated worker
mediator reads only fixed Atenea registry, workspace, allocation and admission
roots, validates their complete exact identity plus Git origin/HEAD, performs
no mutation and returns only sealed fingerprints with `valuesExposed=false`.
The legacy plan repeats that diagnosis before persisting its finite
confirmation. Transport, protocol and deterministic ownership failures retain
distinct response categories.

After an immutable operation is already `BLOCKED`, a replacement plan also
requires `POST /v1/project-workspaces/release-preflight` to accept the complete
server-derived release request, including the original operation identity. The
worker holds the same lifecycle lock, invokes the same fixed-root mediator and
validates the same Git, registry, workspace, admission, allocation and
ephemeral projection used by release, but stops before journal creation or any
resource mutation. Its closed response contains only the request, ownership
and allocation fingerprints with `valuesExposed=false`. A deterministic
failure prevents creation of the fresh human-confirmation plan; transport and
protocol failures keep their distinct status and never enter a worker-
unavailable retry window for a deterministic 4xx.

An exact worker release-preflight rejection is not retried as transport and
does not make the first single-use plan reusable. Atenea persists the original
operation as `BLOCKED` without a receipt. Recovery requires a new read-only
diagnosis, a fresh finite plan and another explicit platform-administrator
confirmation bound to the unchanged ownership fingerprint. That confirmation
may advance only the same operation through `RECONCILING`; startup never
retries `BLOCKED` on its own, while `REQUESTED` and `RECONCILING` remain
restart-recoverable. Web and Android expose the recovery action only when the
complete persisted predicate is exact and discard every consumed or newly
blocked plan until explicit refresh.

### Put the real state and one next action first

Web and Android consume the same read model. The first viewport shows one of:

- `Cerrando · liberando recursos remotos` with wait/reconcile;
- `Cierre bloqueado · ownership no verificable` with administrator contact;
- `Bloqueada por una sesión cerrada` with `Reconciliar cierre` for an
  authorized administrator;
- `Capacidad liberada` with `Reintentar tarea` only after the blocker is gone.

Generic retry is hidden or disabled while a deterministic blocker remains.
The screen does not expose host paths, UUIDs, raw HTTP text, labels or command
output. Web acceptance uses real rendered Playwright checks at `1440x900` and
`390x844`; Android receives equivalent state/action tests and a separately
installed canary build before acceptance.

### Keep rollout default-off and Atenea-only

Application and worker release/reconciliation gates default false. Source,
migration, worker and UI validation complete before any production mutation.
Task 6.8 is an absolute gate: High must stop until a separate explicit
authorization covers the V63 migration, backend/web rollout, AX42 worker
successor, exact rollback exercise and canonical Atenea activation.

After authorization, capability is enabled globally only as a prerequisite and
then allowlisted solely for canonical Atenea. Beautips and all other projects
remain byte-identical and ineligible. The current legacy repair requires a
second explicit confirmation in the product and must be visually observed by
the operator; its result cannot be simulated.

## Failure Matrix

| Observation | Persisted run/session state | Primary action | Automatic mutation |
|---|---|---|---|
| I/O, timeout, compatible 5xx | `RECONCILING` | Wait / reconcile | Poll same identity only |
| Capacity held by exact open session | `QUEUED` | Wait / cancel | Bounded backoff only |
| Capacity held by exact closed session | `FAILED` + `CLOSED_SESSION_OWNS_CAPACITY` | Reconcile closed session | None |
| Deterministic request/source/policy rejection | `FAILED` + stable safe code | Correct declared state | None |
| Foreign, partial or ambiguous ownership | `FAILED`/`BLOCKED` | Platform administrator | None |
| Release response lost after success | `CLOSING/RECONCILING` | Reconcile same close | Return same receipt |
| Valid release receipt | `CLOSED/RELEASED` | None | No repeat mutation |

## Rollback

Rollback disables new close-release and legacy-reconciliation operations before
changing application or worker components. It waits for or exactly reconciles
any in-progress close operation; it never changes an open session's affinity.
The V63 schema and lifecycle history remain in place and are not down-migrated.

The prior backend image must be the separately built V63-compatible image. The
prior worker program and mediator are restored only from their checksum-sealed
predecessors after no release is in progress. A successfully released
registration/admission or retired allocation is monotonic and is not recreated
by rollback. GitHub history, WorkSessions, turns, AgentRuns, attachments,
worktrees, logs, artifacts, backups and policy-retained volumes remain intact.

If any post-rollback gate fails, leave routing/release disabled, retain the
expanded history and exact evidence, and stop. Do not adopt or reconstruct
worker ownership.

## Migration Plan

1. Seal the incident and infrastructure baseline without reading prompt or
   attachment content.
2. Add V63 and rollback-compatible application support with all gates disabled.
3. Implement and test typed worker error preservation/classification.
4. Implement and adversarially test the exact worker release protocol,
   lifecycle lock, staged journal, installer and rollback.
5. Integrate durable remote close and confirmed legacy reconciliation in the
   backend.
6. Implement the shared web/Android state-first operator experience and real
   rendered validation.
7. Run complete backend, web, Android, worker, migration, security and
   adversarial suites; review the exact release independently.
8. Stop for separate production rollout authorization.
9. After authorization, backup/restore, deploy disabled, verify, enable only
   Atenea and run isolated/synthetic acceptance.
10. Obtain the operator's in-product confirmation, reconcile only WorkSession
    16 and prove WorkSession 17 can become ready without dispatching a prompt.
11. Execute disable-first exact rollback and re-enable acceptance, seal final
    evidence, update the ledger and archive.

## Open Questions

None. Prompt retry remains an explicit operator decision outside this change.
