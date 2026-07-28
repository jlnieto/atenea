# Remote Codex Platform Programme

## Authority and status

This document is the durable programme ledger for moving Atenea development execution to a dedicated remote worker.

- Programme: `remote-codex-platform`
- Foundation change: `establish-remote-codex-platform-program`
- Current phase: `route-agent-runs-to-remote-worker` (active `30/35`)
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

`relocate-atenea-development-to-ax42` is archived. The active
`route-agent-runs-to-remote-worker` change has a strictly valid proposal,
design, two capability deltas and 35-task checklist. Its entry gate is accepted
at `4/35`; routing remains disabled while implementation proceeds. Later names
remain a planning queue and MUST NOT be created or started from this phase.

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
SHA-256 of its `SHA256SUMS` is
`f4bbfc0cbfea8cf73a6361b18ceccdacff531aa6e34b9fe92f4e3c380b9377c8`.

The exact resume point is task 7.1: disable new synthetic selection, verify all
remote runs are terminal, execute idempotent rollback/cleanup and compare final
fingerprints. Do not start Phase 5 or make a real project authoritative.
