# Remote Codex Platform Programme

## Authority and status

This document is the durable programme ledger for moving Atenea development execution to a dedicated remote worker.

- Programme: `remote-codex-platform`
- Foundation change: `establish-remote-codex-platform-program`
- Current phase: `establish-project-runtime-contract`
- Runtime routing: unchanged; Atenea production is not connected to the AX42
- Production/control plane: current Atenea VPS
- Development/execution plane: Hetzner AX42 (manual pilot only)
- Canonical source: GitHub
- Last evidence refresh: 2026-07-25

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

Only `establish-project-runtime-contract` is active. The names after it are a
planning queue, not created or implementation-ready OpenSpec changes. Each must
receive its own proposal, design, specs and tasks only when its entry gate is
met; they are deliberately not folded into the active runtime-contract change.

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

The active change is `establish-project-runtime-contract`. Resume from its first
unchecked task. The administrative Codex/tmux bridge may be used to begin work,
but it is not evidence of the managed session isolation boundary and MUST NOT be
used as Atenea's AgentRun executor.

Recommended session title while this programme remains in its documentation
handoff is `Migración Atenea y Codex al AX42 — fase documental`.
