# Remote Codex Platform Programme

## Authority and status

This document is the durable programme ledger for moving Atenea development execution to a dedicated remote worker.

- Programme: `remote-codex-platform`
- Foundation change: `establish-remote-codex-platform-program`
- Current phase: `bootstrap-secure-codex-worker` (implementation complete except tailnet enrollment and observation gate)
- Runtime routing: unchanged
- Control plane: current Atenea VPS
- First worker: Hetzner AX42
- Last evidence refresh: 2026-07-22

The normative requirements live in OpenSpec. This ledger records phase state, decisions, evidence locations and the exact resume point. Code, tests and migrations remain authoritative for existing Atenea runtime behaviour.

## Objective

Make repository work initiated through Atenea continue safely without the operator laptop, support up to four bounded concurrent project sessions, preserve the trusted Codex workflow, and make manual and automated browser verification available from laptop and mobile without publishing development services.

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

## Scope

Included:

- secure AX42 worker baseline;
- private network between Atenea, worker, laptop and mobile;
- durable worker dispatch and reconciliation;
- session worktrees and runtime manifests;
- compatible `dev` CLI;
- four-slot scheduling and resource policy;
- private previews, SSH tunnels and Playwright artifacts;
- Codex instruction, skill and toolchain parity;
- onboarding Yvateve, Beautips, ISC, Recambios, Fomasys and Checkpol;
- monitoring, backups, cleanup, capacity and rollback.

Excluded:

- moving Atenea PostgreSQL, web or mobile APIs to the worker;
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
```

### Target

```text
Laptop / Android
        │ authenticated operator traffic
        ▼
Atenea control plane
  ├─ public web + mobile APIs
  ├─ PostgreSQL and durable workflow state
  ├─ scheduling, leases and notifications
  └─ private worker protocol
        │ encrypted private network
        ▼
AX42 worker
  ├─ session worktrees
  ├─ bounded Codex execution
  ├─ project runtimes and caches
  ├─ private previews + Playwright
  └─ artifacts + operational telemetry
```

## Ownership boundaries

| Concern | Authority | Notes |
|---|---|---|
| Projects and WorkSessions | Atenea | Logical project identity replaces assumptions that one host path is universal. |
| Conversation and AgentRun state | Atenea/PostgreSQL | Worker events are idempotently incorporated. |
| Dispatch lease and live processes | Worker, observed by Atenea | Atenea decides admission and terminal product state. |
| Git workspace | Worker per WorkSession | Canonical remotes and branches remain Git-backed. |
| Runtime manifest | Project repository | Consumed by worker and `dev`. |
| Preview route | Worker, published in Atenea | Private by default and session-scoped. |
| Browser artifacts | Worker storage, indexed by Atenea | Retention survives preview teardown. |
| Codex context | Versioned Atenea/project sources | The run records the effective context version. |
| Secrets | Dedicated secret boundary | Never OpenSpec, Git, ordinary logs or copied home directories. |
| Public/mobile authentication | Atenea | Existing operator contract remains. |
| Backups | External target | RAID is not the backup target. |

## Phase order

1. `bootstrap-secure-codex-worker`
2. `establish-project-runtime-contract`
3. `route-agent-runs-to-remote-worker`
4. `add-private-session-previews`
5. project onboarding, one pilot then modern Docker then legacy Tomcat
6. `harden-worker-operations`
7. default cutover and bounded retirement of the old executor

Entry, evidence, rollback and archive gates are defined in `remote-codex-platform-phases.md`. No phase becomes authoritative merely because its code builds.

## Decision log

| ID | Decision | Rationale | Status | Owner | Safe review point |
|---|---|---|---|---|---|
| D-001 | Keep Atenea as control plane and AX42 as worker. | Preserves working web/mobile/durable state and isolates resource-heavy execution. | accepted | platform owner | before any control-plane relocation proposal |
| D-002 | Introduce an authenticated worker protocol instead of pointing Atenea directly at one remote App Server. | Scheduling, leases, cancellation, workspace and preview ownership need a worker contract. | accepted | platform owner | remote routing design phase |
| D-003 | Use Tailscale initially. | Provides WireGuard data plane, device identity, NAT traversal, mobile support and policy with lower operational load. | accepted, enrollment owner pending | platform owner | before worker joins a production tailnet |
| D-004 | Retain `dev` as a compatibility CLI over manifests. | Preserves operator muscle memory while removing laptop-only internals. | accepted | platform owner | runtime contract phase |
| D-005 | One worktree and runtime namespace per WorkSession. | Protects branches and permits safe cross-project concurrency. | accepted | platform owner | runtime contract phase |
| D-006 | Do not expose the host Docker socket to Codex. | A mounted socket is effective host-root and defeats session isolation. | accepted | security owner | isolation spike |
| D-007 | Reconcile remote runs through leases after restart. | Backend process lifetime is not execution lifetime. | accepted | backend owner | remote routing phase |
| D-008 | Use one stable foundation plus short-lived implementation changes. | Avoids one unreviewable long-running migration change. | accepted | programme owner | after every phase archive |
| D-009 | Prefer Beautips as pilot after repository synchronization. | Checkpol is simpler at runtime but currently has 14 local uncommitted changes; Beautips is locally clean. | provisional | programme owner | onboarding gate comparison |
| D-010 | Keep localhost SSH tunnels as compatibility fallback. | Some cookies, callbacks and legacy assumptions may not accept a tailnet hostname. | accepted | runtime owner | each project onboarding |

## Deferred decisions and gates

| Decision | Deferral | Must be resolved before |
|---|---|---|
| Tailnet identity provider, owner and recovery administrator | Requires operator account choice; infrastructure can be hardened first. | enrolling Atenea or AX42 in the production tailnet |
| External backup target and retention | Compare Hetzner Storage Box with an independent provider and existing storage. | storing authoritative non-Git artifacts or completing operational hardening |
| Final pilot | Beautips is provisional; first reconcile its local and Atenea commits. | enabling first real project run |
| Per-project localhost requirement | Discover through cookies, callbacks and browser tests. | declaring that project's private preview ready |
| Initial runtime sandbox implementation | Prototype mediated rootless/container alternatives against the no-host-socket requirement. | accepting the runtime contract phase |
| Lease, artifact and preview retention durations | Measure representative jobs. | production defaults in remote routing/preview phases |

## Runtime non-impact statement

This foundation does not:

- deploy services;
- change Atenea endpoints or database schema;
- point any WorkSession at the AX42;
- modify current production containers;
- open or close firewall ports;
- copy repositories or credentials;
- change startup reconciliation.

Every implementation phase requires a dedicated OpenSpec change, test evidence, deployment evidence, an observation window and an executable rollback.

## Resume protocol

After any interruption:

1. Open this ledger and identify `Current phase`.
2. Run `openspec list` and `openspec status --change <current-change>` in the canonical Atenea worktree.
3. Confirm the production Atenea worktree and the programme worktree are not being confused.
4. Read the stable capability specs and the active phase proposal/design/tasks.
5. Recheck the dependency gate in `remote-codex-platform-phases.md`.
6. Inspect actual worker/control-plane state before continuing; never infer it from documentation alone.
7. Continue the first unchecked task or record a new decision if evidence invalidates the plan.
8. Validate strictly, collect release/rollback evidence and archive the phase before advancing.

The active change is `bootstrap-secure-codex-worker`. Its implementation and
reboot evidence are recorded in `remote-codex-worker-bootstrap-evidence.md`.
Resume at the Tailscale identity/enrollment gate; do not repeat completed host
hardening stages. Archive only after private connectivity is proven and the
24-hour observation window is clean.
