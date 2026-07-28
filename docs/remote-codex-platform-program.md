# Remote Codex Platform Programme

## Authority and status

This document is the durable programme ledger for moving Atenea development execution to a dedicated remote worker.

- Programme: `remote-codex-platform`
- Foundation change: `establish-remote-codex-platform-program`
- Current phase: `relocate-atenea-development-to-ax42` (implementation `12/27`)
- Runtime routing: unchanged; Atenea production is not connected to the AX42
- Production/control plane: current Atenea VPS
- Development/execution plane: Hetzner AX42 (manual pilot only)
- Canonical source: GitHub
- Last evidence refresh: 2026-07-27

The normative requirements live in OpenSpec. This ledger records phase state, decisions, evidence locations and the exact resume point. Code, tests and migrations remain authoritative for existing Atenea runtime behaviour.

## Objective

Move all repository development and Codex execution initiated through Atenea to
the AX42 without coupling work to the operator laptop. The platform must support
up to four bounded concurrent project sessions, preserve the trusted Codex
workflow, and make manual and automated browser verification available from
laptop and mobile without publishing development services. The Atenea server
remains the production and control plane, not a general development executor.

## Programme invariants

1. Atenea remains authoritative for `Project`, `WorkSession`, `SessionTurn`, `AgentRun`, delivery and operator access.
2. An open WorkSession is pinned to one execution target and one session-owned workspace.
3. No more than one AgentRun executes per WorkSession.
4. The first worker admits at most four normal sessions and at most two heavy operations by default.
5. Each session owns an isolated Git worktree and runtime namespace.
6. Codex does not receive the host Docker socket, host root filesystem or unrelated workspaces.
7. Worker API, Codex App Server and previews are private by default.
8. Client disconnection does not cancel a durably accepted run.
9. Restart recovery reconciles worker state before declaring a remote run failed.
10. Active sessions are never moved implicitly during rollout or rollback.
11. Authentication and project secrets are not copied as ordinary repository files.
12. RAID availability is complemented by external backup and restore evidence.
13. GitHub remains the canonical source for repository code; worker mirrors and worktrees are execution state, not a replacement remote.
14. Atenea production, its PostgreSQL, secrets, backups, monitoring and deploy/rollback authority remain on the Atenea server.
15. Builds, Codex, project runtimes, development databases, Playwright, previews, repositories and worktrees belong on the AX42 after their migration gates pass.
16. “Latest screenshot” and related image references resolve only inside the current WorkSession attachment set.
17. Database replacement requires explicit confirmation and is permitted only for development databases.
18. Production deployment is a separate governed workflow using a reviewed versioned artifact, restricted credentials, confirmation, health checks and rollback; normal Codex execution cannot deploy directly.

## Scope

Included:

- secure AX42 worker baseline;
- private network between Atenea, worker, laptop and mobile;
- durable worker dispatch and reconciliation;
- session worktrees and runtime manifests;
- compatible `dev` CLI;
- four-slot scheduling and resource policy;
- private previews, SSH tunnels and Playwright artifacts;
- WorkSession-scoped attachments and deterministic screenshot resolution;
- Codex instruction, skill and toolchain parity;
- development relocation and onboarding for Atenea itself;
- onboarding Yvateve, Beautips, ISC, Recambios, Fomasys and Checkpol;
- isolated development databases and confirmed development-only refresh;
- controlled production deployment from versioned artifacts;
- monitoring, backups, cleanup, capacity and rollback.

Excluded:

- moving Atenea PostgreSQL, web or mobile APIs to the worker;
- giving ordinary Codex runs production deployment or production database credentials;
- local model inference;
- Kubernetes or a general CI product;
- multiple simultaneous open WorkSessions for one project;
- automatic reconciliation of uncommitted laptop work;
- public development hosting by default.

## Current and target topology

### Current

```text
Laptop
  ├─ trusted Codex configuration
  ├─ dev + local runtimes + browser
  └─ must remain online for local work

Atenea VPS (4 vCPU / 8 GB)
  ├─ public web + Android APIs
  ├─ PostgreSQL prod/preview
  ├─ repositories
  ├─ Codex App Server prod/preview/rescue
  └─ execution coupled to control-plane lifetime

AX42
  ├─ four prepared rootless runtime slots
  ├─ administrative Codex/tmux bridge
  └─ manual Beautips runtime + tailnet-only pilot preview
```

This is the observed state on 2026-07-25, not the target boundary. In
particular, the Atenea server still hosts repositories and Codex App Server
containers, and no Atenea AgentRun is routed to the AX42. AX42 Codex is
available in a login shell as `0.145.0`; GitHub is independently authenticated;
the four slot proxies and `codex-beautips` tmux session are active. The Beautips
runtime remains healthy on worker loopback, but the previously accepted
Tailscale Serve route was absent at the 2026-07-25 refresh and is not currently
an available preview. Playwright has pilot evidence through project tooling but
is not currently exposed as a global login-shell command.

### Target

```text
Laptop / Android
        │ authenticated operator traffic
        ▼
Atenea control plane
  ├─ public web + mobile APIs
  ├─ production PostgreSQL and durable workflow state
  ├─ production secrets, backups and monitoring
  ├─ scheduling, leases and notifications
  ├─ governed deploy/health/rollback control
  └─ authenticated private worker protocol
        │ encrypted private network
        ▼
AX42 worker
  ├─ GitHub-backed mirrors + session worktrees
  ├─ bounded Codex, build and test execution
  ├─ project runtimes, development databases and caches
  ├─ private previews + Playwright
  └─ WorkSession attachments + operational telemetry
```

Atenea is also an internal development project. Its source worktree, builds,
tests, development runtime and development database move to the AX42 through a
dedicated phase. Its public service, production database, production secrets,
backup/monitoring and deploy/rollback control remain on the Atenea server.

## Ownership boundaries

| Concern | Authority | Notes |
|---|---|---|
| Projects and WorkSessions | Atenea | Logical project identity replaces assumptions that one host path is universal. |
| Conversation and AgentRun state | Atenea/PostgreSQL | Worker events are idempotently incorporated. |
| Dispatch lease and live processes | Worker, observed by Atenea | Atenea decides admission and terminal product state. |
| Git workspace | Worker per WorkSession | Canonical remotes and branches remain Git-backed. |
| Runtime manifest | Project repository | Consumed by worker and `dev`. |
| Preview route | Worker, published in Atenea | Private by default and session-scoped. |
| Attachments/browser artifacts | Worker storage, indexed by Atenea/PostgreSQL | WorkSession-scoped ordering and retention survive preview teardown. |
| Development databases | Worker per declared project/session policy | Replace/restore is confirmed and cannot target production. |
| Codex context | Versioned Atenea/project sources | The run records the effective context version. |
| Secrets | Dedicated secret boundary | Never OpenSpec, Git, ordinary logs or copied home directories. |
| Public/mobile authentication | Atenea | Existing operator contract remains. |
| Backups | External target | RAID is not the backup target. |
| Production deployment | Atenea governed operations boundary | Consumes versioned artifacts with restricted credentials, confirmation, health check and rollback. |

## Phase order

1. `bootstrap-secure-codex-worker`
2. `establish-project-runtime-contract`
3. `relocate-atenea-development-to-ax42`
4. `route-agent-runs-to-remote-worker`
5. `add-worksession-attachments`
6. `add-private-session-previews`
7. `establish-development-database-lifecycle`
8. individual project onboarding changes for Atenea, Beautips, Checkpol, Yvateve, Fomasys, ISC and Recambios
9. `add-controlled-production-deployments`
10. `harden-worker-operations`
11. `retire-legacy-atenea-executor`

Entry, evidence, rollback and archive gates are defined in `remote-codex-platform-phases.md`. No phase becomes authoritative merely because its code builds.

`relocate-atenea-development-to-ax42` is the only active OpenSpec change. Its
entry gate was reviewed against real control-plane and worker state, and its
proposal, design, specs and 27-task implementation checklist are complete and
strictly valid. Later names remain a planning queue and MUST NOT be created or
started from this phase.

## Decision log

| ID | Decision | Rationale | Status | Owner | Safe review point |
|---|---|---|---|---|---|
| D-001 | Keep Atenea as control plane and AX42 as worker. | Preserves working web/mobile/durable state and isolates resource-heavy execution. | accepted | platform owner | before any control-plane relocation proposal |
| D-002 | Introduce an authenticated worker protocol instead of pointing Atenea directly at one remote App Server. | Scheduling, leases, cancellation, workspace and preview ownership need a worker contract. | accepted | platform owner | remote routing design phase |
| D-003 | Use Tailscale initially. | Provides WireGuard data plane, device identity, NAT traversal, mobile support and policy with lower operational load. | accepted and enrolled in `codynwave.com` | platform owner | before adding another user or network path |
| D-011 | Use `info@codynwave.com` as the sole tailnet Owner initially. | Keeps Standard billing to one seat; Microsoft MFA/recovery plus tested public key-only SSH break-glass cover the initial recovery model. | accepted; second independent admin deferred | platform owner | before removing public SSH break-glass or expanding the operator team |
| D-004 | Retain `dev` as a compatibility CLI over manifests. | Preserves operator muscle memory while removing laptop-only internals. | accepted | platform owner | runtime contract phase |
| D-005 | One worktree and runtime namespace per WorkSession. | Protects branches and permits safe cross-project concurrency. | accepted | platform owner | runtime contract phase |
| D-006 | Do not expose the host Docker socket to Codex. | A mounted socket is effective host-root and defeats session isolation. | accepted | security owner | isolation spike |
| D-007 | Reconcile remote runs through leases after restart. | Backend process lifetime is not execution lifetime. | accepted | backend owner | remote routing phase |
| D-008 | Use one stable foundation plus short-lived implementation changes. | Avoids one unreviewable long-running migration change. | accepted | programme owner | after every phase archive |
| D-009 | Prefer Beautips as pilot after repository synchronization. | Checkpol is simpler at runtime but currently has 14 local uncommitted changes; Beautips is locally clean. | provisional | programme owner | onboarding gate comparison |
| D-010 | Keep localhost SSH tunnels as compatibility fallback. | Some cookies, callbacks and legacy assumptions may not accept a tailnet hostname. | accepted | runtime owner | each project onboarding |
| D-012 | Keep Atenea production/control responsibilities on the Atenea server and move Atenea development to AX42. | Separates durable public control state from builds, Codex and mutable development runtimes. | accepted | platform owner | relocation design phase |
| D-013 | Keep GitHub canonical for all source, including Atenea. | Mirrors and worktrees must be reproducible and cannot become an unreviewed source of truth. | accepted | programme owner | every onboarding gate |
| D-014 | Scope attachments and screenshot language to WorkSession. | Global folders can mix projects and make “latest” nondeterministic. | accepted | product owner | attachment phase |
| D-015 | Permit database replacement only for development databases and only after confirmation. | Prevents an execution-plane operation from reaching production data. | accepted | data owner | development database phase |
| D-016 | Separate production deployment from ordinary Codex execution. | Production requires reviewed artifacts, restricted authority, explicit confirmation, health checks and rollback. | accepted | operations owner | controlled deployment phase |
| D-017 | Keep executable reboot harnesses beneath `/tmp`, but persist synthetic WorkSession state and retained evidence in the canonical `/srv/atenea` roots. | AX42 clears `/tmp` during reboot; reconciliation must be based on real surviving state rather than static or lost files. | accepted and proven in 5.3 | runtime owner | cleanup and retention design |
| D-018 | Give the administrative Beautips PostgreSQL and Redis containers the same `unless-stopped` restart policy as the application. | The first reboot proved that dependencies with restart policy `no` leave the application unhealthy after host recovery. | accepted and proven in 5.3 | operations owner | Beautips onboarding |

## Deferred decisions and gates

| Decision | Deferral | Must be resolved before |
|---|---|---|
| Second independent tailnet administrator | The operator chose one paid seat initially. `info@codynwave.com` is Owner; Microsoft recovery and public SSH break-glass remain mandatory. | removing public SSH break-glass or expanding beyond one operator |
| External backup target and retention | Compare Hetzner Storage Box with an independent provider and existing storage. | storing authoritative non-Git artifacts or completing operational hardening |
| Final pilot | Beautips is provisional; first reconcile its local and Atenea commits. | enabling first real project run |
| Per-project localhost requirement | Discover through cookies, callbacks and browser tests. | declaring that project's private preview ready |
| Initial runtime sandbox implementation | Prototype mediated rootless/container alternatives against the no-host-socket requirement. | accepting the runtime contract phase |
| Lease, artifact and preview retention durations | Measure representative jobs. | production defaults in remote routing/preview phases |
| Atenea development data fixture and sanitization policy | Production data must not be copied implicitly to AX42. | relocating the Atenea development database |
| Versioned artifact format and promotion authority | Deployment must not build mutable source on the production host. | controlled deployment phase |

## Runtime non-impact statement

This foundation does not:

- deploy services;
- change Atenea endpoints or database schema;
- point any WorkSession at the AX42;
- relocate the current Atenea worktree, preview stack or database;
- modify current production containers;
- open or close firewall ports;
- copy repositories or credentials;
- change startup reconciliation.

Every implementation phase requires a dedicated OpenSpec change, test evidence, deployment evidence, an observation window and an executable rollback.

## Resume protocol

The secure AX42 bootstrap was accepted after more than 24 hours of clean
observation and archived as
`openspec/changes/archive/2026-07-24-bootstrap-secure-codex-worker`.

After any interruption:

1. Open this ledger and identify `Current phase`.
2. Run `openspec list` and `openspec status --change <current-change>` in the canonical Atenea worktree.
3. Confirm the production Atenea worktree and the programme worktree are not being confused.
4. Read the stable capability specs and the active phase proposal/design/tasks.
5. Recheck the dependency gate in `remote-codex-platform-phases.md`.
6. Inspect actual worker/control-plane state before continuing; never infer it from documentation alone.
7. Continue the first unchecked task or record a new decision if evidence invalidates the plan.
8. Validate strictly, collect release/rollback evidence and archive the phase before advancing.

`establish-project-runtime-contract` completed all 21 tasks and is archived at
`openspec/changes/archive/2026-07-26-establish-project-runtime-contract`.
OpenSpec synchronized eight modified requirements with no additions, removals
or renames; strict validation passes for the archived change.

The runtime-engine mode mismatch observed during the 5.4 rollback was corrected
without weakening the invariant. An owned engine state root created below the
canonical setgid runtime directory is normalized to exact mode `0700`. The
regression starts from a setgid parent, fails against the previous engine and
passes against the corrected implementation.

Local and AX42 contract, allocation, dev, manager, engine and admission suites
passed. AX42 returned to the accepted empty-state baseline, retained evidence
from 5.3 and 5.4 remained intact, Beautips remained healthy at the published
commit, and Atenea production remained unchanged and unrouted.

The entry-gate review for `relocate-atenea-development-to-ax42` found a dirty
Atenea development worktree. The operator authorized reconciliation: the
reviewed React-console migration and documentation were validated, committed
and published normally on `feature/actualizar-conversacion-en-web` at
`a9fe14989544308acc587e3eb71cb985fa637b2d`. The branch is now clean and matches
its remote; production remains healthy, unchanged and unrouted.

The active change is apply-ready with proposal, design, two capability deltas
and 27 implementation tasks. Tasks 1.1 through 1.3 recorded the canonical
source and sentinels, committed the schema-valid heavy Atenea manifest, and
defined the empty PostgreSQL 16 migration plus synthetic-fixture contract.
Task 2.1 then added the deliberately adapter-dependent Atenea worker Compose
definition and proved its fail-closed resolution without activating it. Task
2.2 extended only the manager/engine allowlist for that exact manifest and
Compose hash, one persisted heavy allocation, the three declared services,
session-owned paths, three loopback ports, full-runtime resource names and
five exact ownership labels. Task 2.3 then added the dedicated negative policy
corpus at both the manager-inspection and engine closed-plan boundaries. It
proves that daemon sockets, privileged or host namespaces, devices, undeclared
mounts, fixed global identities and unlabelled, partially labelled, foreign
or ambiguous resources fail before engine execution or daemon access. Task
2.4 then passed the complete synthetic contract, allocation, lifecycle,
manager, engine, admission, health/browser/retention and cleanup regression
gate from `/tmp`, plus both focused Atenea adapter suites. The integrated
contract suite's protected manager/engine hashes were advanced only to the
exact task 2.2 implementations.
Detailed results are in `docs/atenea-development-relocation-evidence.md`;
OpenSpec progress is `12/27`.

Atenea is clean and synchronized locally and remotely at
`7cc003dba3b931e5d4769c507d65983d377a3222`. With explicit operator
authorization for task 3.2, the three previously local reviewed commits were
published in order above the accepted entry commit. The first adds
`ops/atenea-runtime.json`; the second adds only
`ops/atenea-development-data-v1.json` and its 45-file migration checksum
inventory; the third adds only `ops/worker/docker-compose.ax42.yml`.
PostgreSQL 16.11 applied all 45
Flyway migrations from an empty temporary volume and reached V45 with zero
domain rows before fixtures; the temporary container and volume were removed.
The declared fixture contains one synthetic operator, one synthetic project,
one closed synthetic WorkSession and two synthetic turns, with all other
domain counts zero and explicit production/external-integration denials.

The current Compose SHA-256 is
`2133646b9fe6227ca417d6d62c92a74306caaa46a2957cdee810d5d7b0e5bb9f`.
It declares exactly `db`, `codex-app-server` and `atenea-dev`, requires all
session identities, images, internal ports, owned paths, network, volume and
secret-file references from the future allowlisted adapter, and has no host
port publication or fallback to `docker-compose.dev.yml`. Resolution from
`/tmp` passed with the AX42 Compose `5.3.1`; all 18 required inputs fail closed
when individually absent.

The allowlisted Atenea plan remains deliberately non-activable: after exact
plan validation the engine rejects lifecycle execution before resolving or
calling a daemon. Manager validation never executes manifest `argv`, and the
plan contains named secret references rather than values. The synthetic
fixture loader remains deferred to 4.3.

Task 3.1 created the sole GitHub-backed Atenea bare mirror at
`/srv/atenea/repositories/atenea.git` as `atenea-worker:atenea`, mode `2770`,
using the contract's credential-free HTTPS identity and
`+refs/heads/*:refs/remotes/origin/*` fetch mapping. A fresh fetch selected
`a9fe14989544308acc587e3eb71cb985fa637b2d`, exactly equal to the accepted
entry commit. The mirror contains none of Atenea's three unpublished local
commits and has no local alternates, credential-bearing URL or persisted
credential material.

Task 3.2 admitted administrative development WorkSession
`41c0ff95-e555-4773-b7b4-60903a3af1ad` in `slot2` with `heavy1`. Its clean
session branch and worktree are pinned at the synchronized GitHub commit
`7cc003dba3b931e5d4769c507d65983d377a3222`. Workspace, admission, allocation,
runtime-path, log, artifact, reconstructible-cache and empty named-secret
reference ownership records are persisted beneath the canonical
`/srv/atenea` roots as `atenea-worker:atenea`, with session roots mode `2770`
and records mode `0640`.

No lifecycle command, manager, engine, client, container, image, network,
volume, listener, service unit or route was started. Slot container/image
counts remain `3/3`, `0/4`, `0/0`, `0/0`; admission capacity is `1/4` normal
and `1/2` heavy.

Task 3.3 first stopped at its default-deny source-isolation gate because the
worker Compose mounted the owned upload path at `/workspace/data/uploads`
without setting `ATENEA_MOBILE_UPLOAD_ROOT`. With explicit commit/push
authorization, Atenea commit
`b6dc854d94ba5b1976926656c9a6aba330f671e2` added only the missing environment
binding and was published on the selected branch. The exact Compose hash and
the manager/engine allowlist hashes were advanced, and both isolated adapter
suites passed against a fresh GitHub checkout.

The AX42 mirror fetched the published correction and the existing
administrative session branch was fast-forwarded locally without publication.
`workspace-v1.json` now records `b6dc854...`; allocation and admission records
remained byte-identical in `slot2/heavy1`. The accepted read-only proof
reproduced Git common-directory, GitHub publication, mirror self-containment,
the three administrative records, the four exact inputs, all 45 migration
hashes and the effective owned upload root. It found no alternate, symlink,
bind mount, control-plane runtime input or real WorkSession/AgentRun authority.
Complete mirror, worktree, record, artifact and cache content/metadata
fingerprints were identical before and after the proof.

Task 4.1 verified the exact committed manifest at
`b6dc854d94ba5b1976926656c9a6aba330f671e2` without executing or changing a
toolchain. The worktree and Git blob both reproduce manifest SHA-256
`3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3`;
the manifest passes the staged versioned schema on AX42. Existing package
records prove the pinned Git, Docker and Compose versions, while immutable OCI
index/platform/config metadata and stored layer identity prove the selected
Node, Java, Maven, Playwright and Chromium versions. No package, image,
wrapper, browser or container was downloaded, installed, updated, built or
executed.

Task 4.2 ran `npm ci`, the zero-vulnerability audit and the canonical
`scripts/web-build.sh` with the pinned Node image in `slot2`. The audit
reported zero vulnerabilities and the generated `index.html`, CSS and
JavaScript are byte-identical to the selected commit. The index references
exactly the two files emitted by Vite, with no stale asset identity. Evidence
is retained beneath the administrative WorkSession artifact root at
`runs/task-4.2-web-build`.

The rootless slot daemon cannot traverse the `atenea-worker:atenea` mode
`2770` worktree ancestors for a direct bind. Task 4.2 therefore used a
byte-exact `git archive` scratch owned by `atenea-slot2`, without changing
ACLs, ownership or the worktree; the scratch was removed. This does not block
the accepted build, but direct worktree mounting remains an explicit gate
before the private runtime task 5.1.

Tasks 4.2 and 4.3 are complete and programme progress is `13/27`. Task 4.3
initialized a new runtime-derived, exactly labelled PostgreSQL volume from
the approved PostgreSQL 16 digest. A one-shot `network=none` helper used the
already documented least-authority pattern of `cap_drop=ALL` plus only
`CAP_CHOWN` to assign the empty volume root to `999:999`, then exited and was
removed. Persistent PostgreSQL ran as `999:999` with all capabilities dropped,
an internal WorkSession network, no published ports and zero host listeners.

Flyway validated and applied exactly the committed V1–V45 inventory from an
empty schema, with 45 successful history rows, zero failures and final version
45. Before fixtures all 28 declared domain counts were zero. The deterministic
fixture created exactly one operator, one project, one closed WorkSession, two
SessionTurns and zero AgentRuns; every other declared count is zero. Exact
reapplication was a no-op and a conflicting pre-existing project failed
closed with a transaction rollback.

All temporary containers, networks, scratch and processes were removed.
Slot2 retains the exact approved image and exactly one session-owned volume
for task 4.4; session-owned containers and networks, allocated-port listeners
and AgentRun routing are zero. Production, preview and Beautips remain `UP`.
Passing evidence is retained beneath `runs/task-4.3-database`; the first
fail-closed attempt remains separately beneath
`runs/task-4.3-database-attempt-1-blocked`.

Task 4.4 is complete and programme progress is `14/27`. The committed
`scripts/test.sh` remained the canonical entry point, with an ephemeral
exact-invocation adapter replacing only its unsafe local Compose operations.
The adapter used the commit-exact source archive, a new task-only PostgreSQL
volume, an internal network and the pinned Maven/JDK 21 and PostgreSQL 16
digests. It did not run `docker compose up`, Codex App Server or the private
application runtime.

After dependency prefetch, the complete backend suite ran once offline:
327 tests passed with zero failures, errors or skipped tests across 48
Surefire XML reports. The container exited zero after 26 seconds; thirteen
samples recorded peak CPU `203.50%`, peak memory `654 MiB / 3 GiB` and peak
PID count `71`. All external integrations were disabled or test-local.

All task 4.4 containers, networks, test volumes, caches and scratch were
removed. Slot2 retains only the accepted task 4.3 database volume for the
private runtime step; there are zero session-owned containers and networks,
zero allocated-port listeners and zero AgentRun routing keys. Evidence is
retained beneath `runs/task-4.4-backend-tests`.

The next action is task 5.1. It has not started. Before starting the admitted
private runtime, resolve the documented direct-worktree traversal gate with a
reviewed byte-exact WorkSession-scoped delivery mechanism; do not widen ACLs,
owners, groups or modes. Detailed evidence is in
`docs/atenea-development-relocation-evidence.md`.

The administrative Codex/tmux bridge may be used to begin work, but it is not
evidence of the managed session isolation boundary and MUST NOT be used as
Atenea's AgentRun executor.

Recommended session title at the current resume point is
`Migración Atenea y Codex al AX42 — implementar reubicación de desarrollo Atenea`.
