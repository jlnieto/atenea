# Remote Codex Platform Programme

## Authority and status

This document is the durable programme ledger for moving Atenea development execution to a dedicated remote worker.

- Programme: `remote-codex-platform`
- Foundation change: `establish-remote-codex-platform-program`
- Current phase: `route-agent-runs-to-remote-worker` (complete and archived
  `35/35`)
- Runtime routing: unchanged; Atenea production is not connected to the AX42
- Production/control plane: current Atenea VPS
- Development/execution plane: Hetzner AX42 (manual pilot only)
- Canonical source: GitHub
- Last evidence refresh: 2026-07-28

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

`relocate-atenea-development-to-ax42` is archived.
`route-agent-runs-to-remote-worker` is archived as
`2026-07-28-route-agent-runs-to-remote-worker` with all `35/35` tasks complete.
Production routing is unchanged and disabled. `add-worksession-attachments` is
archived as `2026-07-29-add-worksession-attachments` with all `31/31` tasks
complete. `add-private-session-previews` is archived as
`2026-07-29-add-private-session-previews` with all `37/37` tasks complete. Its
accepted synthetic boundary used an authenticated coordinator on `8789`,
tailnet-only ingress ports `19000–19031`, a renewable five-minute lease, an
eight-hour hard lifetime and 30-day preview audit metadata. Rollback leaves
the capability disabled with zero route/runtime projection resources. Public
sharing remains disabled. Real-project authoritative activation remains
blocked until independent external backup is configured and restore-tested.
The exact resume point is the Phase 7 entry gate for
`establish-development-database-lifecycle`.
`establish-development-database-lifecycle` is now the active Phase 7 change;
its entry gate is accepted at `5/37`.

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
| D-019 | Pin execution target and immutable workspace identity when each WorkSession is opened. | A feature change or transient failure must never move an active Codex thread implicitly. | accepted | backend owner | remote routing implementation |
| D-020 | Use a UUID dispatch identity plus monotonic worker lifecycle revision as the idempotency boundary. | Retries and duplicate terminal delivery must return existing work rather than create another run or response. | accepted | backend owner | remote routing acceptance |
| D-021 | Use an additive V46 expand/contract migration and disable routing for rollback instead of down-migrating live history. | Retained remote ownership is required for reconciliation and audit; destructive rollback is unnecessary. | accepted | data owner | after remote records have aged out |
| D-022 | Restrict Phase 4 execution to the fixed `synthetic-routing-v1` workload over a private authenticated protocol. | Routing continuity can be proved without granting arbitrary shell, repository, container or real-project authority. | accepted | security owner | first real-project onboarding |
| D-023 | Retain non-terminal lease and lifecycle records; do not reuse expired leases or delete routing history in Phase 4. | Reconciliation requires durable ownership while final retention can be informed by measured synthetic runs. | accepted | backend owner | before production remote-routing defaults |
| D-024 | Keep attachment metadata authoritative in Atenea/PostgreSQL and content bytes on AX42 behind opaque storage identities. | Ordered ownership belongs in the control plane while large content should remain on the worker without exposing filesystem paths. | accepted for synthetic Phase 5 | platform owner | before real-project activation |
| D-025 | Default attachment limits to 16 MiB per file and 256 MiB retained per WorkSession with a narrow validated media-type allowlist. | Safe bounded defaults prevent an upload surface from becoming arbitrary worker storage. | accepted | security owner | after representative preview measurements |
| D-026 | Record `TRANSIENT` 24-hour, `SESSION` 30-day and `EVIDENCE` 180-day retention classes but perform no general deletion in Phase 5. | The contract needs deterministic retention metadata while production cleanup requires measured evidence and external backup. | accepted for preproduction | data owner | before production defaults |
| D-027 | Keep Phase 5 default-off and exact-synthetic-only until independent external backup is configured and restore-tested. | RAID is availability, not backup, and synthetic fixtures are recreatable. | accepted | operations owner | before authoritative real-project artifacts |
| D-028 | Use one bounded tailnet ingress port per active preview instead of a shared path-rewriting proxy. | Legacy applications commonly emit root-relative redirects, cookies and assets; a private ingress port preserves application semantics without publishing the allocation-derived runtime port. | accepted for synthetic Phase 6 | platform owner | after representative project onboarding |
| D-029 | Give preview routes a five-minute renewable lease, eight-hour hard lifetime, 60-second revocation target and 30-day audit metadata. | Durable intent must survive restart, while abandoned development routes must not remain reachable indefinitely. Attachments keep their independent retention. | accepted for synthetic Phase 6 | operations owner | before production preview defaults |
| D-030 | Keep Phase 6 public sharing disabled and generate localhost forwarding only for an explicit manifest declaration. | Tailnet-only access satisfies laptop/Android operation without introducing Internet ingress; per-project origin constraints must be proven rather than guessed. | accepted | security owner | each project onboarding |
| D-031 | Limit Phase 7 to deterministic PostgreSQL and MariaDB fixtures containing no production-derived rows. | Database ownership, replacement and restore can be proven without granting AX42 production connectivity or adopting retained real-project volumes. | accepted for synthetic Phase 7 | data owner | each project onboarding |
| D-032 | Keep at most three synthetic snapshots for seven days and require a one-use five-minute revision-bound replacement challenge plus verified pre-snapshot. | Bounded local evidence and explicit destructive intent are sufficient for recreatable fixtures; authoritative retention remains blocked on external backup. | accepted for synthetic Phase 7 | data/operations owners | before authoritative database activation |
| D-033 | Archive a closed synthetic WorkSession's byte-exact allocation record only after its admission is released and its exact runtime resources are absent. | A released slot must become reusable without discarding immutable allocation evidence, worktree, mirror, Git, logs or artifacts. | accepted for completed synthetic fixtures | runtime owner | before general allocation retirement support |

## Deferred decisions and gates

| Decision | Deferral | Must be resolved before |
|---|---|---|
| Second independent tailnet administrator | The operator chose one paid seat initially. `info@codynwave.com` is Owner; Microsoft recovery and public SSH break-glass remain mandatory. | removing public SSH break-glass or expanding beyond one operator |
| External backup target and retention | Compare Hetzner Storage Box with an independent provider and existing storage. | storing authoritative non-Git artifacts or completing operational hardening |
| Final pilot | Beautips is provisional; first reconcile its local and Atenea commits. | enabling first real project run |
| Per-project localhost requirement | Discover through cookies, callbacks and browser tests. | declaring that project's private preview ready |
| Initial runtime sandbox implementation | Prototype mediated rootless/container alternatives against the no-host-socket requirement. | accepting the runtime contract phase |
| Terminal AgentRun, artifact and preview retention durations | Non-terminal Phase 4 lease/lifecycle retention is fixed; measure representative runs before choosing terminal cleanup. | production defaults in remote routing/preview phases |
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

The first task 5.1 preflight stopped before daemon access. Its unchanged
blocked evidence was moved to
`runs/task-5.1-private-runtime-attempt-1-blocked`; the SHA-256 of its
`SHA256SUMS` remains
`4098564cff3eccda9002fa85fd6d9c1e593997ea0f5ea7fd694b7b3962f240b4`.

Task 5.1 is now complete and programme progress is `15/27`. The versioned
runtime contract installs a root-owned client, manager, engine and dedicated
Atenea adapter, creates a commit-exact WorkSession delivery without changing
the protected worktree ancestors, and starts exactly `db`,
`codex-app-server` and `atenea-dev` through the admitted
`slot2/heavy1` lifecycle.

The three containers are running with the exact runtime-derived names and
ownership labels, read-only root filesystems, all capabilities dropped,
`no-new-privileges`, no host namespaces, devices, privilege or daemon socket.
They share one labelled internal network. Because that network has no Docker
gateway publisher, the adapter retains exactly three RootlessKit `tcp4`
mappings on `127.0.0.1`: `28541→5432`, `22667→8092` and `22359→8081`.

Codex `0.145.0` is fixed at OCI digest `sha256:c081aaa9...`; its
authentication-disabled App Server listens only on container loopback and a
reviewed same-container TCP proxy exposes the declared internal port. Atenea
returns `UP` at its private health endpoint. PostgreSQL reuses the exact task
4.3 volume, data root, database and role: Flyway history remains byte-identical
at 45 successful V1–V45 rows, and all 28 counts remain exact, including one
synthetic operator, one project, one closed WorkSession, two SessionTurns and
zero AgentRuns. No fixture or migration was rerun.

OpenAI, DeepSeek, FCM, GitHub operations and other external integrations are
disabled or fail-safe local. Rootful Docker and containerd remain inactive
and masked; production, preview and Beautips remain `UP`; routing and secret
value matches remain zero. Passing evidence is retained beneath
`runs/task-5.1-private-runtime`; the SHA-256 of its `SHA256SUMS` is
`23010f74668e1f962a056b67505bb8c9816e47a953409fd2a53c0056f87ea856`.
Detailed evidence is in
`docs/atenea-development-relocation-evidence.md`.

Task 5.2 is complete and programme progress is `16/27`. A reviewed,
package-lock-enforced Playwright `1.60.0` module bundle was installed for
slot2 and verified against content-tree SHA-256
`1ca49077563d996a21591e41f5a71296747d81ed9f1936e4887924fcb574b2ee`.
The official pinned image continues to provide Chromium `148.0.7778.96`;
the module bundle is mounted read-only and no dependency download occurs
during browser acceptance.

Playwright authenticated the synthetic operator at `1440x900` and `390x844`
through the exact WorkSession internal network. Both viewports proved login
absence after authentication, the expected operator identity, non-empty
complete DOM, `Atenea Core`, the synthetic project, the closed fixture's
declared `Sin sesión` operator projection and the expected enabled/disabled
critical actions. Login and project-overview reads returned HTTP 200. Browser
external requests, failed local requests, AgentRuns, routing, secret matches,
screenshots and traces were all zero.

The committed manifest preview path `/admin/login` returned 404 in the
selected Atenea commit. Acceptance therefore used `/`, backed by the
commit-exact `src/main/resources/static/index.html` Git object
`ac4ea34f6dabcb4e200188afad801928bcb79d0d`; the discrepancy is retained in
the evidence rather than hidden or repaired through a runtime redeploy.

The accepted runtime, three containers, internal network, retained volume,
three loopback listeners, 45 Flyway migrations and exact synthetic counts
remain unchanged. Production, preview and Beautips remain `UP`; browser
processes and refresh tokens were cleaned to zero. Passing evidence is beneath
`runs/task-5.2-playwright-dom`; the SHA-256 of its `SHA256SUMS` is
`351dca13a8e356bf0eac6e8018f672250de5a4006887ff711d4505af445b7418`.
Toolchain remediation evidence is beneath
`runs/task-5.2-toolchain-remediation`.

Task 5.3 is complete and programme progress is `17/27`. Playwright ran only
on AX42 in the same admitted `slot2/heavy1` WorkSession network and captured
the authenticated Projects screen at `1440x900` and `390x844`. Pre-capture
semantic locators proved the expected synthetic operator, project,
`Sin sesión` projection and critical action states, with no login, permanent
loading, inline error, external browser request or failed local request.

Finite DOM measurements recorded equal `scrollWidth` and `clientWidth` at
both viewports, full viewport intersection for the critical state and actions,
and zero stable visible overlaps. Direct inspection of both original-resolution
PNGs passed hierarchy, readability, primary-action visibility, clipping,
overlap, overflow, control containment, wrapping, empty-state distinction and
desktop/mobile consistency.

The accepted commit, runtime, three containers, internal network, retained
volume, three listeners, 45 Flyway migrations and exact synthetic counts
remain unchanged. Refresh tokens, AgentRuns, routing and browser processes are
zero. Production, preview and Beautips remain `UP`. Final sanitization found no
secret value or forbidden unsanitized browser artifact. Passing evidence is
beneath `runs/task-5.3-playwright-visual`; the SHA-256 of its `SHA256SUMS` is
`8d6cc8093107126b2d07b517d0ef5177462c609fea996d285cc8d7743cedf37f`.

Task 5.4 is complete and programme progress is `18/27`. The accepted probe
ran only on AX42 against the existing admitted `slot2/heavy1` WorkSession and
its loopback endpoints. Runtime configuration kept OpenAI, DeepSeek costs,
briefing, FCM and LLM intent routing disabled; all declared provider base URLs
remained the non-routable loopback sentinel `127.0.0.1:9`, the session network
remained internal and no external credential environment variable existed.

Authenticated costs returned OpenAI and DeepSeek as `configured=false` and
`disabled`. Speech synthesis, realtime voice and transcription each returned
the exact sanitized HTTP 503 disabled outcome before provider transport.
Source-guard hashes and runtime boundaries prove that disabled FCM returns
before token/message HTTP, disabled DeepSeek briefing returns before provider
HTTP and an absent GitHub token fails before GitHub HTTP; no operational
GitHub, push, briefing, host-management or external-provider action was
invoked. Runtime log signatures and the internal network boundary recorded
zero provider attempts.

Logout revoked the one temporary refresh token and bounded cleanup restored
the table to zero. Flyway remained at 45 successful migrations; the exact
synthetic counts, Git identities, WorkSession records, allocation, admission,
three containers, one network, one retained volume and three listeners were
unchanged. AgentRuns, API usage, push records, managed hosts, core commands,
routing and residual task processes remained zero. Production, preview and
Beautips remained `UP`; rootful Docker, its socket and containerd remained
inactive and masked. Final scanning found no secret value, retained auth
material or unsanitized provider response.

Passing evidence is beneath
`runs/task-5.4-external-integrations-fail-safe`; the SHA-256 of its
`SHA256SUMS` is
`bc750f5c958867f69b6f8b23d562ed7a13c96e990fb5f64b2d463ca0e10d0a70`.

Task 6.1 is complete and programme progress is `19/27`. A key-authenticated
private SSH connection over Tailscale established the named tmux session
`codex-atenea-41c0ff95` as administrator `jose`, with one
`administrative` window rooted at the exact admitted WorkSession worktree.
Codex `0.145.0`, the sanitized `remote-codex-admin-v1` context and ChatGPT
login guard passed. The conversation contains the non-secret continuity marker
`CONTEXT-READY ATENEA-41C0FF95-20260728`.

Tmux options label the session `administrative` and bind it to the existing
WorkSession/runtime while explicitly recording `AgentRun=none`, worker
lease `none` and routing `none`. The Codex process has no `DOCKER_HOST`
environment. The worktree commit/tree/index, workspace/allocation/admission
hashes, private runtime resources, 45 migrations and synthetic counts remained
unchanged. AgentRuns, refresh tokens and routing records remain zero.
Production, preview and Beautips remain `UP`; rootful Docker, its socket and
containerd remain inactive and masked.

The installed `codex-work` helper differs from the programme template only by
`export COLORTERM=truecolor`; the retained comparison proves no workspace,
daemon or authority change. Task 6.1 used an explicit detached tmux command,
not that drifted helper. Final scanning retained no Codex auth/history/session
file, environment dump or secret value.

Passing evidence is beneath
`runs/task-6.1-administrative-tmux-session`; the SHA-256 of its
`SHA256SUMS` is
`c914c4d4234701dd5d2d01ecabcd841f6c7fd72fca09bc982f4bef5045498ecf`.

Task 6.2 is complete and programme progress is `20/27`. Two independent,
finite private SSH clients attached in sequence to the existing
`codex-atenea-41c0ff95` tmux session. Before the first disconnect, during the
detached interval, after the second resume and after final detach, the session
retained `session_created=1785262669`, window `administrative`, pane `%0`, pane
PID `1170290`, the exact worktree and the same live Codex process. Attached
client counts followed `0→1→0→1→0`.

The resumed pane still contained
`CONTEXT-READY ATENEA-41C0FF95-20260728`. Without tools or file changes, the
existing conversation returned the exact response
`CONTINUITY-RESUMED ATENEA-41C0FF95-20260728`. The final client detached
cleanly; tmux and Codex remain alive with zero attached clients.

The worktree commit/tree/index, workspace/allocation/admission hashes,
`slot2/heavy1` identity, runtime `ready/healthy`, three containers, internal
network, retained volume and three loopback listeners remained unchanged.
Flyway remains at 45 successful migrations and the synthetic counts remain one
operator, one project, one closed WorkSession and two SessionTurns. AgentRuns,
refresh tokens and routing remain zero. The session labels still classify this
as administrative with no dispatch or lease. Production, preview and Beautips
remain `UP`.

Passing evidence is beneath
`runs/task-6.2-administrative-continuity`; the SHA-256 of its `SHA256SUMS` is
`1216ed3162348b6d3f4f2e465bffd071ed8ec468b792bf1b5ff517b176bb54ed`.
Sanitization retained no raw terminal, Codex auth/history/internal-session
file, token, cookie, environment dump or credential-pattern match.

Task 7.1 is complete and programme progress is `21/27`. The installed mediated
manager stopped the exact admitted Atenea runtime and returned
`stopped/stopped` in 1,631 ms. Its fixed adapter retained logs and removed the
three owned RootlessKit listeners. A task-scoped rollback wrapper then required
the exact five ownership labels and immutable IDs before removing only the
three stopped session containers and their now-empty internal network.

The session PostgreSQL volume and complete image inventory were retained.
`heavy1` was released before `slot2` through the exact versioned admission
tool; the admission record now records `released/released`. Workspace and
allocation records, mirror refs, worktree commit/tree/index, post-stop logs and
all prior retained artifacts are unchanged. The administrative tmux/Codex
session remains alive with zero attached clients.

The first wrapper continuation stopped after the successful mediated stop
because its network assertion still expected the pre-stop three endpoints;
Compose had correctly disconnected stopped containers and the actual count was
zero. No resource had yet been removed and admission remained held. The
assertion was corrected, the stop was not repeated and the runtime was not
recreated.

Pre-stop Flyway and synthetic data checks remained exact at 45 migrations, one
operator, one project, one closed WorkSession, two SessionTurns and zero
AgentRuns/refresh tokens. Routing remains zero. Production, preview and
Beautips remain `UP`; rootful Docker, its socket and containerd remain
inactive.

Passing evidence is beneath
`runs/task-7.1-atenea-runtime-rollback`; the SHA-256 of its `SHA256SUMS` is
`25c6a03f43c727652020161116011a82d3a881e2b8b74ba94dd59b6b3bd2bf70`.
Sanitization retained no Codex auth/history/internal-session file, token,
cookie, environment dump, private key or credential-pattern match.

Task 7.2 is complete and programme progress is `22/27`. The bounded second
rollback pass began from the exact 7.1 terminal boundary: zero session
containers, networks and listeners, one retained PostgreSQL volume and
persisted `slot2/heavy1` admission already `released/released`. It removed zero
containers, networks, images and listeners and did not recreate the runtime.
The mediated manager rejected a post-release stop with the expected
`RUNTIME_OWNERSHIP_CONFLICT`; repeating both versioned admission releases was
an idempotent zero-exit no-op.

Four synthetic network identities then exercised literal no-label, partial
label, complete foreign-owner and complete-but-ambiguous ownership. Every
candidate was recorded by immutable ID, name, creation time, driver and labels
before use. The exact rollback ownership gate rejected every case with exit 65
and `RUNTIME_OWNERSHIP_CONFLICT`; inspect SHA-256 remained byte-identical and
each rejected resource remained present during denial. Cleanup revalidated the
complete recorded identity and removed only that exact immutable ID.

All four rootless-slot container, network, volume and image inventories match
their pre-fixture fingerprints. The Atenea session has zero residual
containers, networks, owned images, allocated listeners, brokers and
Playwright/Chromium processes, while its labelled PostgreSQL volume, mirror,
worktree, clean Git/index, allocation, delivery, engine state, logs and all
prior artifacts remain byte-identical. AgentRuns, worker lease and routing
remain zero/none. The administrative tmux/Codex session remains alive with the
same identity and zero attached clients. Production, preview and Beautips
remain `UP`; rootful Docker, its socket and containerd remain inactive.

Four bounded preliminary attempts are retained transparently. They exposed an
inaccessible inherited cwd, an unbounded denial exit, an absent-resource
status propagated after exact deletion, and a nominally unlabelled fixture
carrying a non-ownership task label. No attempt recreated runtime, removed a
rollback target, changed admission or modified a foreign resource. The two
anonymous volumes created by the early container fixtures were identified by
their exact creation identities and removed by immutable ID; the pre-existing
anonymous volume and the retained session volume were preserved. The accepted
corpus uses network-only fixtures and a literally unlabelled candidate.

Passing evidence is beneath
`runs/task-7.2-rollback-idempotence`; the SHA-256 of its `SHA256SUMS` is
`f65acffc596e333ac3a3428c784756eeee8b73729d6046c5e810e051b84745c0`.
Sanitization retained no Codex auth/history/internal-session file, token,
cookie, environment dump, private key or credential-pattern match.

Task 7.3 is complete and programme progress is `23/27`. After the separately
authorized single AX42 restart, the boot ID changed from
`0886b4d0-485c-4035-b8bb-1b0ab910e85c` to
`5cc2a4e3-020d-4d19-8a55-6ecae77f22ce`. Finite SSH probes first observed the
host unavailable and reconnected on attempt 10. No second reboot was
requested.

All three RAID arrays returned `[UU]`; storage, key-only SSH, firewall,
Tailscale, the health timer and the strict worker health suite pass. The four
rootless user daemons and daemon sockets returned automatically. Read-only
`docker info` through each stable proxy socket proved all four proxy paths.
Rootful Docker, its socket and containerd remain inactive and masked.

Reconciliation selected only the exact persisted workspace, allocation,
released admission, engine owner marker and rootless immutable metadata for
WorkSession `41c0ff95-e555-4773-b7b4-60903a3af1ad`. The allocation still names
`slot2/heavy1`, admission remains `released/released` and no ephemeral runtime
resource exists. The accepted outcome is therefore `stopped/stopped` with
action `report-only`: no runtime was recreated or started, no resource was
removed, no volume was reattached, no slot was reassigned and no ownership was
invented.

The retained PostgreSQL volume, mirror refs, worktree commit/tree/index,
workspace/allocation/admission records, engine state, logs and every prior
artifact survived byte-identically. The rebuildable delivery under `/tmp` was
cleared by reboot as expected. Rootless Docker regenerated only each daemon's
default `bridge` ID; network name/driver shape is identical and the `host`,
`none` and persistent Beautips network IDs are exact. No session container,
network, owned image, listener, AgentRun, lease or routing record appeared.

The administrative tmux/Codex session ended with the host reboot, as expected
for the non-persisted administrative bridge. It was not recreated or replaced.
Production and preview remain `UP` with the same nine immutable containers.
Beautips remains `UP` with the same three immutable containers.

Two read-only preflight attempts are retained: an outer capture returned 1
before reboot, then a doubled escape in the healthy `[UU]` assertion was
localized and corrected. Two postflight assertion continuations distinguished
regenerated default bridges from persistent network ownership and sorted the
normalized network shape. None changed a resource or issued another reboot.

Passing evidence is beneath
`runs/task-7.3-restart-reconciliation`; the SHA-256 of its `SHA256SUMS` is
`57c702382e7d9551224d19121a310adb337b6aba554fe5434bc57e553f0819ba`.
Sanitization retained no Codex auth/history/internal-session file, token,
cookie, environment dump, private key or credential-pattern match.

Task 8.1 is complete and programme progress is `24/27`. The final
control-plane capture remains clean and synchronized: the programme was at
`bb14726b06ad07c8cb804fd76b3747beb37fa474` before handoff documentation and
the Atenea source remains on
`feature/actualizar-conversacion-en-web` at
`b6dc854d94ba5b1976926656c9a6aba330f671e2`. Production and preview are `UP`.
The nine immutable production/preview container IDs match task 7.3 exactly,
including the production PostgreSQL container. This proves the unchanged
runtime configuration and environment boundary without reading an environment
value or database row. Source and persisted routing-record scans are zero.

The final AX42 session inventory has zero containers, networks, owned images
and allocated listeners, one retained labelled PostgreSQL volume, zero
AgentRuns by the unchanged retained database evidence, no lease and no routing.
Beautips remains `UP`. Passing evidence is beneath
`runs/task-8.1-final-non-impact`; the SHA-256 of its `SHA256SUMS` is
`21ef3351db436d2cec0223a692c92ca6c303e08683553eeafe37744f942692d7`.
A read-only first assertion attempt is retained separately and records no
resource change.

Task 8.2 is complete and programme progress is `25/27`. The strict installed
worker verifier passes. All three RAID arrays are `[UU]` with no recovery
action; root and Atenea filesystems are each at 4% use. UFW is active, SSH is
key-only, Tailscale is online with no Serve configuration, and all four
rootless slots, daemon sockets and stable proxies are healthy. Every slot
retains the accepted CPU `4s`, `MemoryHigh=10737418240`,
`MemoryMax=12884901888` and `TasksMax=4096` boundary.

Rootful Docker, its socket and containerd remain inactive and masked, with no
Docker group members. All slot container, image, volume, normalized-network
and persistent-network inventories equal task 7.3. Beautips remains clean,
synchronized at `5044a3b07b3db82895e9c8ff47bc4bc9b0e97130` and `UP` with
the same immutable containers. Passing evidence is beneath
`runs/task-8.2-final-worker-audit`; the SHA-256 of its `SHA256SUMS` is
`00de504f1a1381c5945701d08dc3ebcdba88703c98d1655200994b731a538a00`.

Task 8.3 is complete and programme progress is `26/27`. The operator workflow,
rollback boundary and explicit administrative resume procedure now distinguish
this accepted manual pilot from managed AgentRun routing. The handoff points
only to non-secret artifact roots and verified manifests. Passing evidence is
beneath `runs/task-8.3-operator-handoff`; the SHA-256 of its `SHA256SUMS` is
`0068a4f8428e6d8a2d2c1bb8896bb8c68b8f90e544b21cbd0f9e6676743338f7`.

Task 8.4 is complete and programme progress is `27/27`. All task checkboxes
were complete before strict change validation passed. One
`openspec archive relocate-atenea-development-to-ax42 -y --json` invocation
archived the change as
`openspec/changes/archive/2026-07-28-relocate-atenea-development-to-ax42` and
synchronized seven added requirements plus one modified requirement into the
normative specs. Strict all-spec validation passed.

The worktree and cached diff checks identified one blank line at EOF introduced
by the archive formatter in each of the two synchronized specs. Removing only
those two blank lines made the diff clean and strict all-spec validation passed
again. The archive command was not repeated and the index remained empty. No
runtime, route, production resource, unrelated slot or Beautips resource
changed.

Passing evidence is beneath `runs/task-8.4-openspec-archive`; the SHA-256 of
its `SHA256SUMS` is
`7f03e7ba6916d8394daed6fac2795fdec0a30c8e8e3a7f2d83d75cb49558c6cc`.

## Phase 4 entry and active resume point

The `route-agent-runs-to-remote-worker` entry gate was accepted on 2026-07-28.
Canonical programme Git was clean at
`8b964f2c3db54481315b59a9ed7ac1a399f53353`; Atenea source was clean at
`b6dc854d94ba5b1976926656c9a6aba330f671e2`. Production, preview and Beautips
were `UP`, production routing records were zero, AX42 strict health passed,
three RAID arrays were `[UU]`, all four bounded rootless slots were healthy and
the accepted capacity remained four normal slots plus two heavy permits.

The exact installed runtime client, manager, engine and Atenea adapter hashes
match their versioned sources. The retained production-schema backup is
`/srv/atenea/backups/prod/atenea_prod_before_remote_routing_v46_20260728T222500Z.dump`
with SHA-256
`a48a7d25b5d9b3289e926bef4201c074c5f523bb32a793b0f3ccc8e1f1760160`.
It restored successfully into a network-disabled disposable PostgreSQL 16
fixture with the full successful Flyway V45 history and expected public tables.
The fixture was removed by exact immutable identity.

Sanitized accepted evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/route-agent-runs-to-remote-worker/entry-gate`;
the SHA-256 of its `SHA256SUMS` is
`783780d6170441392e7cc2124ecf54c571df859005d1847b710dc728a946b245`.
The first blocked lexical-version check and its exact cleanup remain retained
separately for audit; it changed no production or foreign resource.

Tasks 1.1–1.4 are complete. The proposal,
design and deltas close execution affinity, authenticated protocol, lease,
capacity, synthetic scope and non-destructive migration rollback decisions.
Attachments/previews, real-project selection, per-project localhost,
artifact-promotion authority, external backup retention and a second tailnet
administrator remain at their later declared gates and do not block synthetic
Phase 4 routing.

Tasks 2.1–2.4 and 3.1–3.4 are complete and programme progress is `12/35`.
The additive V46 model, immutable session affinity, durable dispatch identity,
lease/lifecycle state, optimistic terminal acceptance and non-terminal
per-session uniqueness are committed in Atenea source at
`23a9549e2ef2f3930325004068aea7bc0aad7454`. Routing remains default-disabled
and existing rows remain local.

The accepted V45 restore plus current V46 migration proof is beneath
`runs/task-2.4-v46-restore-migration`; the SHA-256 of its `SHA256SUMS` is
`70a752d212a6ae4e2ee77a083200859968b4c85b46cf70a221d31e605b7ec18f`.
It used a network-disabled disposable PostgreSQL fixture, verified the
expand/contract boundary and removed the fixture by exact identity without
changing production.

The versioned worker source now implements authenticated
`agent-run-worker/v1`, atomic durable state, idempotent dispatch, fail-closed
conflict handling, exact cancellation and bounded four-normal/two-heavy FIFO
scheduling. Its seven protocol/scheduler tests pass, including service-state
restart recovery with the same execution identity. The complete Atenea backend
suite passed with `330` tests and no failures after recreating only its
disposable test database; focused routing/API/reconciliation tests passed
`19/19`.

Tasks 3.5 and 4.1–4.7 are complete and programme progress is `20/35`. The
private worker is installed as `atenea-worker`, listens only on
`100.81.98.93:8787`, and UFW permits that port only from Atenea at
`100.88.252.28`. Unauthenticated health is rejected, an unrelated tailnet
source cannot connect, and authenticated health reports the exact v1 protocol,
`ax42-01` identity and `4/2` capacity. Root-owned configuration, bounded
readiness and systemd hardening pass the installed verifier.

Accepted installation evidence is beneath
`runs/task-3.5-private-worker-install`; the SHA-256 of its `SHA256SUMS` is
`62d1ffaecc727b876996529c7b9e6d78be4224e944666db9871e9b378d057d55`.
Two fail-closed verifier attempts and their separately committed fixes remain
documented there; neither accepted an execution.

Atenea now has default-disabled exact-allowlist selection, authenticated finite
clients, durable-before-dispatch coordination, monotonic optimistic terminal
acceptance, exact cancellation and persisted-target reconciliation. The local
startup path explicitly excludes remote runs and its original stale-run policy
continues for local runs.

Tasks 5.1–5.5 are complete and programme progress is `25/35`. The final Atenea
backend suite passed `335/335`; the web build and canonical packaged backend
build also passed on AX42. Installed-protocol acceptance returned one execution
for an identical retry, rejected conflicting identity reuse with HTTP `409`,
held the fifth normal run queued behind four permits, and held the third heavy
run queued behind two permits. All admitted and queued fixtures subsequently
completed and capacity returned.

The exact Compose fixture was removed with volumes and local build images.
Slot 2 returned to zero containers and custom networks, seven baseline images
and only the pre-existing retained Phase 3 PostgreSQL volume. Production
configuration, routing and AgentRun count remained unchanged.

Accepted validation evidence is beneath `runs/task-5-automated-validation`;
the SHA-256 of its `SHA256SUMS` is
`dc4f59d3c58c0b760eaed04d95fc58e8b9faf84948cc10b1748e74f63a12d754`.

Tasks 6.1–6.5 are complete and programme progress is `30/35`. An empty
disposable V46 control plane completed six remote AgentRuns: five succeeded and
one exact cancellation became `CANCELLED`; no non-terminal row remained.

One live execution survived an Atenea backend restart with the same dispatch
and execution identities and exactly one visible response. A proxy-scoped
partition exposed `RECONCILING` with an explicit no-replacement reason, then
healed to the same successful execution. Exact cancellation left a concurrent
unrelated execution running to success. Three turns in one WorkSession retained
`ax42-01`, one workspace identity and one synthetic thread, while using three
distinct dispatch/execution identities and producing exactly six visible
operator/worker turns.

Production AgentRun count and routing remained unchanged; production and
preview containers remained `UP`. Beautips health remained `UP`, all four
rootless slots retained their accepted inventories, RAID remained healthy and
the private worker returned to zero capacity in use.

Accepted continuity evidence is beneath `runs/task-6-synthetic-continuity`; the
manifest includes the rollback instance identity recorded before removal. The
final SHA-256 of its `SHA256SUMS` is
`c3ef39356cd83d92e82a8a0c64ad7b5bb1c6b1cbc5a34948384c1385672f8292`.

Tasks 7.1–7.4 are complete and programme progress is `34/35`. Remote selection
was disabled with all six synthetic AgentRuns terminal. The four existing
WorkSessions retained their persisted remote affinity. The first exact rollback
removed the registered disposable resources and private firewall/listener; the
second exited zero with every target already absent and removed nothing.

Worker state retains fifteen terminal protocol records (`14 SUCCEEDED`,
`1 CANCELLED`) and zero non-terminal records. The worker is inactive/disabled
with no port 8787 listener or UFW rule. Production remains on its unchanged
schema with AgentRun count `58`; production, preview and Beautips remain `UP`.
AX42 strict verification passes, RAID is `[UU]`, slot inventories match
baseline, source Git is clean/synchronized and no Phase 4 temporary resource
remains.

Accepted rollback and observation evidence is beneath
`runs/task-7-rollback-observation`; the SHA-256 of its `SHA256SUMS` is
`5db761a247ee2c5981ca67fb62046e7e0b250c7a07c044056e8d484775ceeb89`.

Task 7.5 is complete and programme progress is `35/35`. Strict change
validation passed with all tasks checked before one archive invocation moved
the change to
`openspec/changes/archive/2026-07-28-route-agent-runs-to-remote-worker`.
Twelve modified requirements were synchronized into the two normative specs.
The archive formatter added one blank line at EOF to each synchronized spec;
removing only those lines made the diff clean. Strict validation then passed
for all seven normative specs.

Accepted archive evidence is beneath `runs/task-7.5-openspec-archive`; the
SHA-256 of its `SHA256SUMS` is
`fbc4713c8a884144d1d1b73728a72d455e49507a6c79fe658c025ecfbe2a77c6`.

The exact resume point is the Phase 5 entry gate for
`add-worksession-attachments`.

## Phase 5 progress: add-worksession-attachments

Tasks 1.1–1.4 are complete and change progress is `4/31`. The accepted entry
gate proves clean synchronized source and programme Git, unchanged production
and preview container identities and health, no V47 source, no AX42 attachment
root, an inactive/disabled Phase 4 worker, four healthy rootless slots, healthy
RAID and unchanged Beautips state. The first capture attempt is retained
separately because obsolete verifier/database assumptions exited non-zero; it
was not accepted and caused no mutation.

The storage, metadata, access-control, ordering, limits, retention and rollback
contract is approved for exact synthetic preproduction use. Authoritative
real-project activation is explicitly blocked until an independent external
backup target is configured and restore-tested. Accepted entry evidence is
beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-worksession-attachments/entry-gate`;
the SHA-256 of its `SHA256SUMS` is
`1d63a3ce1c6b76d2baa03b7422260796ee365e6a6f9e9200cb025b71ced7913d`.

The exact resume point is task 2.1 of `add-worksession-attachments`.

Tasks 2.1–2.4 are complete and change progress is `8/31`. Atenea source commit
`631ee048e9f3f541a940e3bedcaecb8d909ca251` adds the expand-only V47 attachment
table, immutable UUID/integrity/storage identities, ownership-derived project
metadata, exact optional AgentRun validation, pessimistic WorkSession quota
serialization, idempotent conflict detection and deterministic
`createdAt DESC, id DESC` screenshot queries.

The focused metadata suite passes `7/7`. A clean disposable PostgreSQL schema
validated and applied all 47 migrations, Hibernate schema validation passed
and the existing WorkSession integration suite passed `26/26`. The first V47
integration attempt exposed a `CHAR`/`VARCHAR` mapping mismatch; the disposable
test schema was recreated exactly, V47 was corrected before publication and
the accepted rerun passed.

The exact resume point is task 3.1 of `add-worksession-attachments`.

Tasks 3.1–3.4 are complete and change progress is `12/31`. The versioned
`worksession-attachment/v1` service accepts only authenticated exact UUID
routes; streams bounded content through an owned temporary file; verifies the
declared SHA-256 and file signature/text encoding; atomically publishes content
plus metadata; returns opaque identities; and exposes no filesystem list,
path, command or execution field. It independently enforces 16 MiB file and
256 MiB WorkSession limits.

Identical retries return the original object and conflicting identity reuse
changes nothing. General deletion is absent; the exact delete route requires
both persisted and request-side synthetic-fixture identity. The `11/11`
protocol tests cover authentication, atomic/idempotent create, conflict,
integrity, MIME, file/quota bounds, cross-session/traversal rejection,
restart persistence, download and exact synthetic cleanup.

The exact resume point is task 3.5 of `add-worksession-attachments`.

Task 3.5 is complete and change progress is `13/31`. AX42 runs the
enabled/active service only on `100.81.98.93:8788`; UFW admits that port only
from Atenea tailnet identity `100.88.252.28`. The retained root is
`0700 atenea-worker:atenea`, initially contains only its owned `.incoming` and
`work-sessions` directories and starts no project runtime. Installed programme
and unit SHA-256 identities match commit
`0cc8b7b09d00f45dde160400560890de15cbef52`. The follow-up aligns the worker
route with Atenea's canonical positive-decimal WorkSession database identity;
the attachment identity remains a UUID.

AX42 strict verification and RAID pass after installation. All four slot
inventories remain untouched and Beautips reports actuator health `UP`.
Accepted evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-worksession-attachments/runs/task-3-worker-storage`;
the SHA-256 of its `SHA256SUMS` is
`f81c159f533b399331c130187f94b7c17d2fe1f73410512aa59862ad4a55dc44`.

The exact resume point is task 4.1 of `add-worksession-attachments`.

Tasks 4.1–4.4 are complete and change progress is `17/31`. Atenea source commit
`7a77923da458a4488aabb897860d13afb7c4ad58` adds default-off configuration,
finite-timeout private worker authentication, WorkSession-scoped web/mobile
upload and read APIs, exact integrity-checked download and bounded screenshot
resolution. API responses omit worker paths and storage identities; existing
Spring Security authentication covers every new `/api/**` route.

Creation is limited to an exact synthetic project allowlist plus persisted
remote worker affinity. Project identity is derived from the WorkSession and an
optional AgentRun must belong to that same session. The legacy global mobile
upload remains compatible while the capability is disabled and fails with an
actionable WorkSession instruction when scoped attachment creation is enabled.
The focused client, service, controller, metadata and compatibility suites pass
`20/20`. The worker protocol also accepts a content-identical idempotent retry
with a later request timestamp while retaining the original immutable
`createdAt`; all other classification or content changes remain conflicts.

The exact resume point is task 5.1 of `add-worksession-attachments`.

Tasks 5.1–5.4 are complete and change progress is `21/31`. Atenea source commit
`e98138dd2e82e928399502a040f6c01557d2a1ad` adds one compact attachment surface
to the existing WorkSession conversation: current retained count, accepted
types and 16 MiB bound are visible immediately, one primary upload action is
available, retained items download through the authenticated client and
backend failures remain actionable.

The production web bundle builds successfully. Focused backend tests pass
`21/21`. A controlled Playwright validation exercised a successful list/upload
refresh at `1440x900` and an unsupported-format state at `390x844`; DOM
assertions passed, screenshots were visually inspected, no horizontal overflow
was present and the browser closed cleanly. Accepted sanitized evidence is
beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-worksession-attachments/runs/task-5-operator-experience`;
the SHA-256 of its `SHA256SUMS` is
`3dc344dd8f446a2990e9ea8952432c040bf7bccbe31a90144b113177dbe38ff7`.

The exact resume point is task 6.1 of `add-worksession-attachments`.

Tasks 6.1–6.5 are complete and change progress is `26/31`. The accepted
disposable control plane applied all 47 migrations and the complete Atenea
regression passed `356/356`. Exact synthetic HTTP acceptance proved private
authentication, one-row/one-object idempotency, stable integrity, prompt/image
ownership under WorkSession `51001` and AgentRun `51001`, bounded ordering by
session/source and exclusion of a newer cross-project sentinel.

Unauthorized, foreign-session, foreign-run, conflicting, unsupported,
content-mismatched, oversized, quota, empty and traversal inputs failed closed
with their expected actionable status. Rejected identities left no database
rows, retained objects or incoming temporary files. Four accepted fixtures
remained byte-identical after client disconnect, disposable control-plane
restart and the real attachment-service restart; no preview runtime was
required or coupled to retained content.

The first real idempotency retry exposed nanosecond/microsecond timestamp drift
at PostgreSQL persistence. Atenea source commit
`3beee9de0f6a75434cc92175627ecd276e06fbb4` normalizes attachment creation time
before worker retention. Focused tests and the complete regression passed
before the clean accepted rerun.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-worksession-attachments/runs/task-6-automated-continuity`;
the SHA-256 of its `SHA256SUMS` is
`7e32a0efcfb1a2c9d0da5b87d3bacfedbc83c631554cd072934125bf2557caf4`.

The exact resume point is task 7.1 of `add-worksession-attachments`.

Tasks 7.1–7.5 are complete and change progress is `31/31`. Creation was
disabled twice without rebuilding the worker service. Both executions retained
the same four indexed synthetic attachments and exact downloadable content;
the repeated rollback produced empty worker-hash and authoritative-metadata
diffs, proving idempotence. A new upload failed closed with `409` and left zero
metadata or worker residue.

Cleanup first validated every recorded attachment identity, WorkSession,
SHA-256 and synthetic-fixture marker. The worker deleted exactly four objects,
the disposable database deleted exactly four matching rows and all rejected
and accepted synthetic residue is zero. Temporary control-plane and local
containers, volumes, networks, scripts and installer harnesses were removed
only after their immutable identities or exact Compose labels matched the
recorded harness.

Final fingerprints preserve the AX42 boot identity, healthy RAID `[UU]`, all
four rootless slot inventories, Beautips and the nine production/preview
containers. Production and preview remain `UP` and unchanged. The sole
intentional AX42 delta is the empty tailnet-only attachment service and retained
root introduced by Phase 5; it contains no fixture, incoming, browser or proxy
residue. Atenea source commit
`1f3598691df09f5a54dfb940519a2c36cbb60884` also retains the actionable
limit, unsupported-type and worker-unavailable controller regression coverage.

Accepted sanitized rollback/final evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-worksession-attachments/runs/task-7-rollback-final`;
the SHA-256 of its `SHA256SUMS` is
`2edf4d395c0f893a723cdead42072ec70ec465a41fdff295bf53e88c66972c74`.
The operator-render evidence remains in the accepted task 5 bundle and the
complete `356/356` regression plus real continuity evidence remains in the
accepted task 6 bundle.

Strict validation passed with all tasks checked. The attachment delta adds the
new `worksession-attachments` capability and synchronizes the scoped artifact
requirements in `private-development-preview` and `remote-work-continuity`.
The completed change is archived at
`openspec/changes/archive/2026-07-29-add-worksession-attachments`.
Accepted archive evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-worksession-attachments/runs/task-7.5-openspec-archive`;
the SHA-256 of its `SHA256SUMS` is
`db2a837afa470543f8917cadac1d4cd7ea6f7f0f2c388d91ea3f375b4ff1ffc5`.

The exact resume point is the Phase 6 entry gate for
`add-private-session-previews`.

## Phase 6 progress: add-private-session-previews

Tasks 1.1–1.4 are complete and change progress is `4/37`. The accepted entry
gate proves clean synchronized Atenea source and programme Git, unchanged nine
production/preview container identities, production and preview health `UP`,
no deployed V46/V47/V48 schema and no Phase 6 service, state, listener,
container, browser or proxy.

AX42 strict verification passes with RAID `[UU]`, rootful Docker
inactive/masked, unchanged four-slot inventory and Beautips `UP`. The Phase 5
attachment service is active and both retained/incoming stores are empty.
Atenea control, the operator laptop and Pixel 7 are online in the approved
tailnet.

The synthetic contract fixes coordinator control port `8789`, tailnet-only
ingress `19000–19031`, a renewable five-minute lease, eight-hour hard lifetime,
60-second route revocation and 30-day preview audit metadata. Localhost
forwarding requires a manifest declaration and public shares fail closed. The
proposal, design, four delta specs and task plan pass strict validation.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/entry-gate`;
the SHA-256 of its `SHA256SUMS` is
`a1a42baa8bacd14524219f9c0a25c0255d8f0f1063770ba34c3f105153d59ec9`.

The exact resume point is task 2.1 of `add-private-session-previews`.

Tasks 2.1–2.4 are complete and change progress is `8/37`. Atenea source commit
`bc32118e4e3f85d20a69af953deafc90d37cece8` adds the expand-only V48 preview
registry, immutable WorkSession/project/worker/allocation ownership, optional
same-session AgentRun validation, one-active-preview constraint, monotonic
optimistic revision and deterministic reconciliation/audit queries.

The metadata state machine enforces `STARTING`, `READY`, `RECONCILING`,
`BLOCKED`, `STOPPED` and `EXPIRED`; stale or invalid transitions mutate
nothing. Ready and renewed routes remain inside the approved tailnet/range,
five-minute lease and eight-hour hard limit. Blocked text is bounded and
sanitized, and 30-day audit identity survives stop/expiry.

The focused metadata suite passes `10/10`. A fresh disposable PostgreSQL
database validated and applied all 48 migrations, Hibernate schema validation
passed and the existing WorkSession integration suite passed `26/26`.

The exact resume point is task 3.1 of `add-private-session-previews`.

Tasks 3.1–3.6 are complete and change progress is `14/37`. Programme commit
`41e2d509286964f4dd91d2f05659f334b405fe4b` adds the authenticated
`session-preview/v1` coordinator, exact persisted projection records, bounded
tailnet ingress forwarding and manifest-derived localhost tunnel data without
credentials or runtime-port disclosure.

The coordinator is active/enabled on AX42 at tailnet-only
`100.81.98.93:8789`. UFW accepts that control endpoint only from Atenea
`100.88.252.28` and accepts ingress `19000–19031` only on `tailscale0` from
`100.64.0.0/10`. Its state store is empty and installation started no project
runtime or ingress listener. Twelve synthetic protocol tests pass, including
authentication, idempotence, stale revision, partial/foreign/ambiguous
ownership, persisted restart, lease expiry and exact cleanup.

Rootful Docker remains inactive/masked, all four rootless daemons are active,
slots 2–4 remain empty and slot 1 retains only the same three Beautips
containers. RAID remains `[UU]`; production, preview and Beautips remain `UP`.
Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-3-private-coordinator`;
the SHA-256 of its `SHA256SUMS` is
`7084d061238835f5ee234fa38a50189fe9c0cd2b364c24acd423663dc8fcbe9e`.

The exact resume point is task 4.1 of `add-private-session-previews`.

Tasks 4.1–4.5 are complete and change progress is `19/37`. Atenea source
commit `0b6a8178d52e325e9c86cddfb16d03920bba496c` adds a default-off,
finite-timeout authenticated preview client plus WorkSession-scoped activate,
status, retained history, renew, stop and declared-localhost APIs.

Atenea derives project, worker and allocation identity from the persisted
WorkSession plus the submitted runtime UUID; AX42 remains the authority that
validates the supplied allocation fingerprint against its exact persisted
record. Web and mobile share one read model that exposes the private URL only
for `READY`, bounded expiry and one primary next action, without worker or
allocation internals.

Startup and 30-second periodic reconciliation select only persisted
reconcilable records, cap each batch, renew only exact ready ownership and
never create or reassign a runtime. Twenty-three focused client, service,
persistence, reconciliation and controller tests pass, including default-off,
foreign ownership, stale identity and sanitized worker rejection paths.

No deployment occurred; production and preview remain default-off and `UP`.
Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-4-atenea-api-reconciliation`;
the SHA-256 of its `SHA256SUMS` is
`78e614b3b5a5657fabb83d1de1d493b8aef755bc5dbc0c8690d8d76a63361cfa`.

The exact resume point is task 5.1 of `add-private-session-previews`.

Tasks 5.1–5.5 are complete and change progress is `24/37`. Atenea source
commit `24ad3dcfaea8974d4f18fbd83f3df68ac4ee7182` adds one compact preview
surface immediately beneath the WorkSession header on web and Android.
`READY` alone exposes the primary `Abrir preview` action; starting and
reconciling visibly wait, while blocked, expired and stopped remain concise,
actionable and omit stale URLs. Android opens the same tailnet URL directly
and links secondarily to the existing retained-evidence surface.

The production web build, isolated secret-free Android debug build and focused
UI API regression pass. Playwright asserted all six lifecycle states and
verified the ready surface at `1440x900` and `390x844`: state and action remain
inside the first viewport, with no horizontal overflow, clipping or overlap.
The final desktop, mobile and blocked-state screenshots were visually
inspected. No deployment occurred and production/preview remain `UP`.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-5-operator-experience`;
the SHA-256 of its `SHA256SUMS` is
`915d0f6ff2828c5a1b006d0e37f5ef1eea1f34fb8e6b17812d80cf0bb259b94a`.

The exact resume point is task 6.1 of `add-private-session-previews`.

Task 6.1 is complete and change progress is `25/37`. Exact synthetic
WorkSession runtime `80b54495-88cf-4354-b1e4-aada1921644a` is
`ready/healthy` in its persisted free `slot3` allocation, with runtime
upstream bound only to `127.0.0.1:22243`. Preview
`f106453b-601a-47f3-9272-adafaa58ec7b` is `READY` at the single tailnet
projection `100.81.98.93:19000`; an exact duplicate activation returned the
same byte-identical identity and created no second listener.

The initial `slot2` allocation stopped before runtime creation because the
retained Phase 2 allocation still owns that slot. No historical record was
changed. Runtime-engine commit
`4bc325c3e7d9cc1a2ad87d78a7ef60f3f63040ed` removed the synthetic fixture's
obsolete `slot2` constant, accepts only the allocation's validated
`slot1`–`slot4` identity and adds `slot3` regression coverage. The new
session's admission was released from `slot2`, reacquired exactly in free
`slot3`, and the root-owned engine was installed with SHA-256
`48bc54324bf39086401fc7430a1b9b8048bcb6bd37e028bf8cad80e92bc4360e`.

Atenea reached the fixture over Tailscale with HTTP 200. Independent probes
from Atenea and the operator host to AX42's public address on ingress,
coordinator and runtime ports all timed out with HTTP 000. `ss` proves the
preview listener binds only the AX42 tailnet address, the runtime remains
loopback-only, unauthenticated control returned 401 and an injected public
sharing request returned 400 without changing the private route. Rootful
Docker remains inactive/masked; RAID is `[UU]`; production, preview and
Beautips retain their accepted inventories.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-6.1-synthetic-private-preview`;
the SHA-256 of its `SHA256SUMS` is
`ed59877411b6eafb5e3d1668a826a5ed8d48c3c946debe936339e028522d3147`.

The exact resume point is task 6.2 of `add-private-session-previews`.

Task 6.2 is complete and change progress is `26/37`. The operator laptop and
an independent Atenea private client both resolved preview
`f106453b-601a-47f3-9272-adafaa58ec7b` as `READY` for WorkSession `96061`
and reached the same tailnet URL. Their live response bodies are byte-identical
with SHA-256
`54c244c22440ed1f09203f79bb0d45387b8ddc543146fb87a736bf7f6572e4d6`.

The Pixel 7 Android private peer is online on the approved tailnet and answered
a finite peer probe in 126 ms. It exposes no ADB transport, so no automated
physical-device browser claim is made; the accepted `Android/private-client`
case uses the independent private client while the previously accepted Android
build/read model remains unchanged.

An authenticated inspect using foreign WorkSession `96062` returned
`ownership_conflict`. The coordinator record was byte-identical before and
after that denial at SHA-256
`27823e1a510cd5fdf7202d466adae0c99ea48940c56184bf3c8ef5f29526ebb1`,
and the coordinator still contains exactly one preview record. No foreign
WorkSession was resolved or changed.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-6.2-private-clients`;
the SHA-256 of its `SHA256SUMS` is
`9d87f73687a8054b02c3cec8cf6ccf30012a0e516efb5c51b3291e1bed27a8aa`.

The exact resume point is task 6.3 of `add-private-session-previews`.

Task 6.3 is complete and change progress is `27/37`. The manifest-declared
localhost case returned only credential-free SSH destination
`codex-worker`, tailnet ingress `100.81.98.93:19000` and path `/`; it
explicitly reports `runtimePortExposed=false` and never discloses upstream
port `22243`.

One bounded key-authenticated SSH forward bound
`127.0.0.1:39061` to the exact preview ingress. The localhost response and
the direct tailnet response are byte-identical at SHA-256
`54c244c22440ed1f09203f79bb0d45387b8ddc543146fb87a736bf7f6572e4d6`.
The listener never bound `0.0.0.0` or the operator's tailnet address, and a
non-loopback probe failed without content. The recorded SSH PID was terminated
and awaited; the localhost listener is absent after cleanup while the private
preview remains ready.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-6.3-localhost-tunnel`;
the SHA-256 of its `SHA256SUMS` is
`71afeff381ff56b83c20d24ef4c7b75226420fa4dc62ebbec06ed457e19c2f8a`.

The exact resume point is task 6.4 of `add-private-session-previews`.

Task 6.4 is complete and change progress is `28/37`. A single exact-labelled
Playwright 1.60.0 container ran on the synthetic runtime network with no
published port, all capabilities dropped, read-only root and finite timeouts.
It asserted HTTP success, visible body text and the expected fixture identity
at `1440x900` and `390x844`. Both records report `textLength=66`, no clipping
and no horizontal overflow. The inspected desktop and mobile screenshots show
all content visibly within their viewports; the narrow rendering wraps without
overflow.

The first browser attempt stopped before navigation because the read-only
container lacked a writable Playwright `/tmp`. Its exact diagnostic container
was removed. The accepted run uses a bounded noexec/nosuid tmpfs, completed in
1968 ms, closed pages, contexts and Chromium in `finally`, and left zero
browser containers or browser processes.

Desktop attachment `905681df-c014-47f0-9e0c-01f59c3d1eae` and mobile
attachment `5639d847-445b-441d-8a33-70037709bc53` were accepted through the
authenticated AX42 attachment boundary as `BROWSER_SCREENSHOT/IMAGE` evidence.
Their downloaded SHA-256 values exactly match the Playwright registry:
`9c52eccafaf54635063809ac3a4deabf788e821b5fecf33e7f785ae308282f26`
and
`e5f912bcdf5e695733df61f91cf24513d85856ae83ba398e3ee568073c27c6f5`.

The isolated non-production `atenea_test` database has all 48 Flyway rows and
indexes both attachments under exact WorkSession `96061`, project `9606`,
AgentRun `9606101`, worker `ax42-01` and preview
`f106453b-601a-47f3-9272-adafaa58ec7b`. The transactional index took 76 ms.
The physical Android device exposes no ADB transport; no device-browser claim
is included in this browser acceptance.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-6.4-playwright-attachments`;
the SHA-256 of its `SHA256SUMS` is
`af7b6b18ff1f325bb2d66ecd2d160fa9fe832d240a43d6283c5ebfaac351f9b3`.

The exact resume point is task 6.5 of `add-private-session-previews`.

Task 6.5 is complete and change progress is `29/37`. The exact preview lease
expired at `2026-07-29T02:20:01.698489Z`; the coordinator persisted
`EXPIRED` and removed its listener at
`2026-07-29T02:20:02.591403Z`, 0.893 seconds later. The tailnet route now
returns HTTP 000 while the separately owned runtime remains healthy and
loopback-only on `127.0.0.1:22243`.

Both attachment metadata records and both contents remain retrievable through
the authenticated boundary after route teardown. Their SHA-256 values remain
exactly
`9c52eccafaf54635063809ac3a4deabf788e821b5fecf33e7f785ae308282f26`
and
`e5f912bcdf5e695733df61f91cf24513d85856ae83ba398e3ee568073c27c6f5`;
the isolated Atenea index still binds both to WorkSession `96061` and AgentRun
`9606101`.

After those retained copies were reverified, only the exact temporary browser
scratch was deleted. There are zero preview-labelled browser containers, zero
Chromium/Playwright processes and no local-forward listener. The runtime,
allocation, worktree, Git, production, preview, Beautips and RAID state remain
unchanged.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-6.5-preview-teardown-retention`;
the SHA-256 of its `SHA256SUMS` is
`57a1de69afa5beb591e4145efc4f004f4154f129452af2b22c0167a415aabd66`.

The exact resume point is task 6.6 of `add-private-session-previews`.

Task 6.6 is complete and change progress is `30/37`. Preview
`05aa7e6e-f4a7-4621-aeda-248e491eeee6` was activated for the same exact
WorkSession, project, worker and allocation while the prior preview remained
terminal. Restarting only the AX42 preview coordinator changed its PID and
restored the same persisted unexpired route in 790 ms. Preview identity,
revision, ingress, upstream, lease and hard lifetime remained exact; the
runtime container and retained attachment hashes were byte-identical. The
prior expired record was byte-identical and was not restored.

An independent disposable Atenea acceptance database applied all 48 Flyway
migrations and retained the exact synthetic WorkSession, AgentRun, two
attachments, one expired preview and one unexpired ready preview. Two separate
Atenea startups reconciled only the ready row through authenticated finite
requests; the AX42 journal contains four successful exact-ownership inspections
and no request for the expired preview. Database state was unchanged across
both startups, no runtime was created or reassigned, and each application
process stopped cleanly without a remaining listener.

The worker credential was streamed only into an anonymous in-memory file
descriptor and was neither printed nor written to a filesystem. The final
credential helper inventory is empty. Production, preview, Beautips, rootful
Docker, RAID, runtime allocation and canonical Git state remain unchanged.
The preview subsequently reached its recorded lease expiry normally; that
post-window terminal state is retained separately and is not used to claim
that the accepted reconciliation remained ready indefinitely.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-6.6-restart-reconciliation`;
the SHA-256 of its `SHA256SUMS` is
`07836ce14b406d6fdb27d5b90653de02213b62b0ea91c94db6b0e234d1f21ea9`.

The exact resume point is task 6.7 of `add-private-session-previews`.

Task 6.7 is complete and change progress is `31/37`. Preview
`05aa7e6e-f4a7-4621-aeda-248e491eeee6` stopped receiving renewals and
reached its persisted lease at `2026-07-29T02:37:20.908708Z`. The coordinator
persisted `EXPIRED` and removed its route 0.881 seconds later, well inside the
60-second bound.

A separate exact synthetic preview,
`62f6ac6d-3248-48b0-9e16-710775a28a7d`, became `READY` revision 2 at the
same private ingress and returned HTTP 200 to Atenea. Its exact authenticated
stop completed in 49 ms as `STOPPED` revision 3 with no private URL. The
ingress listener was absent immediately after the response, and an independent
Atenea route probe failed closed with exit 7 and HTTP 000 in 107 ms.

Before and after fingerprints for the persisted allocation, runtime container,
worktree HEAD/tree/status and both attachment metadata/content pairs are
byte-identical. The runtime remains healthy and loopback-only at
`127.0.0.1:22243`; both preview services remain active and no browser process
exists. Production, preview, Beautips, rootful Docker, RAID and firewall state
remain unchanged.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-6.7-expiry-hard-stop`;
the SHA-256 of its `SHA256SUMS` is
`8112ff5559db5221ba4fefc06097f9c4b9395cf7addf941968bce710eed644ef`.

The exact resume point is task 6.8 of `add-private-session-previews`.

Task 6.8 is complete and change progress is `32/37`. The first canonical
Atenea run executed 379 tests with zero failures and 27 setup errors, all from
the same foreign-key guard: the exact Phase 6 attachment index created for
task 6.4 still referenced synthetic AgentRun `9606101` in the shared test
database. Immutable ownership checks resolved only the two attachment rows,
one preview row, AgentRun, internal turn, WorkSession and project created by
this acceptance. Their exact transactional removal left all seven fixture
counts at zero; physical AX42 attachments and the independent continuity
database were not changed.

The repeated canonical regression passed all 379 tests with zero failures,
errors or skips in 31.683 seconds. The programme-source worker regressions also
passed: preview protocol 12/12, attachment protocol 11/11, runtime engine, and
the complete project-runtime contract 8/8. The preview and attachment protocol
suites were independently repeated on AX42 and passed 12/12 and 11/11.

The installed coordinator verification passed service, listener, permission,
firewall and systemd-hardening checks using the persisted Atenea control-plane
identity. A preceding verification deliberately made no change and rejected
the operator-host address because it did not match that exact firewall rule.
All temporary suite directories are absent. Canonical Git trees are clean,
the preview ingress remains absent, the runtime remains loopback-only, and
production, preview, Beautips, RAID, firewall and rootful Docker remain
unchanged.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-6.8-regression-suites`;
the SHA-256 of its `SHA256SUMS` is
`f839c16318ef16e1a846f33f7d124bb95b6df0e3fd6c5435847e902cc9e4f0ef`.

The exact resume point is task 7.1 of `add-private-session-previews`.

Task 7.1 is complete and change progress is `33/37`. The canonical AX42
preview coordinator is disabled and inactive; its control and ingress
listeners are absent, while the separately owned synthetic runtime remains
healthy and loopback-only on `127.0.0.1:22243`. The three terminal preview
records, both preview firewall rules and installed worker program remained
byte-identical for the exact rollback acceptance in task 7.2.

Both retained attachment metadata documents and both PNG contents remained
readable through the authenticated attachment boundary. Their content hashes,
the two preview audit rows and the two independent attachment indexes were
unchanged. The disabled control endpoint fails closed from Atenea, while
production, preview, Beautips, RAID, rootful Docker, allocation, worktree and
Git fingerprints remain unchanged.

A retained `READY` read-model regression was found and closed before
acceptance: with the capability disabled, Atenea now retains the state and
audit copy but suppresses the private URL and returns primary action `NONE`.
Web and Android also require server-derived `OPEN` before exposing an open
action. Source commit `b605c8d5b063e7321edd60fec2265ec7ddb84ea9` is pushed.
Eight focused backend tests, the web build, Android core-console compile and
the complete final Atenea regression (`380/380`) pass.

Playwright used a disposable loopback-only Atenea instance and the real
preview read boundary. At `1440x900` and `390x844`, it proved the retained
state and disabled copy visible, zero open actions and zero horizontal
overflow. Both screenshots were inspected, the browser/application processes
were closed and all exact temporary authentication rows were removed.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-7.1-disable-affordances`;
the SHA-256 of its `SHA256SUMS` is
`f47c42242bcca2d482f0df879a455ddfbf9471483fc4b79cf1a0e52e52509e90`.

The exact resume point is task 7.2 of `add-private-session-previews`.

Task 7.2 is complete and change progress is `34/37`. The first exact AX42
rollback exited 0 in 547 ms and changed only the two Phase 6 UFW entries: the
control rule on `8789/tcp` from Atenea and the bounded tailnet ingress rule on
`19000–19031/tcp`. The coordinator remained disabled/inactive and no
coordinator or ingress process was started.

The identical rollback was repeated with the same immutable control-plane
identity and finite timeout. It exited 0 in 264 ms; complete worker
fingerprints after the first and second executions are byte-identical.
All three terminal preview record hashes, the runtime allocation/admission,
runtime container, worktree Git, attachment service, rootless inventories,
Beautips, RAID and every non-preview firewall rule remained unchanged.
Production and preview retained the same nine container identities and both
health probes returned HTTP 302.

The coordinator regression now includes an explicit unlabelled preview-like
candidate and proves its directory and payload hash remain unchanged after
rejection. Together with the existing partial-record, foreign non-synthetic
record and ambiguous allocation cases, it proves fail-closed preservation for
all four required classes. The accepted suite passes `13/13` locally and
`13/13` on AX42 from isolated temporary directories. No installed coordinator,
listener or projection was created, all suite directories are absent and only
the exact three-file staging directory was removed.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-7.2-idempotent-rollback-rejection`;
the SHA-256 of its `SHA256SUMS` is
`8a7d51fbcf435a8cbd67b5e72978b65fc8160ffc9495810f1413c67c98e50f20`.

The exact resume point is task 7.3 of `add-private-session-previews`.

Task 7.3 is complete and change progress is `35/37`. Before mutation, all
three remaining terminal worker projections were resolved by immutable preview
UUID, WorkSession `96061`, project, worker, runtime UUID, allocation
identity/fingerprint, ingress port, lifecycle revision, terminal state,
synthetic marker and exact record SHA-256.

With the systemd coordinator still disabled/inactive, the installed
coordinator's exact synthetic-delete contract removed only those three
validated records in 52 ms. The worker preview state root now has zero records
and zero child entries, with no control/ingress listener or coordinator
process. Complete before/after worker diff contains no other change.

The first projection-cleanup pass preserved the runtime because the design
treats runtime, worktree and Git as separate resources. Before archive, the
common Phase 6 rollback contract was rechecked and its explicit requirement to
stop the synthetic preview runtime was correctly treated as the separate
teardown authorization required by that design.

The installed mediated runtime client then stopped the exact slot3 runtime as
`stopped/stopped` in 616 ms. Only after validating immutable container,
network and image IDs, their complete engine/session/runtime labels, the
allocation hash, stopped state, engine owner marker and held admission record,
the correction removed container `f08f9993b621…`, network `9fd22daf1cb5…`,
image `sha256:b73b260ae26b…`, the owner-marked engine temporary root and its
regular lock. Cleanup exited 0 in 670 ms and released only this WorkSession's
slot3 admission.

There are now zero session-owned containers, networks, images, volumes,
runtime/preview listeners, browser processes and preview processes. The
allocation record, released admission record, worktree HEAD/status, bare
mirror HEAD/fsck, Git, logs, artifacts and both attachments remain. Beautips,
RAID `[UU]`, base services, rootful Docker `inactive/masked`, every non-preview
firewall rule and Atenea production/preview remain unchanged.

The independent continuity database remains byte-identical with two preview
audit rows, two attachment indexes and one synthetic AgentRun, proving worker
projection cleanup did not down-migrate retained history. Atenea production
and preview retain the same nine container identities and healthy probes.
Only the two exact task staging files were removed after verification.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-7.3-exact-projection-cleanup`;
the SHA-256 of its `SHA256SUMS` is
`cf6edafe395f173e561520652278c8b65150294e3c5403f73257d6aff2153c24`.

Accepted supplemental teardown evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-7.3b-runtime-teardown-correction`;
the SHA-256 of its `SHA256SUMS` is
`ccfcd5612968eb43aa50549a5f1197447ff5513beb7bc750f49a52f2f65f4903`.

The exact resume point is task 7.4 of `add-private-session-previews`.

Task 7.4 is complete and change progress is `36/37`. The Phase 6 chain of
custody now indexes and independently verifies 15 artifact sets across their
owning hosts: entry gate and tasks 3–5 on Atenea, then tasks 6.1–7.3 on AX42.
Every internal `sha256sum -c SHA256SUMS` passes and every outer hash matches
the immutable value in this ledger.

Fifteen accepted PNGs remain indexed by exact SHA-256 across the operator UI,
private Playwright attachment and disabled-affordance evidence, including
desktop and mobile viewports. Command ledgers retain exit codes, finite
timeouts and durations. Bounded filename-only sanitization audits on both
artifact roots found zero credential-bearing patterns and zero forbidden auth,
cookie or credential filenames.

After task 7.3 sealed its retained database counts, the independent local
continuity database was resolved as disposable acceptance infrastructure by
its pre-recorded container and anonymous-volume identities. Only container
`2a18dabc20cd1716106e2ec82c08829ecdc879d239f4b11f28cfe88f8b055c1c`
and volume
`bf9b660be492ab5eda170dc449a8ec887e79b7894faa4de51cdbcf16352923b8`
were stopped and removed, exiting 0 in 475 ms; both are absent. This does not
down-migrate authoritative history, and the sealed task 7.3 evidence retains
the observed audit, attachment-index and AgentRun counts.

The supplemental task 7.3 teardown supersedes the rollup's interim live-runtime
line. Final state retains zero AX42 preview records, session-owned containers,
networks, images, volumes, runtime/preview listeners, browser/preview processes
and preview firewall rules; three unchanged Beautips containers, RAID `[UU]`,
rootful Docker `inactive/masked`, nine unchanged Atenea production/preview
containers and successful health probes remain.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-7.4-evidence-rollup`;
the SHA-256 of its `SHA256SUMS` is
`1c1b57d6a4f828e569e388b52c2439af02bde20ed91f7984bb2b1f4192563e28`.

The exact resume point is task 7.5 of `add-private-session-previews`.

Task 7.5 is complete and Phase 6 progress is `37/37`. Pre-archive strict
validation accepted the complete change. OpenSpec applied three added and
eight modified requirements across `isolated-project-runtime`,
`private-development-preview`, `worker-operational-safety` and
`worksession-attachments`, then archived the change as
`2026-07-29-add-private-session-previews`.

Post-archive strict validation passes all eight authoritative specifications
with zero failures and OpenSpec reports no active changes. Atenea source is
clean and synchronized at
`b605c8d5b063e7321edd60fec2265ec7ddb84ea9`; the programme branch is clean
and synchronized after the archive commit.

Phase 6 closes with the capability disabled, zero preview records/routes,
zero session-owned containers, networks, images or volumes, zero runtime or
preview listeners, released slot3 admission, preserved allocation, worktree,
mirror, Git, logs, artifacts and attachments, unchanged production and
Beautips, RAID `[UU]` and rootful Docker `inactive/masked`.

The exact resume point is the Phase 7 entry gate for
`establish-development-database-lifecycle`. No Phase 7 implementation or
authoritative development database operation has been executed yet.

Accepted final archive evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-private-session-previews/runs/task-7.5-archive`;
the SHA-256 of its `SHA256SUMS` is
`a86cc97f7847efe832b2d72aece0231341eccb73c708a06eebe2753f6b132bcb`.

## Phase 7 progress: establish-development-database-lifecycle

Tasks 1.1–1.5 are complete and change progress is `5/37`. The entry gate
proves clean synchronized programme and Atenea source Git, archived Phase 6,
strictly valid authoritative specs, unchanged nine-container Atenea
production/preview inventory and healthy public probes.

AX42 has RAID `[UU]`, 419826200576 bytes available, rootful Docker
inactive/masked, all four rootless engines and proxies healthy, unchanged
three-container Beautips in slot1, the foreign retained Phase 3 volumes in
slot2 and empty slots 3–4. Phase 6 left zero preview state, listener or
session-owned runtime resource.

Atenea production/preview PostgreSQL, Beautips and the retained Phase 3 Atenea
volumes are classified out of scope and were not read, mounted, started,
labelled, adopted or changed. Phase 7 accepts only two new deterministic
synthetic fixtures: pinned PostgreSQL and MariaDB with versioned migration/seed
rows and no production-derived data.

The approved contract uses named ephemeral secret files, private
integrity-addressed snapshots capped at three copies/seven days, sanitized
reports without raw dumps, a one-use five-minute revision-bound replacement
challenge and a verified pre-replacement snapshot. Authoritative activation
remains blocked until independent external backup passes restore.

The proposal, design, new `development-database-lifecycle` capability, three
modified capability deltas and 37-task plan pass strict OpenSpec validation.
Accepted sanitized entry evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/entry-gate`;
the SHA-256 of its `SHA256SUMS` is
`2acf24c1ac3a39b1dec979eea43ddcc50b87dffd8cd8b5a2a27baf65587b033a`.

The exact resume point is task 2.1 of
`establish-development-database-lifecycle`.

Tasks 2.1–2.5 are complete and change progress is `10/37`. The runtime
manifest now has one optional closed database contract accepting only pinned
PostgreSQL or MariaDB images, exact synthetic-development classification,
relative deterministic migration/seed inputs, one declared database port,
one required named database secret and fixed health, snapshot, retention and
explicit-replacement policies.

The two accepted fixtures contain only versioned generated schema and one seed
row each. A dependency-free state layer persists canonical database/
WorkSession/project/worker/allocation/slot/manifest ownership, derives
container/network/volume identities and writes strictly validated atomic
mode-0600 records. Lifecycle revisions are monotonic and idempotent.
Replacement challenges store only a SHA-256, bind to one revision, expire
after five minutes and are consumable once.

Private snapshot metadata binds exact ownership, lifecycle revision, byte
count and SHA-256. Retention selects but does not delete only exact synthetic
snapshots older than seven days or beyond three copies; foreign metadata fails
closed unchanged.

The focused state/schema suite passes `13/13` locally and `13/13` on AX42.
The complete project-runtime contract passes `8/8`. All isolated staging and
state-test directories are absent; no worker component was installed and no
runtime, volume, service, firewall, Beautips or production resource changed.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-2-manifest-state-contract`;
the SHA-256 of its `SHA256SUMS` is
`883533db49f7e1ffb98c088f053f838e935186dfe413b158fdf725b6088b2a47`.

The exact resume point is task 3.1 of
`establish-development-database-lifecycle`.

Tasks 3.1–3.6 are complete and change progress is `16/37`. AX42 now has a
root-owned fixed-operation database mediator, immutable state module, narrow
`atenea-worker` client and one exact sudoers delegation. The installation is
deliberately disabled: the enable marker is absent, reconciliation reports
zero records and performs no implicit create or start, and there is no
service, host listener, published database port or firewall rule.

The mediator accepts only `register`, `create`, `migrate`, `seed`, `health`,
`status`, `snapshot`, `prepare-replace`, `replace`, `restore`, `stop`,
`cleanup`, `retain`, `reconcile` and `verify`. It derives the rootless slot,
container, internal-only network, volume, image, endpoint and private snapshot
path from the exact allocation, manifest and persisted database ownership.
Caller endpoints, literal credentials, arbitrary Docker arguments,
production-like manifests and partial/foreign/ambiguous resources have no
accepted command surface and fail before resource mutation.

PostgreSQL uses custom-format `pg_dump`/transactional `pg_restore`; MariaDB
uses a single-transaction engine dump and fixed client restore. Replacement
first consumes a one-use five-minute revision-bound confirmation, then creates
and verifies an engine-native pre-replacement snapshot before deleting the
complete exact projection. Secret values exist only in a mode-0600 ephemeral
file owned by the admitted rootless slot user; outputs and evidence contain no
value.

The focused state suite passes `13/13`, the mediated worker suite passes
`9/9`, and the expanded project-runtime integration suite passes `10/10`
locally and on AX42. Repeated installation is idempotent and remains disabled.
The four rootless inventories still match entry: Beautips remains the only
slot1 workload, the two retained Phase 3 volumes remain in slot2, and slots
3–4 have no project resources. There are zero database lifecycle containers,
networks or volumes in every slot. Atenea is clean and synchronized at
`b605c8d5b063e7321edd60fec2265ec7ddb84ea9` with all nine production/preview
containers running; RAID remains `[UU]` and rootful Docker remains
inactive/masked.

The first AX42 integration invocation inherited an inaccessible administrative
working directory and stopped before mutation; repeating from `/tmp` passed.
The first install also exposed inherited setgid mode `2700` on the new state
root and stopped while disabled; the installer now normalizes both private
roots to `0700`, and its exact idempotent repetition passes. These corrections
did not create a database record or Docker resource.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-3d-final-accepted`;
the SHA-256 of its `SHA256SUMS` is
`71692f364c6844745b698607e9441fe9cf4bad8626baaa5d26712b4a07613e25`.
Earlier task-3, task-3b and task-3c runs are retained as superseded audit
history; the accepted task-3d run proves the final warning-free binary at
SHA-256 `785780ba9a29310f884300aecb4ec274bc9c72cdb196f7f7506550b42dc8d216`.

The exact resume point is task 4.1 of
`establish-development-database-lifecycle`.

Task 4.1 is complete and change progress is `17/37`. PostgreSQL owns
WorkSession `1e452a4a-8b06-40a6-837e-952bcaa74c7e`, database
`925bce0d-7662-4e15-97d1-13f7e1f97a5a` and slot3. Its canonical mirror,
session worktree, immutable allocation and admission record are persisted.
The worktree is clean at programme commit
`102057745733de264b335a1ae77a0b6c3268c54d`.

Slot3 admission was free, but the archived Phase 6 allocation record still
claimed the slot. Before reuse, its sealed SHA-256
`58b77d11384d79fd50a88fc5d3052048337859e9fd97eac1b027ba7ed5203672`,
released admission and zero exact resources were re-proven. The byte-exact
record was moved into task 4.1 evidence; its worktree, mirror, Git, logs and
artifacts remain in place. No foreign allocation or resource changed.

The first database create exposed that the rootless daemon cannot bind a
secret from host-global `/run`, even though the slot user can read it. The
attempt created no container and its exact new network/volume were removed by
the mediator. The corrected boundary uses the slot's own XDG runtime tmpfs,
`/run/user/1103`, with a mode-0600 file owned only by `atenea-slot3`. No
secret value appears in output, process arguments or evidence.

The final idempotent create persists state `CREATED`, revision `2`, one
completely labelled container, one internal-only network and one named volume.
There are no published host ports, database listeners or firewall rules.
Beautips remains three running containers, Atenea remains clean/synchronized
with nine running production/preview containers, RAID has three `[UU]` arrays
and rootful Docker remains inactive.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-4.1-postgresql-create`;
the SHA-256 of its `SHA256SUMS` is
`4d5e9f55072401ba973c23bd4b99ccf3c8e33ca44dd49eac1d6ebe1cfdf62158`.
It also supersedes the task-3d installed-worker hash with the XDG-runtime
correction, SHA-256
`07e250df652120bd3a3d6a07e0b28f2d8dff12e1aafcd5cf1fe79f9690366c01`.

The exact resume point is task 4.2 of
`establish-development-database-lifecycle`.

Task 4.2 is complete and change progress is `18/37`. The fixed PostgreSQL
migration advanced revision `2 -> 3`, the fixed seed advanced `3 -> 4`, and
the fixed `select-one` health acceptance advanced `4 -> 5`. The resulting
synthetic identity is one row and four declared columns; evidence retains only
their counts and SHA-256-like MD5 comparison digests, never raw row content.

Late retries of migrate, seed and health now return the existing `HEALTHY`
revision `5` without re-executing SQL or changing state. The initial mediator
had used the stable rootless proxy for `docker exec`; real acceptance showed
that proxy carries normal Docker calls and stdin but drops hijacked stdout.
The corrected mediator validates the persisted slot user's real Unix socket
type and owner, then executes as that user against
`/run/user/<slot-uid>/docker.sock`. It still cannot select rootful Docker or a
caller-provided/foreign slot.

The accepted resource has an internal-only network, no published port and no
listener on allocated loopback port `24752`; a finite connection attempt is
denied. Slot4 cannot inspect or enumerate any container, network or volume for
the PostgreSQL WorkSession. Atenea production/preview remains nine running
containers, Beautips remains three, RAID remains `[UU]` and rootful Docker
remains inactive.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-4.2-postgresql-migrate-seed-health`;
the SHA-256 of its `SHA256SUMS` is
`d6672c69e49cdf11cae44974c4d6fbb4c585f0a8485ec13aab95d24b1948755f`.
The accepted mediator SHA-256 is now
`7ad5e07c7b0507a4032629c1db86102f2f8e6bebf62a0bd982ae0f761f4250e5`.

The exact resume point is task 4.3 of
`establish-development-database-lifecycle`.

Task 4.3 is complete and change progress is `19/37`. An explicit
custom-format PostgreSQL snapshot was stored privately at revision `5`, then
one replacement challenge advanced the bound revision to `6`. Its value lived
only in memory, expired after five minutes by contract and is absent from
evidence; persisted audit retains only its SHA-256, operation UUID
`276ea038-61ad-4058-9cad-dcd1f039b45e`, bound revision and consumed state.

Confirmed replacement created and verified a second engine-native snapshot
before removing any exact database resource. It then replaced only the
session-labelled container/network/volume projection, reapplied the fixed
migration and seed, and returned `HEALTHY` at revision `13`. The container
immutable ID changed while the persisted resource names, allocation, slot,
project, WorkSession and database identities remained constant. The
deterministic row count/digest still matches.

Both raw snapshots remain mode-0600 beneath the private snapshot root. Their
byte counts and SHA-256 values match immutable metadata; neither dump nor a raw
row is attached. The accepted initial interactive run retained fixed
timeouts/exit codes but not per-command duration, so task 4.5 will repeat the
whole lifecycle with a duration-bearing harness.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-4.3-postgresql-confirmed-replacement`;
the SHA-256 of its `SHA256SUMS` is
`6f42070eba6b6490d0d6eb1c3cd8f9a2dc7c48426458e1d4e7aef4e47cb2ddd2`.

The exact resume point is task 4.4 of
`establish-development-database-lifecycle`.

Task 4.4 is complete and change progress is `20/37`. The automatic
pre-replacement snapshot `c0417c78-8b0b-4669-bccd-d83cd6a7057a` was
re-verified by size, SHA-256, database, WorkSession, engine, allocation and
synthetic ownership before restore.

Fixed `pg_restore --clean --if-exists --single-transaction` advanced
`HEALTHY 13 -> RESTORING 14 -> HEALTHY 15`. The restored row-count/content
digest exactly matches the pre-replacement digest. Restore changed neither the
container immutable ID nor the worktree: HEAD remains
`102057745733de264b335a1ae77a0b6c3268c54d`, tree remains
`6cb26b9cce81496ca5e02e8ea0a7d1ce5e04b1b4`, status is clean and `git fsck`
passes. No raw dump or row entered evidence.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-4.4-postgresql-restore`;
the SHA-256 of its `SHA256SUMS` is
`c6b624c7cb138ae29a6f60b68309a0b93d15fc18c1c728ece84f9af824780ff2`.

The exact resume point is task 4.5 of
`establish-development-database-lifecycle`.

Task 4.5 is complete, the PostgreSQL section is `5/5`, and total change
progress is `21/37`. A duration-bearing harness repeated the complete
lifecycle from revision `15`: exact stop/cleanup, create plus duplicate
create, migration, seed, health, explicit snapshot, prepare/confirmed replace,
restore and late migration/seed retries.

Exact cleanup removed only the PostgreSQL session's container, internal
network and volume. Recreate regenerated the same persisted names with a new
container identity. The duplicate create retained revision `17`; late
migrate/seed retries retained final revision `30`. Confirmed replacement
created and verified another pre-snapshot, returned healthy revision `28`,
and restore of the explicit snapshot returned healthy revision `30`.

All thirteen measured operations exited `0` within their finite timeouts.
Observed durations ranged from 52 ms to 3982 ms. Final data fingerprint equals
the original/restored fingerprint, Git is byte-identical and clean, snapshots
remain private, and no confirmation/secret/raw row/raw dump entered evidence.
Atenea production/preview remains nine running containers, Beautips remains
three, RAID remains `[UU]` and rootful Docker remains inactive.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-4.5-postgresql-repeat`;
the SHA-256 of its `SHA256SUMS` is
`3497a6da2a888283633c99172ffa07dba702cf273a347a43b37c695788617cd2`.

The exact resume point is task 5.1 of
`establish-development-database-lifecycle`.

Task 5.1 is complete and change progress is `22/37`. MariaDB owns separate
WorkSession `0fd2c888-07f0-4a47-a762-0eae444a166a`, database
`7b15eb56-86a7-465b-bc28-f00e47b57068` and slot4. Its independent mirror,
clean worktree, allocation and held admission are persisted at programme
commit `1e0ac9e42051cac6b768f09de8ad65507fd09791`.

The idempotent create persisted `CREATED` revision `2`, one exact labelled
container, one internal-only network and one named volume. Its secret is a
mode-0600 file owned by `atenea-slot4` beneath that slot's XDG runtime tmpfs.
There is no host publication, loopback listener or firewall rule. The
PostgreSQL WorkSession remains independently `HEALTHY` in slot3, Beautips
remains three running containers and RAID remains `[UU]`.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-5.1-mariadb-create`;
the SHA-256 of its `SHA256SUMS` is
`83e182e3c4c011fc55d089e4e9587f04eee82277171f8e46fb379961a450afff`.

The exact resume point is task 5.2 of
`establish-development-database-lifecycle`.

Task 5.2 is complete and change progress is `23/37`. The first MariaDB
migration stopped safely in `CREATED` after its 90-second bounded health wait.
Read-only diagnosis proved the server and named-file authentication healthy;
the fixed client argv had supplied the database once as `$1` and again inside
`$@`. No SQL or lifecycle revision changed during that failed attempt.

Both engine clients now bind `database="$1"` and `shift` before passing the
remaining fixed arguments. The mediated worker suite passes `10/10`, and the
installed worker SHA-256 is
`e45142209c1d0a24640f6d13ee2c7b9d56891efa36f0aa1365d24085a1272473`.
MariaDB migration, seed and health then advanced revisions `2 -> 5`; late
retries retain `HEALTHY` revision `5` without executing SQL again.

Evidence retains only one-row content digest and four-column schema digest.
The network is internal, no listener exists on allocation port `26853`, a
finite loopback connection fails, and isolated slot3 cannot inspect or list
any slot4 MariaDB resource. No raw row or credential was retained.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-5.2-mariadb-migrate-seed-health`;
the SHA-256 of its `SHA256SUMS` is
`d457d257195f94e162ba12f03fe1ec48b3c3f17a7e302ee3ab287a2817340ea1`.

The exact resume point is task 5.3 of
`establish-development-database-lifecycle`.

Task 5.3 is complete and change progress is `24/37`. One explicit MariaDB
single-transaction snapshot and one automatic verified pre-replacement
snapshot match their private byte counts, SHA-256 values and exact immutable
ownership metadata.

Operation `ed436137-65ac-43f7-a9ea-cf0622815a6c` consumed its five-minute
revision-6 challenge without retaining the confirmation. Replacement changed
the container immutable ID, preserved all persisted resource/WorkSession/
project/allocation/slot names, reapplied migration/seed/health and returned
`HEALTHY` revision `13`. The deterministic data digest still matches, and no
raw dump or row is attached.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-5.3-mariadb-confirmed-replacement`;
the SHA-256 of its `SHA256SUMS` is
`56501214400d19a216959252e89344bc9ae000345a7b69ef12a237bd0d56bef6`.

The exact resume point is task 5.4 of
`establish-development-database-lifecycle`.

Task 5.4 is complete and change progress is `25/37`. The verified automatic
snapshot was imported first into a derived staging database. Only after the
import succeeded, one MariaDB `RENAME TABLE` statement atomically exchanged
`phase7_items`; the staging and backup databases were then removed.

Restore advanced `HEALTHY 13 -> RESTORING 14 -> HEALTHY 15`. Zero derived
restore/backup databases remain, the data digest matches pre-replacement, the
container immutable ID is unchanged, and MariaDB worktree HEAD
`1e0ac9e42051cac6b768f09de8ad65507fd09791`, tree and clean status are
unchanged with passing `git fsck`. No raw dump or row entered evidence.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-5.4-mariadb-restore`;
the SHA-256 of its `SHA256SUMS` is
`f432db394e1410dc00390537f3c24eb3b81a812a3437fac5e4ea9b3e2d6cbf55`.

The exact resume point is task 5.5 of
`establish-development-database-lifecycle`.

Task 5.5 is complete, the MariaDB section is `5/5`, and total change progress
is `26/37`. The duration-bearing second cycle repeated exact stop/cleanup,
create plus duplicate create, migration, seed, health, explicit snapshot,
prepare/confirmed replace, atomic restore and late migration/seed retries.

All thirteen operations exited `0` within finite timeouts; durations ranged
from 50 ms to 9268 ms. Cleanup removed only the MariaDB session's exact
container, internal network and volume. Final state is `HEALTHY` revision
`30`, duplicate/late retries changed no revision, data digest and Git match,
and the confirmed replacement/restore retained no confirmation, raw dump,
raw row or secret.

PostgreSQL remains independently `HEALTHY` in slot3. Atenea production/preview
remains nine running containers, Beautips remains three, RAID remains `[UU]`
and rootful Docker remains inactive.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-5.5-mariadb-repeat`;
the SHA-256 of its `SHA256SUMS` is
`fb976c12b179c520d9e4d238d8c7b2eb131d3991adc476619fde117cc9f8e77b`.

The exact resume point is task 6.1 of
`establish-development-database-lifecycle`.

Task 6.1 is complete and change progress is `27/37`. Cross-slot Docker inspect,
DNS resolution and labelled resource enumeration all fail in both directions.
Neither database container contains the mediator client, sudo, any Docker
socket, worker state root or private snapshot root, so a WorkSession runtime
has no authority to request snapshot, replace, restore or cleanup.

A complete fingerprint of both records, all snapshot metadata/content and both
live resource identities/labels is byte-identical before and after the denial
attempts. The trusted global `atenea-worker` mediator remains the only caller
and still requires exact persisted database ownership before an operation.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-6.1-cross-session-isolation`;
the SHA-256 of its `SHA256SUMS` is
`2c68ff9dfadc270ca2184f93b5a974b4574f8099e4a93fba6e6f9f71eec3c70a`.

The exact resume point is task 6.2 of
`establish-development-database-lifecycle`.

Task 6.2 is complete and change progress is `28/37`. Against PostgreSQL
revision `30`, missing confirmation arguments, stale revision `29` and an
incorrect confirmation were rejected with unchanged complete fingerprints.
After an actual 305-second wait without changing system time, the exact
revision-31 challenge returned `REPLACEMENT_CONFIRMATION_EXPIRED` and the
record, snapshots and resources remained byte-identical.

One new explicitly confirmed replacement was then executed only to establish
a consumed revision-32 operation; it returned `HEALTHY` revision `39`.
Replaying the exact same operation returned `STALE_REVISION`, and its complete
post-success fingerprint remained byte-identical. Confirmation values existed
only in shell memory and are absent from artifacts.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-6.2-confirmation-denials`;
the SHA-256 of its `SHA256SUMS` is
`446d0dc6b9786bc18ffe1f93f7373ad819633e7578fd08eeb638ef0127309d87`.

The exact resume point is task 6.3 of
`establish-development-database-lifecycle`.

Task 6.3 is complete and change progress is `29/37`. MariaDB was stopped and
exact-cleaned while preserving its record/snapshots. Four temporary collisions
were then created with pre-recorded immutable Docker IDs: unlabelled,
partially labelled, fully labelled foreign, and an ambiguous exact-container/
foreign-network projection.

Every mediated create returned ownership denial before mutation, and each
fixture's complete inspect SHA-256 was identical before/after rejection. Only
then was each fixture removed by its recorded exact container/network ID.
MariaDB was reconstructed from persisted ownership and returned `HEALTHY`
revision `35`.

The PostgreSQL record remained byte-identical. Beautips, slot2 retained Phase
3 resources, RAID and firewall share one unchanged before/after fingerprint.
Atenea's clean Git plus exact nine-container production/preview fingerprint is
also identical. The `13/13` state suite reconfirmed production-like manifest
denial, while the CLI still exposes no caller endpoint, literal credential or
arbitrary resource argument.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-6.3-target-and-resource-denials`;
the SHA-256 of its `SHA256SUMS` is
`e1baca72a4fbacd2a94507a5bfdbfbec5a94d1f301a59ac8af4cd9a3036f5e4b`.

The exact resume point is task 6.4 of
`establish-development-database-lifecycle`.
