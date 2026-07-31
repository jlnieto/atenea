# Remote Codex Platform Programme

## Authority and status

This document is the durable programme ledger for moving Atenea development execution to a dedicated remote worker.

- Programme: `remote-codex-platform`
- Foundation change: `establish-remote-codex-platform-program`
- Current phase: Beautips production remote routing accepted after independent
  backup, exact rollback/reactivation and real laptop preview validation
- Runtime routing: only the exact Beautips route is enabled; the generic
  project route, Atenea and every unrelated project remain disabled
- Production/control plane: current Atenea VPS
- Development/execution plane: Hetzner AX42; Beautips owns one active
  WorkSession in slot 4 while the administrative slot 1 stack remains foreign
- Canonical source: GitHub
- Last evidence refresh: 2026-07-30

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
Production routing was unchanged and disabled at that archive.
`add-worksession-attachments` is
archived as `2026-07-29-add-worksession-attachments` with all `31/31` tasks
complete. `add-private-session-previews` is archived as
`2026-07-29-add-private-session-previews` with all `37/37` tasks complete. Its
accepted synthetic boundary used an authenticated coordinator on `8789`,
tailnet-only ingress ports `19000–19031`, a renewable five-minute lease, an
eight-hour hard lifetime and 30-day preview audit metadata. Rollback leaves
the capability disabled with zero route/runtime projection resources. Public
sharing remains disabled. The former real-project backup gate was later
lifted by the accepted independent external backup.
`establish-development-database-lifecycle` is archived as
`2026-07-29-establish-development-database-lifecycle` with all `37/37` tasks
complete. Its synthetic PostgreSQL and MariaDB fixtures were restored,
rollback-tested and exact-cleaned; database automation remains disabled and
real-project activation remains blocked on individual onboarding plus an
independent restore-tested backup. `onboard-atenea-on-ax42` is archived as
`2026-07-29-onboard-atenea-on-ax42` with all `45/45` tasks complete. Its exact
protocol remains installed but project selection/execution is disabled with
zero registered workspaces. Beautips onboarding and its subsequent exact
production activation are recorded later in this ledger.

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
| D-034 | Onboard Atenea first and Beautips second; keep every other project independently disabled. | Phase 8 requires one archived change per project and Atenea already has the strongest canonical manifest/relocation evidence. | accepted | programme owner | after Atenea archive |
| D-035 | Run the first managed real Codex workload through bounded `codex exec` as the already authenticated AX42 administrative identity without copying or reading its authentication cache. | The service identity is deliberately unauthenticated; the documented ephemeral probe proves usable ChatGPT authentication while preserving the forbidden-auth boundary. | accepted for Atenea pilot | security/runtime owners | before expanding beyond the pilot |
| D-036 | Keep Atenea onboarding artifacts non-authoritative and its database empty-migrated plus synthetic until an external backup has passed restore. | RAID and retained acceptance evidence do not satisfy authoritative project backup. | accepted | data/operations owners | before any real retained data |
| D-037 | Admit `project-codex-v1` only from a root-owned exact Atenea workspace registry and execute it in a per-run Bubblewrap namespace with no caller-supplied command, path, remote, endpoint or environment. | The authenticated identity can be reused without turning the worker into a general shell or mounting other workspaces, daemon sockets or production paths. Uncertain turns fail closed after restart instead of being replayed. | accepted for Atenea pilot | security/runtime owners | after Atenea archive and before a second project |
| D-038 | Close Atenea onboarding after a 15-minute disabled/clean observation with exact samples at minute 0, 5, 10 and 15. | Four bounded samples are sufficient to detect automatic resurrection or health drift after exact rollback while keeping this disposable, non-production gate finite. Any drift blocks archive. | accepted for Atenea pilot | programme owner | before onboarding archive |
| D-039 | Pin Beautips to GitHub `jlnieto/beautips` `main`; retain entry commit `5044a3b07b3db82895e9c8ff47bc4bc9b0e97130` and manifest commit `e4256d7fe1610e191099bd12ce993591a5cd4b7a` as reviewed ancestors, with task 2.3 descendant `e9e0b3c319c518363d4135f5378ebbddced96dfb` as current mediated source. | GitHub and AX42 agreed at entry, both older copies were strict ancestors, and accepted descendants remove fixed manual runtime authority plus unmanaged smoke fallbacks before allocation. | accepted for Beautips pilot | programme owner | before any managed Beautips allocation |
| D-040 | Treat the existing manual slot 1 Beautips workspace, runtime, listener, secret boundary and persistent data/files as foreign retained state. | The administrative pilot is healthy but has no WorkSession ownership or independent restore-tested external backup. | accepted | runtime/data owners | throughout Beautips onboarding |
| D-041 | Use only empty migrated PostgreSQL, disposable Redis, invented fixtures/files and disabled WhatsApp for managed acceptance. | Platform ownership can be proven without copying administrative, legacy or production-derived data and without external messaging authority. | accepted | data/security owners | before managed runtime start |
| D-042 | Declare no localhost requirement for the disabled-WhatsApp acceptance; block on any absolute-origin failure rather than generating a tunnel implicitly. | Relative application paths can be verified through the private preview while excluded OAuth/messaging flows cannot justify broader compatibility. | accepted for Beautips pilot | runtime/product owners | private preview acceptance |
| D-043 | Close Beautips after a 15-minute disabled/clean window with samples at minute 0, 5, 10 and 15. | The same bounded post-rollback control detects resurrection and protects the administrative pilot and production. | accepted for Beautips pilot | programme owner | before onboarding archive |
| D-044 | Use a private Backblaze B2 bucket in an operator-owned account as AX42's independent encrypted backup target, keeping 14 daily, 8 weekly and 12 monthly exact-host restic snapshots. | A separate provider and recovery boundary protects against complete AX42/Hetzner loss; bucket-scoped credentials, bounded retention and restore evidence are required before authoritative retained state. | accepted, provisioned and restore-tested | operations owner | before lifting the external-backup gate |
| D-045 | Enable production remote selection only for the exact Beautips project after its workspace is durably provisioned; keep the generic route and every unrelated project disabled. | A project-specific gate permits normal laptop operation without widening remote authority or moving existing sessions. | accepted and active | platform owner | before a second real project |
| D-046 | Give AX42 a dedicated read-only Beautips GitHub deploy key and pin GitHub's Ed25519 host identity while preserving the canonical HTTPS remote URL. | Automated workspace creation needs private repository read access without reusing an operator credential or storing a transport-specific canonical remote. | accepted and active | security/runtime owners | deploy-key rotation |
| D-047 | Derive preview worker project identity as a bounded lowercase project name while retaining the exact Atenea project name for allowlisting and UI. | Atenea persists `Beautips`, while the runtime allocation contract owns `beautips`; explicit canonicalization prevents a case-only ownership conflict. | accepted and regression-tested | backend owner | before project names requiring a non-trivial slug |
| D-048 | Retire the closed onboarding session's stale active allocation marker only after its released admission, absent runtime resources and archived byte-identical allocation are proved. | The retained active marker contradicted reusable slot ownership even though the closed fixture had completed the D-033 release gate. | accepted for the exact onboarding record | runtime owner | general allocation-retirement support |
| D-049 | Activate private previews in production Atenea with a dedicated host-to-host credential, the exact `Beautips` allowlist and tailnet-only AX42 control/ingress. | Real browser acceptance must use the same control plane as normal operation without public sharing, runtime-port disclosure or credential exposure. | accepted and active | platform/security owners | preview credential rotation or public-sharing proposal |
| D-050 | Retain the four fail-closed activation AgentRuns as immutable audit history and accept only subsequent terminal successes. | Rewriting failed attempts would destroy evidence of prerequisite enforcement; successful runs on the same workspace and thread prove the corrected path. | accepted | backend/programme owners | terminal audit-retention policy |
| D-051 | Retire a `DRAFT_BLOCKED` WorkSession's stale active allocation marker only after exact recovery, released admission, sealed semantic equality, absent owned runtime resources and byte-preserving rename are proved. | A retained source draft must remain reviewable without permanently preventing its clean replacement from owning the fixed project slot. | accepted for the exact Atenea recovery | runtime/programme owners | general retained-draft allocation retirement support |
| D-052 | Resolve model and effort independently through next-turn, WorkSession, project, platform and worker-default precedence; persist both field sources with the exact catalog revision and Codex version. | A one-turn effort change must not erase a longer-lived model choice, and later setting changes must never rewrite execution history. | accepted | backend/worker owners | before changing execution-profile precedence or source fields |
| D-053 | Version the worker catalog with canonical worker/Codex/model fields and accept only each model's advertised subset of `none`, `low`, `medium`, `high`, `xhigh` and `max`; aliases, Pro and Ultra remain outside persisted profile authority. | Current Codex families do not share one implicit capability set, so exact per-model advertisement and fail-closed intersection avoid silent substitution. | accepted | platform/worker owners | each catalog schema revision or Codex family expansion |
| D-054 | Use the thirteen fixed sanitized progress categories, coalesce identical consecutive events before sequencing and retain the newest 200 events plus independent current/latest/terminal/next-action projections. | The operator gets bounded useful progress and deterministic replay without retaining reasoning, raw commands, output or secrets. | accepted | backend/worker owners | before changing progress taxonomy or retention bound |
| D-055 | Enable completion, failure and action-required push categories by default per active Android device, keep intermediate progress in-app only, and reserve update plan/stage/activation/rollback for platform administrators with separate exact activation and operator-rollback authorizations. | Defaults must notify unattended work without push noise, while binary lifecycle changes remain distinct from routine and mediated recovery authority. | accepted | product/platform/security owners | notification-default or Codex-update authority change |
| D-056 | Add V57–V61 in dependency order for profiles/catalog, progress, recovery, generic notifications and managed updates; keep five independent gates default-off and accept production migration only after a protected V56 restore plus exact rollback-image compatibility proof. | Expanded history is required for audit/reconciliation, while a nominal old image may reject future Flyway history and therefore cannot be assumed to be a viable rollback. | accepted | data/backend/platform owners | before production V57 or any later schema contraction |
| D-057 | Introduce additive `project-codex-v2`, catalog, progress and closed API schemas while keeping installed v1 compatible; require semantic catalog and exact session/workspace validation after structural schema validation. | JSON syntax alone cannot reject a well-formed foreign UUID or model absent from the current worker catalog, and accepting caller operational fields would recreate arbitrary command authority. | accepted | security/backend/worker owners | protocol v2 implementation or schema revision |
| D-058 | Persist project and WorkSession model/effort defaults independently, but require the immutable AgentRun effective profile to be either entirely absent for legacy history or complete with both values, both sources, catalog revision and Codex version. | Model and effort have independent precedence, while partial execution history would be ambiguous and unauditable. | accepted | backend/data owners | before changing V57 profile constraints or snapshot semantics |
| D-059 | Persist only the thirteen exact category-derived operator messages in V58, serialize sequence allocation with the owning AgentRun row and evict detail below a moving 200-event floor without removing the independent projection. | Free-form progress text can retain commands, output or credentials; row ownership plus a non-reused sequence and projection-first replay gives deterministic concurrent append and reconnect behavior. | accepted | backend/data/security owners | before adding or localizing a progress template or changing replay retention |
| D-060 | Bind each V59 recovery request to one active operator's persisted role snapshot, exact WorkSession/AgentRun composite ownership, idempotency key and canonical request fingerprint; persist routine attempts at privileged actions as closed `ROLE_REQUIRED` outcomes, and permit `RETRY_CREATED` only with immutable same-session `retryOfRunId` lineage. | Authentication alone does not grant host authority, repeated keys must not change meaning after timeout, and a replacement run without exact lineage could duplicate a still-live execution. | accepted | backend/data/security owners | before expanding recovery actions, role authority or retry lineage |
| D-061 | Make V60 notification defaults implicit-enabled through absent preference rows, constrain event copy to the three exact `agent-run-safe-v1` templates, bind deduplication to category/run/source revision and own one FCM delivery per exact event/device without copying the device token. | Upgrade and re-registration must not reset user choices, event rows must never retain conversation content, and partial dispatch needs independently retryable delivery ownership without duplicate presentation. | accepted | backend/mobile/data/security owners | before adding a notification category, template version, channel or changing preference defaults |
| D-062 | Keep the established three-field authenticated principal and resolve operational role from the current active account for each privileged API; require exact JSON field sets, catalog membership and persisted ownership, and expose V57–V60 APIs only behind five independent default-false gates. | Token-carried authority can become stale, permissive JSON binding silently accepts forbidden fields, and additive persistence must remain inert until each rollout dimension is separately accepted. | accepted | backend/security/platform owners | before changing API authority, closed request fields or feature-gate defaults |
| D-063 | Give every shared web/mobile session event a stable persisted-identity key, seed and poll the bounded 200-event SSE window by that identity, and replace the legacy run-terminal timeline item only when a committed terminal progress event is published behind the enabled progress gate. | Timestamp cursors can drop same-instant events or resend them after reconnect; publishing both lifecycle and progress terminals creates duplicate operator output, while disable-first rollback must retain the established terminal feed. | accepted | backend/web/mobile owners | before changing shared-stream identity, progress publication or terminal fallback |
| D-064 | Run shared control-plane integration suites with global synthetic authentication bootstrap disabled and require authentication-specific tests to opt in with their exact operator fixture. | An eager default operator makes database-backed authorization tests order-dependent and can conceal which persisted role or identity actually authorized an operation. | accepted | backend/security/test owners | before changing integration-test authentication bootstrap |
| D-065 | Advertise the first exact Codex catalog through a separately authenticated `codex-model-catalog-v1` endpoint/capability while retaining the strict v1 health shape and withholding `agent-run-project-codex-v2` execution until its fingerprint and runner are complete. | Adding fields to the fail-closed v1 health DTO would break the current control plane, while advertising executable v2 authority before validation and runner support would create a false capability. | accepted | worker/backend/security owners | before changing catalog transport or advertising v2 execution |
| D-066 | Validate and fingerprint the complete `project-codex-v2` envelope, profile and existing v1 ownership before persistence, but reject even a valid v2 create as `profile_execution_unavailable` until the fixed runner consumes the validated profile. | Persisting or scheduling a profiled request before model/effort flags are actually enforced could execute under a silently different profile; staged validation must remain fail-closed. | accepted | worker/security owners | task 3.3 runner enablement or profile-fingerprint change |
| D-067 | Permit profiled execution only through the fixed runner's exact `--model` plus `model_reasoning_effort` arguments, require a pre-execution exact fixed-binary version probe, and reject any runner result whose echoed profile/version differs from the request. | A validated request can still execute incorrectly if the binary link moved, ambient configuration wins or the runner reports a substituted profile; all three boundaries must agree before success. | accepted | worker/security owners | before changing runner flags, binary path or effective-profile result fields |
| D-068 | Normalize only recognized Codex JSONL structure into fixed progress messages, discard every source payload value and let the worker replace timestamps while assigning identity, monotonic sequence, coalescence and bounded retention. | Trusting model-provided text, command/output fields or source timestamps would let secret-bearing content cross the worker boundary even when the category itself is allowed. | accepted | worker/security owners | before changing structured-event mappings or progress message templates |
| D-069 | Persist the highest imported worker progress sequence on the AgentRun and lock that row before atomically applying progress, terminal state and result turn; retain byte-stable terminal worker records across restart. | Lifecycle revision alone cannot deduplicate replayed detail events, and two coordinators must not both create a response turn after observing the same terminal worker revision. | accepted | backend/worker/data owners | before changing worker replay identity, coordinator locking or terminal transaction boundaries |
| D-070 | Require dispatch-path, execution, session, workspace and lease ownership for new routine recovery routes; make reconciliation read-only and constrain doctor to a closed no-values schema while retaining the v1 cancel route. | Execution ID alone is insufficient for new recovery authority, and a free-form diagnostic could expose prompts, results, commands, paths or secrets or accidentally mutate execution state. | accepted | worker/backend/security owners | before changing recovery ownership fields, doctor output or v1 cancel compatibility |

## Deferred decisions and gates

| Decision | Deferral | Must be resolved before |
|---|---|---|
| Second independent tailnet administrator | The operator chose one paid seat initially. `info@codynwave.com` is Owner; Microsoft recovery and public SSH break-glass remain mandatory. | removing public SSH break-glass or expanding beyond one operator |
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

Task 6.4 is complete and change progress is `30/37`. The slot 3 and slot 4
rootless Docker daemons were restarted with finite timeouts, then a fresh
process-per-invocation mediator reconciled exactly the two persisted database
records with `implicitCreation=false`. Both exact database containers remained
exited under restart policy `no`; no container create/start event occurred and
rootful Docker remained inactive.

Container, WorkSession-network and volume identities were byte-identical
before and after. Docker expectedly regenerated only each daemon's built-in
unlabelled `bridge` network, so that daemon-private identity is explicitly
normalized while every WorkSession resource ID remains strict. Records,
snapshot metadata/content, workspaces, Git files, host boot ID and RAID have
one unchanged static fingerprint.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-6.4-rootless-restart-reconcile`;
the SHA-256 of its `SHA256SUMS` is
`715a6cca3dee81475a6d6fc7add73b15dc32415525c41295439cf4bc73c01bc4`.

The exact resume point is task 6.5 of
`establish-development-database-lifecycle`.

Task 6.5 is complete and change progress is `31/37`. Two immutable fixture IDs
were recorded before creating one expired, exact-owned synthetic snapshot per
database. The first retention pass removed exactly the registry-computed
expired/excess IDs; PostgreSQL and MariaDB each retain the three newest
verified copies within seven days. A second pass removed nothing.

Every retained content file was checked against its recorded SHA-256 and size,
but no dump bytes or rows enter evidence. Database records, workspaces, host
boot, RAID, rootful Docker and all four slots' complete container, network and
volume inventories share one unchanged before/after fingerprint. Atenea's
clean Git and nine-container production/preview inventory also remain
byte-identical.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-6.5-bounded-snapshot-retention`;
the SHA-256 of its `SHA256SUMS` is
`888f28b9a182b0690fc14222880de06e5c6f05d07aaa8d72a4d0566b64b168bf`.

The exact resume point is task 6.6 of
`establish-development-database-lifecycle`.

Task 6.6 is complete and change progress is `32/37`. Two independent accepted
passes each completed `13/13` database state/manifest tests, `10/10`
mediator/engine tests and `10/10` project-runtime integration tests. Each pass
also read both persisted engine records/resources and reconciled exactly two
`HEALTHY/RUNNING` records with `implicitCreation=false`.

The integration harness ran as `atenea-worker` from a temporary exact source
copy owned by that executor. Two earlier fail-closed attempts are retained:
the harness first rejected root-owned test workspaces, then rejected an
inaccessible inherited working directory. Neither attempt mutated database
lifecycle state. All accepted invocations have finite timeouts, exit zero and
recorded durations.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-6.6-double-regression`;
the SHA-256 of its `SHA256SUMS` is
`4fe5bce53459130a91028da6a985f71fba33bb3b3e49463b81e0dd8cdccabcf7`.

The exact resume point is task 7.1 of
`establish-development-database-lifecycle`.

Task 7.1 is complete and change progress is `33/37`. New database operations
are disabled and both exact synthetic records are `STOPPED/STOPPED`.
Containers remain present and exited; their networks, volumes, six retained
snapshots, records, worktrees and allocations are preserved. Reconciliation
reports only the two persisted stopped records and creates nothing.

The first rollback attempt exposed a contract gap before any container
mutation: the disabled mediator also rejected `stop`. The boundary was
minimally corrected so only exact-ownership `stop` and `cleanup` remain
available as rollback actions while registration, creation, snapshots,
replacement and other new work stay disabled. The corrected implementation
passed `13/13` state tests, `10/10` worker tests and `10/10` integration tests;
its installed SHA-256 is
`d4bf3ea20bbd1ea5d083a4a46de61aa3c52a45c64a4a74bd97e3084c91764ab8`.

Snapshots, exact resource IDs and the protected platform fingerprint are
byte-identical across the accepted run. Atenea's clean Git and nine-container
production/preview inventory are unchanged.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-7.1-disable-stop-preserve`;
the SHA-256 of its `SHA256SUMS` is
`f07c7b394466c6c5d36e38d30757798206baba1484c424903bbee899afbb1685`.

The exact resume point is task 7.2 of
`establish-development-database-lifecycle`.

Task 7.2 is complete and change progress is `34/37`. Disabling the lifecycle
and stopping both exact persisted database IDs was repeated with finite
timeouts. Both records were already stopped, all calls were idempotent and a
complete fingerprint of records, snapshots, workspaces, allocations,
admission, every rootless container/network/volume/image, boot, RAID, rootful
Docker and firewall remained byte-identical. No additional resource was
deleted.

The sealed task 6.3 evidence was reverified file-by-file and its accepted
`SHA256SUMS` hash remains
`e1baca72a4fbacd2a94507a5bfdbfbec5a94d1f301a59ac8af4cd9a3036f5e4b`.
That real acceptance proves unlabelled, partial, fully foreign and ambiguous
fixtures retained identical inspect hashes throughout rejection and were
removed afterward only by pre-recorded exact IDs. A fresh `10/10` mediator
suite reconfirmed the denial paths. No fixture needed recreation during the
no-mutation rollback repeat. Atenea production/preview also has one unchanged
fingerprint.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-7.2-repeat-rollback-denials`;
the SHA-256 of its `SHA256SUMS` is
`34a137f6ab06b676800dd7198b6f2934587f3cc7a9fcc5d4e31a82c621599f99`.

The exact resume point is task 7.3 of
`establish-development-database-lifecycle`.

Task 7.3 is complete and change progress is `35/37`. Cleanup now validates the
complete container/network/volume projection and every retained snapshot
before its first deletion. Its `11/11` worker tests include a fail-closed case
where a foreign network prevents all deletion; the `13/13` state and `10/10`
integration suites also pass. The installed worker SHA-256 is
`4dd6dc93ca36726e2c523dc0d99eb5baab75af357bf9170f111aacee96ea5196`.

Exact final cleanup removed two stopped containers, two session networks, two
labelled data volumes, six private snapshot metadata/content pairs, two
ephemeral secret roots and two terminal database records. Reconciliation is
empty with `enabled=false` and `implicitCreation=false`; no allocated database
listener or Playwright/Chromium process remains.

The slot3 and slot4 admissions are released. Their allocation records were
archived byte-for-byte into accepted evidence only after exact resources were
absent and capacity was released. Both clean worktrees, mirrors and Git heads
remain. Four pre-existing anonymous slot4 volumes have incomplete ownership
labels, so fail-closed cleanup deliberately preserved them unchanged; images
also remain unchanged as shared immutable cache.

Beautips remains three running containers, retained slot2 resources are
unchanged, all three RAID arrays are `[UU]`, rootful Docker remains inactive,
and the firewall, AgentRuns and foreign-resource fingerprint is identical.
Atenea's clean Git and nine running production/preview containers also retain
one unchanged fingerprint.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-7.3-exact-final-cleanup`;
the SHA-256 of its `SHA256SUMS` is
`667096b7bcb995753e7bae903b9a5c15bd8ffa669a182cec3fa0c749e5227be9`.

The exact resume point is task 7.4 of
`establish-development-database-lifecycle`.

Task 7.4 is complete and change progress is `36/37`. The final rollup
reverified all `25` prior evidence packages file-by-file and records each
relative package, file count, byte count, `SHA256SUMS` digest, result and audit
duration. It inventories `23` sanitized command ledgers and `11`
timeout/exit-code/duration ledgers.

The complete Phase 7 artifact tree has no symlink, special file,
world-accessible file, raw snapshot, dump, environment file, credential file
or unmistakable private-key/token pattern. Versioned SQL migration and seed
files are deterministic synthetic programme inputs rather than captured
database output. No dump bytes, result rows, credential values or environment
captures enter the rollup.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-7.4-sanitized-evidence-rollup`;
the SHA-256 of its `SHA256SUMS` is
`cfdc552c9078f9f907ba5f147925f7281d66562b7c72c906adfcc769774f4dac`.

The exact resume point is task 7.5 of
`establish-development-database-lifecycle`.

Task 7.5 is complete and Phase 7 progress is `37/37`. Pre-archive strict
validation accepted the complete change. OpenSpec applied the new
`development-database-lifecycle` capability plus the isolated-runtime,
operational-safety and attachment deltas, then archived the change as
`2026-07-29-establish-development-database-lifecycle`.

Post-archive strict validation passes every authoritative specification and
OpenSpec reports no active Phase 7 change. The programme branch and Atenea
source are clean and synchronized after the archive commit.

Phase 7 closes default-disabled with zero database records, exact containers,
session networks, labelled volumes, private snapshot files, database
listeners or browser processes. Slot3 and slot4 admission is released; their
allocation records are archived while worktrees, mirrors, Git and sanitized
evidence remain. Beautips and retained slot2 resources are unchanged, RAID is
`[UU]`, rootful Docker remains inactive and Atenea production/preview remains
nine running containers.

Accepted final archive evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-development-database-lifecycle/runs/task-7.5-archive`;
the SHA-256 of its `SHA256SUMS` is
`60b29553abef2b1b0a7bbe79b5f6c1d9a85e53e581fd0bf511175c78dc44b3c1`.

The exact resume point is the Phase 8 individual-project onboarding entry
gate. No real project has been activated by Phase 7.

## Phase 8 progress: onboard-atenea-on-ax42

Tasks 1.1–7.5 are complete and change progress is `41/45`. The entry gate pins
GitHub `jlnieto/atenea`, branch
`feature/actualizar-conversacion-en-web`, commit
`b605c8d5b063e7321edd60fec2265ec7ddb84ea9` and manifest SHA-256
`3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3`.
Atenea source and its control-plane checkout are clean and synchronized; the
Dropbox `atenea` folder is four documentation files, not a Git source.

The first two projects are explicitly ordered Atenea then Beautips. All other
projects remain disabled. Atenea uses empty migrated PostgreSQL plus declared
synthetic fixtures, requires no localhost preview compatibility, publishes
only an exact WorkSession draft delivery and keeps all non-Git artifacts
non-authoritative until an external backup passes restore.

Following the current Codex non-interactive contract, an isolated probe used
`codex exec --ephemeral`, ignored user config/rules, selected a read-only
sandbox and completed in `4873 ms`. Only exit code, timeout, duration and
expected-output SHA-256 were retained. `codex login status` identified the
authentication method, but no `auth.json`, internal session, token, cookie,
credential or environment was read or copied by orchestration. The real pilot
uses a bounded per-run process, a Bubblewrap workspace-write filesystem
namespace and a collected transient cgroup. The Codex CLI's nested sandbox is
disabled only inside that reviewed namespace because AX42 rejects nested
unprivileged user namespaces. Only the exact derived
worktree, canonical Git common directory, private result directory and
Codex-owned authentication/session boundary are mounted. The child denies
loopback, RFC1918, Tailscale and link-local destinations while retaining
public Codex egress. The prompt remains on stdin and only the thread, turn,
final answer and fixed summary enter the result.

`project-codex-v1` has versioned request/result schemas and an exact root-owned
Atenea registry. Unknown fields, caller commands, paths, remotes, endpoints,
environments, foreign project/workspace identities and an empty allowlist fail
closed. Duplicate dispatches retain one execution, exact cancellation does not
affect another process, and restart reconciliation never silently duplicates
an uncertain turn. The existing authenticated tailnet port is reused; no public
or additional listener is introduced.

AX42 retains four active rootless daemons, free container slots 2–4, three
running Beautips containers, three RAID arrays `[UU]`, active Tailscale/UFW,
inactive rootful Docker and disabled real worker/preview/database capabilities.
Atenea retains nine running production/preview containers. No runtime, route,
database or real AgentRun was created by the gate.

The worker was installed, enabled, verified, disabled and rolled back twice.
Its final state is `disabled/inactive`, port `8787` has no listener or UFW
rule, the exact project registry is disabled with zero workspaces, and the
installed runner/config are root-owned. Focused tests pass locally and on
AX42. AX42 lacks the optional `jsonschema` Python module, so schema validation
runs in the repository environment while portable runner tests exercise argv
isolation and thread continuity on AX42. A first remote test invocation
recorded exit `2` because its test file had not yet been copied; corrected
bounded invocations are retained rather than hiding that setup failure. The
first network baseline selected a tailnet SSH destination that does not admit
AX42 and timed out; the corrected control uses AX42's reachable tailnet
attachment listener, proves it reachable without the child policy, then proves
the same tailnet and loopback destinations denied while public egress remains
available. An initial broad `pgrep -f` matched its own capture shell; the
authoritative corrected final state uses exact listener, firewall and transient
unit counts.

The repository-wide test entrypoint was also attempted, but Compose stopped
before tests because two pre-existing local test containers already owned its
fixed names. The attempt-created empty network and unused volume were removed
by their exact recorded identities; the older containers were left unchanged.
The protocol suites above are green. The corrected source mount was then used
for the two complete regressions recorded at task 3.6.

Atenea commit `467e2abed1e86e9b8eac5fac2fcec2df59825be7` completes
the control-plane integration. Each newly selected remote WorkSession now
persists a UUID external session identity and one immutable workload kind;
each project AgentRun additionally persists the exact Atenea project,
repository, branch, base commit and manifest hash before dispatch. The
separate `ATENEA_REMOTE_WORKER_PROJECT_CODEX_ENABLED` gate defaults false,
synthetic routing remains compatible, and a worker lacking the exact
capability leaves a new session local.

The client sends no caller command, path, endpoint or environment and carries
the prior external thread UUID only for continuation. Terminal application
maps the returned thread, turn, final answer and summary once. Focused tests
cover exact selection/denial, payload, persistence, cancellation, bounded
partition, startup reconciliation without redispatch and duplicate terminal
delivery. Migration V49 passed against PostgreSQL test state. Two final full
regressions each passed `391/391` in `32.337 s` and `32.537 s`; production
configuration and its nine running containers did not change.

Accepted sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/entry-gate`;
the SHA-256 of its `SHA256SUMS` is
`fdaf300e4057ce174785a55dee832ff1cac78db8aee4bb0ca8604a1a3a1ba049`.

Protocol evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-2-project-codex-worker`;
the SHA-256 of its `SHA256SUMS` is
`2bad7bca1e4771746df14b01b8441c0c2594a663d6909f88081b963447b14abf`.

Control-plane integration evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-3-control-plane-integration`;
the SHA-256 of its `SHA256SUMS` is
`a204af05e56a8719623b80b688c75f172a81a26ba4f6a6093e059333462ae4c9`.

The canonical acceptance owns WorkSession
`c20f3cde-9a64-4c7b-a674-7b63f94ca475`, branch
`atenea/session-c20f3cde-9a64-4c7b-a674-7b63f94ca475`, external Codex thread
`019facd4-89cc-7cf3-a289-f0190b9a1767`, slot 3 and heavy admission 1. Its
worktree remains pinned to
`b605c8d5b063e7321edd60fec2265ec7ddb84ea9`; the two accepted turns created
only `docs/ax42-onboarding-acceptance.md`, with SHA-256
`5eb0ecbbe266063473e78d44b884c2d7fbab42594e1a946762d347278c3203b8`.
The second turn reused the same thread, exact replay reused its execution and
left both worker state and project content unchanged, and a new observer
connection recovered the same session, workspace, branch, thread and terminal
state.

The disposable control plane records all ten failed hardening attempts and
three successful protocol executions; only the final two successful turns are
the accepted project mutation. Every failed attempt left the project
unchanged. A complete foreign workspace was denied with
`workspace_ownership_conflict` while its registry and worker-state
fingerprints remained unchanged. No session runtime container, network,
listener or temporary result directory remains, and production retains its
nine unchanged containers.

Canonical acceptance evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-4-canonical-worksession-acceptance`;
the SHA-256 of its `SHA256SUMS` is
`1f0659e909dcf05af91d1bcaf6c6af05a4b108bcfa7ed5a4b57c99f32d265394`.

Task 5.1 builds the archived exact WorkSession commit rather than the dirty
operator worktree. The final mediated invocation completed the Vite production
build with 1,583 modules and Maven `clean package` with `380/380` tests, zero
failures, zero errors and zero skips. It produced executable JAR SHA-256
`aaed96b9639bf8501c7692b39fcfdfb9ef66f597811b178fe8b08998523ab9e8`
in 53,211 ms.

Four preceding fail-closed invocations remain in the evidence ledger. They
exposed, in order, the missing isolated test PostgreSQL, dependency resolution
on an internal-only build network, the absent writable integration-test
scratch and its missing exact workspace-root setting. The accepted adapter now
uses an ephemeral exact-owned PostgreSQL container and test network plus a
tmpfs `/workspace/repos`; every temporary build resource is removed after the
invocation. No runtime, PostgreSQL volume or allocated listener was started.
The WorkSession still has only its task-4 documentation change, and Atenea
production retains its nine unchanged containers.

Task 5.1 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-5.1-build-tests`;
the SHA-256 of its `SHA256SUMS` is
`be08c49c59b805356a9a50a6a3e82b94cc290c9bdf3d16391a29c1fa19ba564d`.

Task 5.2 starts the exact slot-3 runtime with three session-labelled
containers, one internal network, one retained PostgreSQL volume and only the
three allocated loopback projections. The web actuator is `UP`. PostgreSQL
initialized from the empty volume, applied `48/48` successful migrations and
retains zero rows across the declared domain tables; the declared fixture set
is empty.

The first fail-closed start found that the allocation had persisted
zero-length development secret placeholders. The mediator now generates only
those exact placeholders atomically after validating ownership, mode and
length, and never emits or retains their values. A second start reached a
healthy migrated runtime but rejected historical administrative expectations
of 45 migrations and non-empty fixtures. The corrected status check accepted
the already-running resources in 718 ms without recreating any identity.
Production retains its nine unchanged containers.

Task 5.2 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-5.2-rootless-runtime`;
the SHA-256 of its `SHA256SUMS` is
`4a09f4067af42718cb8c88690724543ff67d24fff247a6ea98f935e5ea295390`.

Task 5.3 proves the running application cannot reach public Internet, Atenea
production/preview, Atenea SSH or PostgreSQL. Its root filesystem is
read-only, all capabilities are dropped, no devices or privileged mode exist,
and Docker sockets, runtime proxies, host root and foreign workspace paths are
absent.

An exact mediated restart passed in 14,879 ms. The three container IDs and
retained volume fingerprint remained unchanged, the migration summary stayed
`48|1|48|48|0|48`, declared domain rows stayed zero and the actuator returned
`UP`. Only the three exact RootlessKit projection-record IDs rotated; their
loopback addresses and allocated ports did not. WorkSession Git and the nine
production containers remain unchanged.

Task 5.3 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-5.3-isolation-persistence`;
the SHA-256 of its `SHA256SUMS` is
`118400fe8660e0012ab77f4a64bc61f3b761ce4b7876484b3a32f194e8dc120c`.

Task 5.4 creates exact synthetic preview
`a6b4a872-8cfe-495f-a457-25af7593f256` on tailnet-only ingress
`100.81.98.93:19000`. Operator and Atenea probes retrieved the SPA root with
HTTP 200 and the same 449-byte body SHA-256
`3555271f84b38f49b72634d5134693d82b96607332f3f92a738ba5abb7480404`.
Both public probes to `167.235.186.151:19000` timed out after 15 seconds with
HTTP 000 and zero response bytes. UFW limits ingress to `tailscale0` from the
tailnet range and limits control to the exact Atenea tailnet identity.

Atenea declares no localhost compatibility requirement, so the coordinator
returned `localhostCompatible=false` and `tunnel=null`; no localhost forward
or temporary listener was created. The known committed manifest path
`/admin/login` still returns HTTP 404 while the relocation-accepted SPA route
`/` returns HTTP 200. The discrepancy remains explicit and no WorkSession pin,
source or runtime was changed to conceal it.

The exploratory preview expired under its bounded lease and was deleted only
through its exact persisted synthetic identity. The accepted preview was
created and renewed once to revision 3. Production, preview, Beautips, the
slot-3 runtime and Git fingerprints remain unchanged.

Task 5.4 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-5.4-tailnet-preview`;
the SHA-256 of its `SHA256SUMS` is
`03079faab79ccee57e477bcb816d16b07e28b2d366f24ecfbfec3f2ad79ddd7a`.

Task 5.5 runs Playwright 1.60.0 and Chromium 148 against the real tailnet
preview. Both `1440x900` and `390x844` loaded the SPA root with HTTP 200 and
proved visible `Acceso de operador`, `Backend disponible`, both credential
fields and the disabled empty-input `Entrar` action. Each viewport has exact
document/body width, no horizontal overflow, no failed or external request and
positive in-viewport boxes for every critical element.

The first real browser attempt exposed a preview-forwarder defect:
non-blocking `sendall()` truncated the 313,903-byte JavaScript bundle after
43,772 bytes. The accepted bounded bidirectional pump delivers the complete
bundle and a new 2.75 MiB regression passes with all `15/15` preview tests.
The installed and source program SHA-256 is
`678b3f66e18f792e29f06ae83bfc8cc08bbbeea0cc04d07bae4d06cf61ab070f`.

Both original-resolution screenshots were inspected. The desktop and mobile
login states have a clear operational-status hierarchy, readable copy and
fields, consistent spacing, no clipping, overlap or misleading empty state.
Their SHA-256 values are respectively
`a00a037ecea12f4dbb805b977285144a7655d0fac6861681a866e663f9f3b03c`
and
`761494d34127842802f59f027a3f2794c5280795c1ba885f3dbae6b1e248839b`.
Page, context and browser closed in `finally`; the guarded browser boundary is
idle with no task-owned process. Runtime, production, preview, Beautips and
WorkSession Git remain unchanged.

Task 5.5 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-5.5-playwright-visual`;
the SHA-256 of its `SHA256SUMS` is
`ac6da0210f252e08e84e12d5b71d62614e7f1005dd74d85678d3574f2c4ae0d4`.

Task 5.6 registers the desktop PNG, mobile PNG and DOM report as three exact
`EVIDENCE`-retained synthetic attachments for WorkSession
`c20f3cde-9a64-4c7b-a674-7b63f94ca475`. The first PUTs returned HTTP 201;
repeating the same immutable attachment identities returned HTTP 200 with the
original metadata and introduced no duplicate bytes.

The attachment boundary now accepts the existing positive-decimal WorkSession
identity or a canonical UUID while malformed/non-canonical values still fail
closed. All `12/12` attachment tests pass, and the installed/source program
SHA-256 is
`139401e9b336264af29d6f3a20493ed3185e630496e9d261628b62364121be12`.

After registration, the expired preview was deleted only through exact
preview ID `a6b4a872-8cfe-495f-a457-25af7593f256`, revision 5 and complete
synthetic ownership. Its record and ingress listener are absent. Two complete
post-teardown retrieval passes returned HTTP 200 for all three metadata and
content identities; every byte count and SHA-256 matches the original
registered artifact. The records remain explicitly non-authoritative
synthetic evidence. Runtime, production, preview, Beautips and WorkSession Git
remain unchanged.

Task 5.6 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-5.6-artifact-retention`;
the SHA-256 of its `SHA256SUMS` is
`8e68d17226bff549222d9166fe1fd175fa51879ef8b52169f0846c3dd307ed55`.

Task 6.1 commits the canonical documentation change as
`0230c6b973692205ed9a17f8015cd151269d8080`, tree
`d2feee00f9ab2efdb858f77b47536dcb638e1639`, on exact branch
`atenea/session-c20f3cde-9a64-4c7b-a674-7b63f94ca475`. The AX42 worktree and
both disposable control-plane checkouts are clean.

The normal authenticated Atenea publish endpoint created draft pull request
`jlnieto/atenea#4`. GitHub reports exact base
`feature/actualizar-conversacion-en-web` at
`649edba356e104695ce7ca0713f9b86e54b02d36`, exact WorkSession head at the
commit above, one documentation commit, state `OPEN`, `isDraft=true` and
`MERGEABLE`. Atenea persisted the same branch, URL and final commit.

Delivery now creates draft pull requests and reuses a pre-existing remote
WorkSession branch only when its head equals the local head. Missing branches
use a normal non-force push; different or ambiguous heads fail closed before
GitHub mutation. The disposable control plane additionally consumes GitHub
authentication through a named token file. Its first tokenless real publish
failed closed with HTTP 502; the accepted attempt consumed an ephemeral
credential without putting it in configuration, arguments, output or
evidence. The exact secret file, external acceptance configuration, Git bundle
and transfer ref were removed immediately after use.

Focused delivery tests pass `7/7`, the named-token-file test passes `1/1` and
the final complete regression passes `397/397`. The runtime remains `UP` with
the same three containers, internal network and retained PostgreSQL volume;
the nine production/preview containers and three Beautips containers remain
unchanged.

Task 6.1 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-6.1-draft-delivery`;
the SHA-256 of its `SHA256SUMS` is
`01631b47930c76270433c15c876a0686b6af476516edddaa152e2f0eed8fae78`.

Task 6.2 merges pull request `#4` through the normal GitHub merge operation.
Merge commit `b94aacd4dae52f7567156e15710faae66062e814` has exact latest base
`849ceee3293dcc7ce461ee04a564ea12958f5dd3` and immutable WorkSession head
`0230c6b973692205ed9a17f8015cd151269d8080` as its two parents. Both are
ancestors of the merge commit. The WorkSession remote branch still points to
its original head, so no force update or branch deletion occurred.

Atenea synchronization now requires the GitHub pull request number, canonical
URL, base repository/ref, head repository/ref and head SHA to match the exact
persisted WorkSession identity. Cross-session metadata fails closed before
persistence or notification. Repeating a successful `MERGED` sync does not
emit a second merge notification. The complete regression passes `399/399`.

The real sync endpoint ran twice and returned identical material delivery
state: WorkSession `OPEN`, pull request `MERGED`, the same workspace identity,
branch, final commit and original publication timestamp. A disposable
cross-session fixture retained MD5
`d6c507484f57e9e7270c5c9bbe38bf25` across both calls, then exact ID,
ownership, SHA and MD5 preconditions removed only its project and WorkSession
records. Both fixture IDs are absent.

The ephemeral GitHub token file was deleted after the first sync; the second
used only the running control process's in-memory credential. Zero
non-terminal AgentRuns and zero push-notification log rows remain. Runtime,
production/preview, Beautips, the AX42 worktree and both control-plane
checkouts remain healthy and clean.

Task 6.2 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-6.2-merge-sync`;
the SHA-256 of its `SHA256SUMS` is
`89f10cbfd82d576cf54b94860d4586e209a4d18f4eb794603fc52879b0455b3b`.

Task 6.3 closes the canonical WorkSession through Atenea's normal reconciled
endpoint at `2026-07-29T10:01:03.245941289Z`. Final state is
`CLOSED/CLOSED`, delivery remains `MERGED`, the exact workspace identity,
pull-request URL and final commit remain persisted, and there is no close
block or retryable close state.

The shared exact PR validator now also protects close. A repository, number,
base, head or SHA mismatch blocks before checkout or branch deletion; the
complete regression passes `400/400`. Accepted close fetched origin, checked
out base branch `feature/actualizar-conversacion-en-web`, fast-forwarded to
`b18f8a38d41006728c2cdf9518e3f9af20cccc87` and removed only the merged
WorkSession branch locally and remotely.

The merged base history, pull request and AX42 worktree retain exact
WorkSession commit `0230c6b973692205ed9a17f8015cd151269d8080`. The AX42
worktree remains clean on its local WorkSession branch. The exact runtime
remains `UP` with its original three containers, internal network and retained
volume. Ten session artifacts, ten runtime logs and six retained attachment
files survive, and the accepted 5.6, 6.1 and 6.2 checksum files remain
unchanged.

An exact repository-local credential helper consumed the named ephemeral
token only for the normal remote branch deletion. Its config entry, helper,
token and external configuration were removed immediately after close.
Production/preview and Beautips fingerprints remain unchanged.

Task 6.3 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-6.3-canonical-close`;
the SHA-256 of its `SHA256SUMS` is
`49fe27c0d084a46295a2cb9803526a83ddb597b2e959433ffd0924f97078c0dd`.

Task 6.4 restarted only `atenea-agent-run-worker-v1.service`. Its PID changed
from `446520` to `538943`; the service returned healthy with the same
`project-codex-v1` capability after a finite readiness wait. The complete
durable state remained byte-identical: 28 executions, including the 13
terminal records owned by the canonical WorkSession and its three successful
turns, retained the same dispatch, execution, workspace, lease, thread,
revision and terminal identities with `reconcileRequired=false`.

No new dispatch, prompt turn, transient project unit or project process was
created. The allowlist and installed mediator/runner hashes, AX42 WorkSession
Git HEAD/tree/clean status and runtime health are unchanged. Canonical
rootless Docker inspection confirms the same three slot 3 runtime containers,
network and retained volume and the same three slot 1 Beautips containers.
The nine accepted production/preview container identities and the clean
control repository also match the sealed task 6.3 boundary.

Task 6.4 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-6.4-worker-restart-reconciliation`;
the SHA-256 of its `SHA256SUMS` is
`bc0b1ebdb7d1f6d91c4cc1833f7426808a0ea0c1b40bbf939a7a5201150abf7d`.

Task 6.5 proves the accepted Atenea boundary remains available without opening
a session or rerunning a prompt. The healthy mediator advertises
`project-codex-v1`; its exact selection/execution allowlist retains only
Atenea and the canonical workspace. Authenticated retrieval returned the three
accepted terminal turns with their original dispatch, execution, workspace,
thread and turn identities.

Immutable denial probes for Beautips, Yvateve, ISC, Recambios, Fomasys and
Checkpol each returned HTTP 403 `project_ownership_conflict`. An exact Atenea
identity with an unknown workspace progressed to the narrower fail-closed
`workspace_ownership_conflict`, proving that availability does not bypass
persisted ownership. All seven dispatch IDs remain absent and the complete
durable execution file is byte-identical at 28 records with zero non-terminal
executions. Runtime, WorkSession Git, production, preview and Beautips remain
`UP` with their accepted identities.

Task 6.5 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-6.5-atenea-availability-denial`;
the SHA-256 of its `SHA256SUMS` is
`0c8011a01b0667da1a5f1bbd03b777b7b287ced9fb0fa02887e10ab21a5ee198`.

Task 7.1 first disabled only new Atenea real-project selection. The mediator
remains healthy with synthetic compatibility but no longer advertises
`project-codex-v1`; execution is disabled and the exact persisted workspace is
retained for reconciliation.

The first mediated stop blocked before resource mutation because the adapter
required the live worktree HEAD to remain at admitted base `b605c8d`, although
normal accepted delivery had cleanly advanced it to `0230c6b`. The adapter now
validates the exact admitted base tree and requires it to be an ancestor of
the clean current HEAD while retaining exact manifest and Compose hashes.
Divergent history remains fail-closed. Focused adapter validation and the
complete project-runtime contract pass `10/10`; real status then returned
`ready/healthy`.

The accepted mediated stop returned `stopped/stopped`. A task-scoped cleanup
validated five exact ownership labels and immutable IDs before removing only
the three stopped slot 3 containers and their empty internal network. It
removed zero images and volumes. The labelled PostgreSQL volume, allocation,
`slot3/heavy1` held admission, disabled workspace registration, mirror,
worktree HEAD/tree/clean index, delivery, logs and sanitized evidence remain.
The preview projection was already absent; session listeners and transient
project/browser processes are zero. Production, preview and Beautips remain
`UP` with unchanged container identities.

Task 7.1 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-7.1-atenea-project-rollback`;
the SHA-256 of its `SHA256SUMS` is
`b5e9fbb0c7657c82ce95459bf9e0a3f6551ded59abbed32501e23fdcd77d7cb3`.

Task 7.2 repeated project disable, mediated stop and exact cleanup without
recreating the runtime. Stop remained `stopped/stopped`; cleanup removed zero
networks and every disabled allowlist, workspace, allocation, held admission,
durable execution and installed adapter hash remained byte-identical.

Four sequential task-owned network fixtures reused the absent runtime network
name with literal no labels, partial labels, complete foreign ownership and
complete Atenea labels on an ambiguous immutable ID. Each immutable ID,
creation time, driver, labels and full inspect SHA-256 was recorded first.
The cleanup gate rejected all four with exit 65
`RUNTIME_OWNERSHIP_CONFLICT`; every inspect fingerprint remained byte-identical
and the resource remained present during rejection. Each fixture was then
revalidated and removed only by its recorded immutable ID.

Final inventories for all four rootless slots exactly equal their pre-fixture
inventories. There are zero fixture/session containers, networks, owned
images, listeners or temporary project/browser processes, while the retained
PostgreSQL volume remains. Production, preview and Beautips are `UP` with
unchanged identities.

Task 7.2 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-7.2-rollback-idempotence`;
the SHA-256 of its `SHA256SUMS` is
`2db41894002dd536c2719a7d04f889218bfee595a35605095242efe095c5920b`.

Task 7.3 released `heavy1` before `slot3` through the versioned admission
boundary. The retained admission record is now `released/released`, capacity
reports zero normal and heavy use, and idempotent release verification returns
the same state.

After exact cleanup and capacity release, the allocation record was copied
byte-for-byte into accepted evidence at SHA-256
`bd45cac9d22f03ccdf2ef0d2759d850e6200c094953e8d37f419160c5e961e29`.
The original persisted allocation remains unchanged. The disabled worker
registry then unregistered only the exact canonical session/workspace identity
and now contains zero workspaces.

The mirror refs, WorkSession HEAD/tree/clean index, control source, merged
GitHub pull request `#4`, persisted workspace record, durable terminal
executions, retained PostgreSQL volume and all sealed task evidence remain
unchanged. Runtime/preview/listener/project/browser process counts remain zero;
production, preview and Beautips are `UP` with unchanged identities.

The accepted release operations emitted harmless inaccessible inherited-cwd
warnings after their successful fixed record scans, and one post-copy shell
substitution probe was malformed after the archive had already been copied.
Both are retained transparently. Clean idempotent release and direct
persisted/archive SHA comparison supersede those non-mutating warnings.

Task 7.3 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-7.3-release-allocation-archive`;
the SHA-256 of its `SHA256SUMS` is
`da48445d6f83f99119e587e6a10a5325baa0aa4fc959c61f73b49b383aa2d0aa`.

Task 7.4 proves slot 1/Beautips and the complete slot 2 and slot 4
container/network/volume inventories are byte-identical to the sealed entry
gate. Slot 3 has no session container, network, owned image or listener and
retains only the expected labelled PostgreSQL volume beyond its unchanged
default networks.

All three RAID1 arrays report `[UU]` with filesystem headroom. SSH, Tailscale,
UFW, the three private mediators, worker-health timer, four rootless daemons
and four stable socket proxies are active. Rootful Docker, its socket and
containerd remain inactive/masked, `/var/run/docker.sock` is absent and the
database lifecycle gate remains disabled.

UFW has the eight original IPv4/IPv6 base rules plus exactly four reviewed
IPv4 tailnet-only mediator rules: attachment `8788`, AgentRun `8787`, preview
control `8789` from the control-plane address and preview ingress
`19000:19031` from the tailnet CIDR. The expected nftables hash therefore
differs from the pre-install entry hash only after these reviewed additions.

Production and preview retain the same nine full container identities and both
actuator probes are `UP`. Beautips retains its three full identities and is
`UP`. Programme, Atenea source and WorkSession Git are clean and synchronized
at their expected heads.

Task 7.4 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-7.4-final-nonimpact`;
the SHA-256 of its `SHA256SUMS` is
`70a039182d7400e8f80f9d0640e35ebf2389c4fc40e2dc8827ae9a5044116b75`.

Task 7.5 passes two independent regression cycles. Each worker/manifest cycle
passes the AgentRun worker `11` tests, project runner `6` tests, positive
Atenea adapter, negative policy corpus and complete project-runtime contract
`10/10`. Each focused Atenea cycle passes all `15` remote-worker tests.

Both complete canonical containerized Atenea regressions pass `400/400` with
zero failures, errors or skips. The first durations were 187 seconds for
worker/manifest, 8 seconds focused and 33 seconds complete; the second were
188, 7 and 35 seconds.

One ignored two-file Python `__pycache__` generated by a nested focused suite
was removed by its exact directory after both accepted passes. Final programme
and Atenea Git, installed/source adapter hash, disabled zero-workspace worker
registry, released admission, durable executions, empty slot 3 projection,
retained volume and production/preview/Beautips health match the pre-test
fingerprint exactly.

Task 7.5 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-7.5-double-regressions`;
the SHA-256 of its `SHA256SUMS` is
`c39f1e78f4a57660c6975c0fc07bc09a22da21ed316b6d3884d0e31abee21091`.

Task 8.1 revalidates all `20/20` preceding accepted evidence packages
file-by-file. The rollup covers `381` files and `743150` bytes; all `85/85`
modern command metadata records contain the sanitized command description,
start/end timestamps, duration, exit code and finite timeout. It indexes the
two retained Playwright screenshots and `97` command/meta ledger files.

The prohibited-material audit finds zero forbidden filenames and zero risky
content files. The final boundary remains disabled with zero registered
workspaces, released normal/heavy admission, clean synchronized programme and
Atenea Git, intact task 7.5 evidence and all three production, preview and
Beautips health probes `UP`.

Task 8.1 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-8.1-evidence-rollup`;
the SHA-256 of its `SHA256SUMS` is
`1fede0bb37fdac444d10a7cd50c031aaca1abe244e25d18f99c063609a644512`.

Task 8.2 applies D-038's finite 15-minute close window. Normalized samples at
minute `0`, `5`, `10` and `15` are byte-identical. Across all four, project
selection/execution stays disabled with zero registered workspaces, normal and
heavy admission stay released, all `28` durable executions remain terminal
and the exact session runtime, preview, listener, transient unit and browser
process projection remains zero.

The canonical PostgreSQL volume, allocation, admission, workspace, worktree,
mirror and all accepted artifact files remain intact. All four rootless slot
inventories, three RAID arrays `[UU]`, the reviewed firewall, base/mediator
services and inactive/masked rootful Docker boundary remain unchanged.
Atenea production, preview and Beautips retain their exact container
identities and all three health probes remain `UP`.

The first pre-window harness invocation exited `1` before an accepted sample
because it used a nonexistent mirror path and lowercase terminal-state names.
That read-only diagnostic is retained transparently. The corrected harness
uses the canonical mirror and uppercase protocol states, restarted at minute
zero and completed all four accepted samples without drift.

Task 8.2 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-8.2-disabled-clean-observation`;
the SHA-256 of its `SHA256SUMS` is
`151165a3f9d8a045e53382dedc7b9619d0c62093e6d2b25436f8f176050ac40c`.

Task 8.3 passes pre-archive strict validation at `45/45`. OpenSpec creates the
`atenea-project-onboarding` capability, applies five reviewed requirement
updates and archives the completed change as
`2026-07-29-onboard-atenea-on-ax42`. Post-archive strict validation passes all
`10/10` authoritative specifications and reports zero active changes.

The first pre-archive count assertion exited `2` after successful validation
because an empty pending-task match was not normalized to integer zero. A
subsequent archive attempt exited `1` without changing files because the
active safety delta referenced a requirement title superseded by an earlier
archive. The delta was reconciled with the authoritative
`Least-privilege execution` and `Safe garbage collection` requirements while
preserving their existing scenarios. OpenSpec then archived successfully. Its
generated trailing blank lines caused one post-archive `git diff --check` exit
`2`; those three formatting-only lines were removed and the complete strict
and boundary validation passed. All diagnostics are retained transparently.

Atenea source remains clean and synchronized at
`b18f8a38d41006728c2cdf9518e3f9af20cccc87`; its explicit push was already
up-to-date. The worker remains disabled/released, task 8.2 evidence revalidates
and production, preview and Beautips remain `UP`.

Task 8.3 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-atenea-on-ax42/runs/task-8.3-openspec-archive`;
the SHA-256 of its `SHA256SUMS` is
`79475771be27cc2297f38fc4cec0b15b3f77a46bf834b188f1bc72aab300eb13`.

The exact resume point at the Atenea archive boundary was the entry gate for a
separate `onboard-beautips-on-ax42` change. At that boundary it had not yet
been created and Beautips managed routing had not been enabled or modified;
the separate entry gate below begins from that preserved state.

## Phase 8 progress: onboard-beautips-on-ax42

Tasks 1.1–3.5 are complete and change progress is `20/45`. GitHub
`jlnieto/beautips`, branch `main`, managed-manifest commit
`e9e0b3c319c518363d4135f5378ebbddced96dfb`, tree
`533d32f97ae362997ad003170a826da674c31c1d`, runtime manifest SHA-256
`365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82`
and managed Compose SHA-256
`840e64166e8e1ddaefb74d11763fe150e6539074bb02c3173e2175a446555941`
are pinned. The entry commit and legacy manifest remain recorded as reviewed
ancestors, not as enabled runtime authority.

The clean laptop commit `a6d2f28` and clean Atenea commit `bd15a16` were strict
ancestors of GitHub; AX42 already matched. Only ancestry-proven
`pull --ff-only` was used. All three copies are now clean and synchronized at
the pinned head without merge, reset, force update, deployment or container
restart.

The healthy administrative Beautips pilot remains three rootless slot 1
containers with its existing network, loopback listener, root-owned manual
secret boundary and four persistent PostgreSQL/Redis/assets/imports volumes.
These are foreign retained controls and cannot be registered, mounted,
relabeled, snapshotted, stopped or cleaned by the managed WorkSession.

The managed acceptance will use another admitted slot with empty migrated
PostgreSQL, disposable Redis, invented versioned tenants/users/loyalty/files
and distinct session volumes. Current manual data, backups, legacy dumps,
production rows and credential values are excluded. WhatsApp embedded signup,
webhooks, scheduler, outbox and external Graph API access remain disabled.

The two local backup folders are on the laptop root filesystem, and AX42 has
no restic, borg or rclone target. They are not an independent restore-tested
backup, so all non-Git acceptance state remains synthetic, non-authoritative
and cleanup-bound. The pilot declares no required localhost compatibility;
any origin/cookie/redirect failure blocks and requires an explicit manifest
revision.

Managed project selection/execution remains disabled with zero registered
workspaces. Atenea production/preview, the administrative Beautips pilot, all
four rootless daemons, RAID `[UU]`, firewall and inactive/masked rootful Docker
remain healthy and unchanged.

Task 2.1 removes the legacy fixed port, project, Docker network, tracked
env-file and cleanup-disabled command contract. The accepted runtime-contract
v1 manifest points only to a separate managed Compose definition. That
definition receives the WorkSession identity, runtime identity, loopback web,
PostgreSQL and Redis ports, network and four volume names from persisted
allocation values and applies complete ownership labels. It declares an
internal network, disables restart and WhatsApp scheduling/credentials, and
requires stop cleanup of volumes, orphans and locally built images. The manual
`docker-compose.yml` remains byte-identical.

Draft 2020-12 schema validation, Compose non-interpolating validation and
static denial of the legacy literals pass. Two identical synthetic allocation
calls produced byte-identical session paths, Compose/network/volume names and
three unique loopback ports; the fixture was then moved to trash. The mandatory
local `dev redeploy beautips` completed and local health is `UP`.

The AX42 registry remains
`selectionEnabled=false`, `executionEnabled=false`, with zero workspaces. The
three exact slot 1 administrative container identities remain running and
Beautips health is `UP`. Atenea's exact nine production/preview identities
remain running and both health probes are `UP`. Two unrelated
`atenea-onboard-task4-*` containers observed on the control-plane host were
retained unchanged and excluded from this task.

Task 2.1 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-2.1-session-safe-manifest`;
the SHA-256 of its `SHA256SUMS` is
`43ca64baf25a2a4f76a51f5ca0362cc4640ca02dedc9b0d19683beb070e8d621`.

Task 2.2 adds one closed source allowlist containing exactly Beautips. It pins
GitHub repository, branch, commit, tree, runtime manifest, managed Compose,
worker `ax42-01`, normal workload and allowed slots 2–4. Both selection and
execution are false, slot 1 is excluded and the workspace map is empty.

The allowlist SHA-256 is
`e3ad1824c7a134280f907b2831b75391c3791373060806fb1827dc05cb6756fc`;
its exact Draft 2020-12 schema SHA-256 is
`1fc4d61a46e10ea9a6b7201573daef5b50267f13d252e20c6dab062e6fee10e2`.
Valid-format foreign repository, branch, commit, tree, manifest, Compose,
worker, slot, workspace, project and unknown-field mutations all fail schema
validation.

The registry remains source-only and was not installed on AX42. The installed
Atenea registry retains SHA-256
`26a7d75cc4c3d919b82ee6efeb8e7d4214e53d4854ad34dc1985d36aceb7a94a`,
is disabled with zero workspaces and the existing worker remains active.
Focused worker and project-runner regressions pass `11/11` and `6/6`.
Administrative Beautips and Atenea production/preview retain their exact
running identities and all health probes are `UP`.

Task 2.2 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-2.2-disabled-exact-allowlist`;
the SHA-256 of its `SHA256SUMS` is
`a4c60545032856054db1b9980a83cfb1413fddde59c632aacc09998d5555b9d2`.

Task 2.3 adds a closed, source-only mediator for exactly ten reviewed
operations: Node build, Maven test, Compose build, runtime
start/health/logs/stop/cleanup and functional/customer smoke. It accepts only
the canonical WorkSession UUID plus a symbolic operation and derives every
other identity from persisted allocation and the exact source allowlist.

All plans have a finite timeout, named synthetic secret references and
`executionEnabled=false`. Caller commands, paths, endpoints and environments
are not request fields. The plan schema rejects unknown fields; the mediator
rejects unknown operations, `slot1`, noncanonical sessions, duplicate or
foreign ports, project/path/Git drift and altered manifest or Compose before
operation execution.

The two Beautips smoke scripts now have a managed mode that does not load
repository `.env` and requires explicit named inputs. Manual mode remains
unchanged. Mandatory local redeploy completed and health is `UP`. Four focused
mediator tests, worker `11/11` and runner `6/6` regressions pass. The mediator
and updated allowlist remain absent from installed AX42 paths; the existing
Atenea registry is still disabled with zero workspaces. Administrative
Beautips and Atenea production/preview retain exact running identities and all
health probes are `UP`.

Task 2.3 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-2.3-reviewed-operation-mediator`;
the SHA-256 of its `SHA256SUMS` is
`940e412d45833ce2a25223e4a279966258faedf57c77e4f2f7ae377f9f3c1e1f`.

Task 2.4 adds an exact Beautips identity adapter around the already accepted
Codex runner. The adapter pins the base runner SHA-256
`de84b0c96908677e334184b9290691a2116b963dd37483022f97a0fd57ed44d1`
and changes only project, repository, branch, commit, manifest and canonical
Git common-directory identity. Its own SHA-256 is
`55e8f585e19f6a19d3c51aaf7532b1cf0f74f6b087ae0d1ef67faaea3029b73b`.

The inherited execution boundary retains the transient systemd cgroup,
Bubblewrap workspace-write namespace, exact WorkSession worktree and Beautips
mirror mounts, finite timeout, cancellation and thread continuity. It denies
loopback, RFC1918, Tailscale and link-local destinations and does not mount the
manual Beautips workspace, Docker socket or `auth.json`.

Four focused adapter tests pass exact config/workload, real
Git/manifest/allocation fingerprints, foreign identity denials and sandbox
mount/network assertions. The accepted base runner remains `6/6`. The adapter
is not installed, no Codex process ran and the installed Atenea registry stays
disabled with zero workspaces. Administrative Beautips and Atenea
production/preview remain `UP`.

Task 2.4 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-2.4-exact-codex-sandbox`;
the SHA-256 of its `SHA256SUMS` is
`d4853f1712db7130fdb3442957684e4799977774de567448f304835dec9de37b`.

Task 2.5 adds a WorkSession-derived synthetic secret boundary with exactly
four separate names: PostgreSQL password, smoke administrator email/password
and smoke seal code. The exact directory is mode `0700`; files and value-free
metadata are mode `0600` under the worker service identity. Preparation is
byte-idempotent and outputs only names plus `valuesExposed=false`.

The tool accepts no caller value, env file or path. Ambient manual/WhatsApp
variables are ignored. `.env`, WhatsApp, token, cookie, unknown, symlink,
partial and unsafe-mode entries all reject the boundary. Three focused tests
and the four dependent mediator tests pass; generated values lived only under
automatically removed `/tmp` roots and none entered evidence.

The secret tool remains uninstalled, no real WorkSession secret was generated,
the installed Atenea registry remains disabled with zero workspaces and
administrative Beautips health is `UP`.

Task 2.5 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-2.5-synthetic-secret-boundary`;
the SHA-256 of its `SHA256SUMS` is
`0ab26949f1ba66c1f44a2fbe5375dc49aabd88c0914cdef9ca8fe4649150cb3c`.

Task 2.6 installs a durable, default-disabled Beautips lifecycle boundary on
AX42. It installs the exact mediator, project runner, secret boundary,
operation registry and immutable source allowlist under
`/usr/local/libexec/atenea`, plus a separate runtime config with
`selectionEnabled=false`, `executionEnabled=false` and zero workspaces. The
sudoers boundary names only the exact Beautips runner and config.

Plan, apply, verify, selection-enable, enable, disable and rollback are
implemented. Repeated apply is byte-idempotent. Enable without exactly one
persisted workspace fails with exit `65`; modified installed artifacts fail
closed and remain untouched; rollback removes only exact disabled Beautips
artifacts and preserves the shared base runner. The installed lifecycle tool
also verified and rolled itself back after the deployment staging bundle was
removed, then was installed again in the final disabled state.

AX42 listener and UFW digests are identical before and after. The installed
Atenea registry and shared runner hashes are unchanged, the worker service
remains active, and the administrative Beautips, Atenea production and Atenea
preview health probes are `UP` with their retained exact identities. No
runtime, workspace, listener, firewall rule, service restart or routing was
created.

Task 2.6 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-2.6-install-lifecycle`;
the SHA-256 of its `SHA256SUMS` is
`eb1d01c689a09e2936eea19f7a792c289e88102e7b5e2d60acf6744c4d3e2e28`.

Task 2.7 adds an aggregate Beautips worker-contract regression. Three focused
cases validate the Draft 2020-12 manifest, exact cleanup argv, private
no-localhost preview, three service identities, loopback ports, internal
network, four session-labelled volumes and absence of fixed containers,
manual paths or env files. Two mediation calls produce an identical cleanup
plan bound to the exact slot socket, Compose project, WorkSession and runtime.

The aggregate also passes mediator ownership `4/4`, Codex identity/sandbox
`4/4`, secret boundary `3/3`, the complete install lifecycle and inherited
project idempotence/cancellation/restart `3/3`. The final source passes locally
in `71.38 s` and twice on AX42 in `20.75 s` and `17.86 s`.

The administrative checkout remains intentionally at its retained entry
commit and was not used as the managed source. AX42 tests used an exact
temporary checkout of the pinned managed commit and removed it afterwards.
Ubuntu `python3-jsonschema` and `python3-pyrsistent` were installed as the
missing worker test prerequisite; apt reported no service or container
restart. All temporary roots and test processes are absent.

Final Beautips selection/execution remains false/false with zero workspaces.
AX42 listener/UFW fingerprints, the active worker, exact administrative
Beautips identities and Atenea production/preview identities and health are
unchanged.

Task 2.7 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-2.7-worker-contract-tests`;
the SHA-256 of its `SHA256SUMS` is
`5978278bc7db11f36258040bd3969d2c85fc6196c9afac5d7c65feef2bb97983`.

Task 3.1 adds a separate default-false Beautips control-plane gate. Exact
project name `Beautips`, canonical repository path, project branch `main` and
WorkSession base branch `main` select `project-codex-v1` only when the global
worker gate and the Beautips gate are enabled and AX42 advertises that
capability. The existing real-project gate cannot select Beautips. Partial or
foreign name, path or either branch remain local without contacting the
worker; missing capability also fails closed.

Atenea source is committed and synchronized at
`2f92c7ba8d869d79ed3a12f5758661d01174f7c7`, tree
`88e348b688f63a6f0ae6e827d817fe60aa93fe86`. Its laptop and server checkouts
were reconciled by exact ancestry guards and `ff-only`. The Beautips
control-plane checkout was likewise ancestry-reconciled to the already
accepted GitHub commit and tree. Focused selector tests pass `11/11`; the
selector, client and AgentRun set passes `26/26` both locally and from the
committed Atenea server source.

No production deployment, selector environment, WorkSession, AgentRun, lease,
routing record or database mutation was created. Production and preview
actuator checks are `UP` over their loopback-published ports. AX42 retains
three exact administrative Beautips containers in slot 1, empty slots 2–4,
four active rootless daemons, RAID `[UU]`, inactive/masked rootful Docker and
administrative Beautips health `UP`. Canonical identity persistence before
dispatch remains exclusively task 3.2.

Task 3.1 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-3.1-exact-beautips-selection`;
the SHA-256 of its `SHA256SUMS` is
`1558e608f35e6b9412bfdc4e1dbfb3eea0af0b62a89ffd44848f14cf8c3142f9`.

Task 3.2 reuses the additive remote-routing persistence model and adds no
database migration. An exact Beautips queued run now persists target
repository path, selected worker, remote session UUID, derived workspace,
workload, project identity, repository URL, branch, accepted commit and
manifest SHA-256 before dispatch registration. The remote execution identity
remains null until the worker accepts that persisted dispatch UUID.

Acceptance requires worker `ax42-01`, workload `project-codex-v1` and workspace
`remote:ax42-01:work-session:<remote UUID>` in addition to the exact project
and branch identity from 3.1. A foreign workspace fails before
`AgentRunRepository.save`. Atenea project persistence and synthetic
compatibility remain passing.

Atenea source is clean and synchronized at
`dab379b3d11cfacd2e1714d4f56dc1210948d5c5`, tree
`9068166413e9ab85ba4829e4929b1a0e43303c4c`. The focused persistence,
selection and client suites pass `28/28` locally and `28/28` from the
committed server checkout. No deployment, database write, real WorkSession,
AgentRun or remote dispatch occurred.

Production and preview actuator checks remain `UP`; no Beautips selector key
is deployed. AX42 retains boot identity, RAID `[UU]`, slot 1's three exact
administrative Beautips containers, empty slots 2–4 and administrative health
`UP`.

Task 3.2 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-3.2-persisted-beautips-identity`;
the SHA-256 of its `SHA256SUMS` is
`add7b084323e1af19cc3c85c23289aa15eff0cadf91060aca93a191f6b2c5d3f`.

Task 3.3 extends the existing exact project-payload acceptance to Beautips
without a new protocol or endpoint. Repository, branch, commit and manifest
are read only from the persisted AgentRun; its dispatch UUID remains the
idempotency boundary and the WorkSession external thread ID is forwarded for
continuation. Caller command, path, endpoint and environment are absent.

The existing project-neutral coordinator maps a Beautips terminal success to
the saved WorkSession thread, AgentRun external turn and exactly one CODEX
result turn. A second observation after terminal returns without redispatch
or duplicate turn. Existing Atenea project mapping and four-field synthetic
payload compatibility remain passing.

Atenea source is clean and synchronized at
`dc6d5ef2f037e6b88d7fa63107622d5859aceb5b`, tree
`92d8123f34b9a17d9afc96813eccfb197dfd8415`. The focused payload,
coordinator, persistence and selection set passes `34/34` locally and `34/34`
from the committed server checkout. No real dispatch, production database
write, deployment or routing activation occurred.

Production and preview remain `UP` with zero backend restarts and no deployed
Beautips selector key. AX42 retains three `[UU]` arrays, slot 1's three
administrative containers, empty slots 2–4 and Beautips health `UP`.

Task 3.3 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-3.3-idempotent-turn-mapping`;
the SHA-256 of its `SHA256SUMS` is
`e0d4a5e7a8de473027845dd181d126adc6c96b210001273475635203cb4c41c8`.

Task 3.4 keeps the coordinator state machine unchanged and adds exact
Beautips continuity cases. Cancellation uses only the persisted execution
identity and does not redispatch. Startup reconciliation polls the persisted
execution without replacement. A bounded partition fails the same run with
explicit operator review and no reassignment. Re-observing a terminal
Beautips run creates no duplicate dispatch or result turn.

Equivalent Atenea cases remain in the suite. The focused coordinator set
passes `8/8`; coordinator, client, persistence and selection pass `37/37`
locally and `37/37` from the committed Atenea server checkout at
`9e264e3820d6803225d57139150e1df990d9e09e`, tree
`6046d03ac3067aad54ba9127faccd4d099e51454`.

No real restart, dispatch, cancellation, production database write,
deployment or routing activation occurred. Production and preview remain
`UP` with zero backend restarts. AX42 retains three `[UU]` arrays, slot 1's
administrative Beautips runtime, empty slots 2–4 and Beautips health `UP`.

Task 3.4 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-3.4-continuity-semantics`;
the SHA-256 of its `SHA256SUMS` is
`946bf1d692bb7156beb014f991197928c41e0299780164377af691df090d4a9a`.

Task 3.5 closes control-plane allowlisting at Atenea commit
`9e264e3820d6803225d57139150e1df990d9e09e`, tree
`6046d03ac3067aad54ba9127faccd4d099e51454`. Exact selection, payload,
persistence, delivery, denial, Atenea project and synthetic compatibility
pass in two final focused runs of `37/37`, lasting `7.32 s` and `7.50 s`.

Two fresh full Atenea runs pass `411/411`, lasting `56.21 s` and `47.88 s`.
Each used an internal labelled network, PostgreSQL 16 container and isolated
workspace volume, applied all 49 migrations, and removed every fixture after
exact name, ID and label verification. Final task container, network, volume
and diagnostic workspace counts are zero.

The first full harness omitted `ATENEA_WORKSPACE_ROOT`; the application
correctly rejected integration repositories outside its configured `/repos`
root, producing 25 expected-boundary failures. A one-case diagnostic proved
the harness mismatch. Instrumentation was removed byte-for-byte, the accepted
harness mounted `/workspace/repos` independently with the canonical root, and
both complete passes then succeeded. No diagnostic response body or generated
credential was retained.

Atenea production and preview remain `UP` with zero backend restarts. The two
unrelated task4 containers retain exact identities. AX42 retains RAID `[UU]`,
slot 1's administrative Beautips runtime, empty slots 2–4 and Beautips health
`UP`. No production deployment, real WorkSession, dispatch, database write or
routing activation occurred.

Task 3.5 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-3.5-double-regression`;
the SHA-256 of its `SHA256SUMS` is
`fe03bba5fc28349dc87486a98e602130731d0fd3bb81b28e09f816c03b8c0550`.

Accepted sanitized entry evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/entry-gate`;
the SHA-256 of its `SHA256SUMS` is
`87fe021a4e9ba914d7ca2cb8e12910b2eb184cde3f4d5783ed05af2067a183e6`.

Task 4.1 creates the canonical managed Beautips identity without starting a
runtime. A fresh isolated Atenea control plane persists one remote WorkSession,
`6375c738-99da-4ef3-91f5-21e30d3b27d3`, for exact project `Beautips`,
worker `ax42-01`, workload `project-codex-v1`, repository
`https://github.com/jlnieto/beautips.git`, branch `main` and workspace branch
`atenea/session-6375c738-99da-4ef3-91f5-21e30d3b27d3`. Its fresh PostgreSQL
contains one project, one open WorkSession and zero AgentRuns or SessionTurns.

The AX42 service identity fetched the private canonical repository through an
ephemeral mode-0700 Git credential-cache boundary fed by the already
configured operator credential. No credential value was emitted or persisted;
the daemon, socket and directory are absent after provisioning. The canonical
bare mirror has exact HTTPS origin and remote-only fetch mapping. Mirror,
worktree and manifest resolve to commit
`e9e0b3c319c518363d4135f5378ebbddced96dfb`, tree
`533d32f97ae362997ad003170a826da674c31c1d` and manifest SHA-256
`365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82`.
The worktree is clean.

Historical persisted allocations retain slots 2 and 3, so the new normal
admission correctly holds the only unclaimed managed slot, slot 4. Allocation
reserves three collision-free loopback ports but starts no runtime: session
container, network and listener counts are all zero. Project execution remains
disabled with zero registered workspaces. The administrative slot 1 Beautips
pilot, production, preview, prior Atenea acceptance resources, rootful Docker
state and canonical Atenea checkout are unchanged and healthy.

Task 4.1 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-4.1-canonical-mirror-allocation`;
the SHA-256 of its `SHA256SUMS` is
`261a48fea26345289f454c323e0a86cb20ee03bf27a599cfd244e3373b246f98`.

Task 4.2 closes the real worker composition exposed by the first canonical
session. `project-codex-v1` now resolves through independent root-owned Atenea
and Beautips routes. The existing Atenea route remains
`selectionEnabled=false`, `executionEnabled=false` with zero workspaces.
Beautips alone is enabled with the single immutable key
`remote:ax42-01:work-session:6375c738-99da-4ef3-91f5-21e30d3b27d3`.
Its registry record binds that UUID, the exact worker-owned worktree and
allocation SHA-256
`0e46cc38968509fbdd6585e3741f8c8e1eecb32f0161139400ec923780f49dbc`.

The lifecycle now registers only after validating file ownership/modes,
canonical Git common directory and HTTPS origin, branch, commit, manifest,
normal slot 2–4 allocation and exact session/workspace identity. Unregister
requires disabled execution plus the same one-key identity. The installed
worker, systemd unit and final lifecycle SHA-256 values are respectively
`fd5784155fcfe477599c72751fc0cc7064322cea25728de7573ac3c47ef46de4`,
`aa17d70b2c482aaa329778c0629f00f4ab6db8a58233de9d7e41b2d17ed92536`
and
`7f5bc41255bdfb2feaf1823a50ce4a9a7aca6951fa7ed49ce7d88d7e17481d98`.
The existing Tailscale-only worker listener, token boundary, capacity and 28
durable terminal records were retained.

The final complete AX42 aggregate passes all manifest, mediator, sandbox,
secret, lifecycle and four selected worker-route cases in `18.230 s`.
Lifecycle ownership hardening separately passes in `1.796 s`. Six
authenticated negative requests reject the administrative pilot, a complete
foreign Beautips workspace, an ambiguous session/workspace pair, disabled
Atenea, a foreign project and an arbitrary command field with exact HTTP
400/403 closed errors in `59 ms`. Worker state remains byte-identical before
and after all denials. No accepted execution or prompt was submitted.

The canonical worktree remains clean at its accepted commit and has zero
runtime containers and networks. The administrative Beautips pilot,
production, preview, isolated control plane, prior Atenea acceptance
resources and canonical Atenea source remain unchanged and healthy.

Task 4.2 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-4.2-exact-session-enable-denials`;
the SHA-256 of its `SHA256SUMS` is
`f46712a552456436595f7ac348395547e978df6c3474d1a42474a60baa1a572a`.

Task 4.3 submits the first and only accepted Beautips operator turn at this
gate. The deterministic prompt required exactly one new file,
`docs/ax42-onboarding-acceptance.md`, containing the single line
`AX42 Beautips onboarding acceptance.`, no other change, commit or push.
AgentRun 1 reached `SUCCEEDED` in `36.743 s` with dispatch
`7f08985d-2dd9-4c8a-addb-b12176d5e743`, execution/turn
`a8f7ffaf-2a44-4cbb-a344-a8b4a183a968`, thread
`019faf5f-0a96-7592-a936-583cb044dae8` and exact answer
`BEAUTIPS_TURN_1_OK`.

The target is 37 bytes, one line and has SHA-256
`83368013af053c2ede88faf4728abf9a30ddf352fbc47a09ad91707f63166fd3`.
The full pre-turn content manifest is byte-identical after the turn when that
single target is excluded. Worktree HEAD, tree and index are unchanged, and
Git status contains only the expected untracked file. Workspace, allocation,
admission and registry fingerprints remain exact. Atenea persists one
AgentRun, one operator turn and one Codex result turn with the complete
canonical Beautips identity.

No session runtime container or network was created. The isolated control
project/source and canonical Atenea checkouts remain clean. Administrative
Beautips, production, preview and prior foreign acceptance controls remain
unchanged and healthy. No second turn, duplicate replay, commit or push
occurred.

Task 4.3 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-4.3-deterministic-first-turn`;
the SHA-256 of its `SHA256SUMS` is
`d163c82caf3daac1668d672b731e800bdd625998c165ef2e3b0bd3e38d4142bf`.

Task 4.4 continues the same Beautips WorkSession with a deterministic second
turn. AgentRun 2 appended only
`Turn 2: same Codex thread continued idempotently.` and reached `SUCCEEDED` in
`28.528 s`. Dispatch `7bcc0c89-d94d-4fdf-bc5c-e5d3a71b5c12` maps to
execution/turn `f27cc38e-0aa0-4b30-9dce-3b540fee139b`; both its thread input
and result equal the first turn thread
`019faf5f-0a96-7592-a936-583cb044dae8`. Its exact answer is
`BEAUTIPS_TURN_2_OK`.

The target now contains exactly the two accepted lines, 87 bytes, with
SHA-256
`28fc81714c03aa8d640c01cb1cdc6f47a1a129aff143f0a3cc9aa691a3438eaf`.
HEAD, tree and index remain unchanged and Git status still contains only this
untracked file.

Replaying the complete immutable second request with the same dispatch ID
returned HTTP 200 in `11 ms`, the same execution ID, terminal status and
revision 5. Worker state remained byte-identical at 30 records and the target
SHA-256 did not change. Atenea retained exactly two AgentRuns and four turns
before and after replay, with one result turn per run; no duplicate terminal
delivery was created.

Workspace, allocation and admission fingerprints remain unchanged and no
session runtime resource exists. Administrative Beautips, production,
preview, canonical Atenea and the isolated control-plane checkout remain
unchanged and healthy.

Task 4.4 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-4.4-thread-continuity-idempotence`;
the SHA-256 of its `SHA256SUMS` is
`f02bde3306fea98eacf0109bc67fb64f275ee8923fbee09e688fbbf1fdc39d8b`.

The exact resume point is task 4.5 of `onboard-beautips-on-ax42`.

Task 4.5 is complete and change progress is `25/45`. Two independent, finite
SSH observer processes read the same isolated non-production Atenea state in
sequence. Observer A exited completely before observer B started; the detached
interval contained zero matching observer processes and zero established
connections to the isolated control plane. The processes had distinct process
IDs while their normalized WorkSession, AgentRun, turn, Codex thread and
workspace observation SHA-256 values were identical at
`5088c79bc22085fde50582ef0d8c887f8d0f52c095554deb7886659230d0a58e`.

The WorkSession remained `OPEN` with the same remote execution target,
`ax42-01` worker, external thread and exact workspace identity. The isolated
control plane retained exactly one WorkSession, two successful AgentRuns and
four turns. No prompt or worker execution was submitted during observer
reconnection.

AX42 worker state and its 30 retained execution records remained
byte-identical. Worktree HEAD, tree, index, expected two-line untracked target,
workspace registry, allocation and admission fingerprints remained unchanged.
No session runtime resource was created. Administrative Beautips, production,
preview and the isolated control plane remained healthy. Synthetic
authentication values were transient and are absent from evidence.

Task 4.5 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-4.5-observer-reconnect`;
the SHA-256 of its `SHA256SUMS` is
`17dd85832424cc065eb537ae66f3d4f3355bf8579887dd88361d8d5610a89b22`.

The exact resume point is task 5.1 of `onboard-beautips-on-ax42`.

Task 5.1 is complete and change progress is `26/45`. The canonical Beautips
commit `e9e0b3c319c518363d4135f5378ebbddced96dfb` passed the fixed Node 22
CSS build and the complete Maven 3.9.9/Java 21 suite on assigned rootless
`slot4`. Surefire retained 30 tests with zero failures, errors or skips. Both
acceptance commands used the exact reviewed digest-pinned plans with
`--network none` after public dependencies were populated only in this
WorkSession's isolated cache.

The assigned slot receives traverse-only ACLs on shared ancestors and access
only to the exact WorkSession worktree and cache. Ownership, group, allocation,
workspace record and Git remain unchanged. Pre/post fingerprints prove the
same HEAD, tree, index, expected two-line untracked target, allocation,
workspace, worker state and 30 durable worker executions. Slot4 retained zero
task containers and only its three default networks.

Preflight exposed three closed plan defects before acceptance. Git now receives
an invocation-local `safe.directory` for only the already validated worktree;
npm explicitly uses `/workspace/.npm`; and Maven uses the canonical
`-Dfrontend.build.skip=true` because Node has already built CSS. Focused
mediator and installer tests pass. Installed mediator and lifecycle SHA-256
values are respectively
`6cbb65d4b667c08220e40b4b03df5d0143c28bf444fed1bf1305c22ba61917da`
and
`c85d05a2220b9d42a8696884669d83996159e7dae15876bd66e531b7b93d26be`.

The failed attempts stopped at their documented boundary; all partial ACL
projections were reverted before retry. One diagnostic container was removed
by its recorded immutable ID. No failed or passing attempt changed production,
preview, administrative Beautips, other slots, foreign WorkSessions, routing
or canonical Git. Production, preview, administrative Beautips and the
isolated control plane remained healthy.

Task 5.1 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-5.1-canonical-build-tests`;
the SHA-256 of its `SHA256SUMS` is
`e3b5c63bd6b7de888504a0807d806201d16aa25de01cb5aaa1c37728b9791ae8`.
Five sealed blocked-attempt directories remain beside it with their own
integrity manifests and are indexed by the accepted attempt ledger.

The exact resume point is task 5.2 of `onboard-beautips-on-ax42`.

Task 5.2 is complete and change progress is `27/45`. The exact Beautips
WorkSession now owns three running rootless slot4 containers, one internal
network, four labelled PostgreSQL/Redis/assets/imports volumes and one local
Compose application image. PostgreSQL and Redis are healthy and the app
actuator is `UP`.

PostgreSQL applied 41 source migrations. All tenant, user, customer, loyalty,
import, credential, channel, outbox and event tables remain empty. Thirteen
rows exist only in three migration-defined static catalog tables; these are
versioned schema bootstrap records, not fixtures or production-derived data.
Redis `DBSIZE` is zero.

Docker retained the requested `PortBindings` but RootlessKit created no host
listeners for the `internal=true` network. A finite 300-second wait closed.
RootlessKit records 1, 2 and 3 now forward only allocation-derived
`127.0.0.1` ports 21379, 25592 and 23826 to the exact app, PostgreSQL and Redis
container IP/ports. The runtime network remains internal with no egress; the
three complete records are retained for reconciliation and exact cleanup.

The first secret preparation stopped before values or Docker resources because
setgid inheritance produced directory mode `2700`. The exact empty directory
was removed. Creation now normalizes mode `0700` after ownership and the
regression test covers a setgid parent. Four synthetic named values plus
value-free metadata are retained as `0600`; no value appears in evidence.

Current installed secret boundary, source allowlist, mediator and lifecycle
SHA-256 values are respectively
`acbbb58f5ead82f47288fa499009c46797655bd277071d57e21b5c6ccfd504f6`,
`696a00eae3d35f9e54d3eebc55441252705c982dc19adb0aa9aa7aecd59a61b0`,
`a4ca6dc559ccf92868fe85d6419a674cc069d5da186365f4d269870748fe331c`
and
`ef05e83d9f38ce6858417d3b088ad47f0b8c4e08802654c2ee0a49ebf3fcba05`.

Canonical Git, allocation and worker state remain unchanged. Four pre-existing
foreign anonymous slot4 volumes and all default networks are byte-identical.
Administrative Beautips, production, preview and the isolated control plane
remain healthy; rootful Docker remains inactive.

Task 5.2 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-5.2-rootless-empty-runtime`;
the SHA-256 of its `SHA256SUMS` is
`9ff3a320071c79043a5e5761db23428f51c5cf7210507c1f06609534f10886b8`.
The sealed pre-resource blocked attempt remains beside it with manifest
SHA-256
`3355d7533d84fb8caf3c2abec414dca6244d459fd74b429a02f78f88ee451920`.

The exact resume point is task 5.3 of `onboard-beautips-on-ax42`.

Task 5.3 is complete and change progress is `28/45`. Versioned fixture bundle
`beautips-acceptance-v1` is pinned to programme commit
`a30117789d1bddfde804dbaa00a71f2975178d60`; its manifest and SQL SHA-256
values are respectively
`3be6c7609a33272aec519058061dfbf98df66e773f1824792c9df609bae5e2fe`
and
`aa49558debab93c5f044663fcd01f76e8a5028cb635d4e9572c7eea2b71cb3db`.
It contains only invented `.invalid` identities, one SVG and one CSV; no
backup, legacy import, production row or manual asset participates.

The canonical APIs created one invented tenant and owner from the exact named
synthetic boundary. Idempotent SQL created exactly one customer, consent,
LOYALTY module, stamp-card program, account, transaction, program event,
service catalog and completed import job. The exact SVG and CSV exist only in
the session-owned asset/import volumes. Two full repeated loads produced
byte-identical sanitized database, file and Redis snapshots. Every declared
synthetic table reports one row; tenant WhatsApp credentials, outbox rows and
Redis keys report zero.

Acceptance exposed an eight-versus-four digit synthetic seal mismatch before
tenant persistence. Programme commit
`858e946c4e5c0ac704e2776179e2667dd73d6f66` corrects the generator and focused
boundary/lifecycle tests pass. The installed boundary and lifecycle hashes
are now respectively
`6f79b5f4cfae1924a479d541e4189c3db9cc8abcb0357a38603bdc7d7d4d21b1`
and
`c39b0a578a87161c79025de2c5b72930e7a2c834bdecbed074c0ecdbe8ad782b`.
Only this WorkSession's synthetic seal was shortened; the other named secrets
and exact enabled workspace config remained unchanged. Remediation evidence
is sealed with `SHA256SUMS` SHA-256
`ed5cb97c057a190ead56405609f9d829d980b12d4533f0b9c92afb0e4eaf3cb4`.

The running rootless containers ceased executing `docker exec` during
preflight. Fully session-labelled client containers therefore used only the
exact `internal=true` network, recorded immutable IDs and removed themselves.
The introduced PostgreSQL client image tag, private slot4 I/O directory and
all helper containers are absent after acceptance. Thirteen sealed blocked
attempt directories retain the output-transport, contract, schema and
evidence-query boundaries without secret values.

The three managed containers remain running, app/PostgreSQL/Redis are healthy,
and Redis is empty. Canonical Git, worktree change, allocation and routing
remain unchanged. Administrative Beautips, production, preview and the
isolated control plane remain healthy with their exact foreign identities.

Task 5.3 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-5.3-versioned-synthetic-fixtures`;
the SHA-256 of its `SHA256SUMS` is
`2d14e489e7315645a815b89297504c1dcbc883d8e3437c86aae5a7518b03005f`.

The exact resume point is task 5.4 of `onboard-beautips-on-ax42`.

Task 5.4 is complete and change progress is `29/45`. Exact container and
volume inspection proves the managed app, PostgreSQL and Redis mount only the
four complete-ownership WorkSession volumes. The separate administrative
slot1 workspace, its four volumes, three containers, network, listener and
manual boundary are absent from every managed mount and remain unchanged.
Neither a rootless/rootful Docker socket nor another host runtime path is
mounted.

The exact runtime network is still `internal=true` with only its three managed
endpoints. A finite fully labelled probe observed no default route, no
`host.docker.internal` resolution, no daemon socket paths and denied loopback
ports 18083, 2375 and 2376. Its immutable container ID was recorded and the
helper removed itself. It used the existing PostgreSQL 16 image, so image
inventory remained byte-identical.

A separate fully labelled database probe reports zero legacy import batches,
legacy mappings, non-synthetic import jobs, tenant WhatsApp channels,
credentials, outbox/messages, onboarding sessions and webhook events. The
pinned managed Compose SHA remains exact, its WhatsApp inputs are empty, the
birthday scheduler is false and no manual path, `.env` or daemon socket is
declared.

All four rootless slot container/network/volume/image inventories and AX42
boot, rootful Docker, Git, allocation, worktree, listeners and administrative
health are byte-identical before and after. Atenea production, preview,
isolated control plane, Git, listeners and complete rootful container
inventory are also byte-identical. Every checked health is `UP`.

Task 5.4 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-5.4-isolation-connectivity`;
the SHA-256 of its `SHA256SUMS` is
`9faeee64028161c7999ed6c1deaf9f10b913c7d7ebc71fc323ef009725a13a9c`.
Two sealed fail-closed attempts remain beside it with their own integrity
manifests; neither changed persistent or foreign resources.

The exact resume point is task 5.5 of `onboard-beautips-on-ax42`.

Task 5.5 is complete and change progress is `30/45`. The installed active
`session-preview/v1` coordinator created exactly one persisted synthetic
preview for control-plane WorkSession database ID 2 and runtime session
`6375c738-99da-4ef3-91f5-21e30d3b27d3`. Allocation fingerprint remains
`0e46cc38968509fbdd6585e3741f8c8e1eecb32f0161139400ec923780f49dbc`.

The READY preview has one listener on AX42 Tailscale IPv4 `100.81.98.93`
inside ingress range `19000–19031` and forwards only to allocation-owned
loopback port 21379. The private `/admin/login` route returns HTTP 200 both
from AX42 and the operator laptop over the tailnet. There is no wildcard or
localhost ingress listener.

The canonical manifest, persisted record and response all retain
`localhostCompatible=false`. The response has no tunnel metadata and does not
expose the runtime port. A localhost connection to the ingress port fails, and
the response page plus headers contain no `localhost` or `127.0.0.1`
reference. No public share, firewall change, production route or unrelated
preview was created.

Canonical Git and the intended untracked two-line WorkSession file remain
unchanged. Production, preview, isolated control plane, managed runtime and
administrative Beautips remain `UP`; Atenea boot, Git and complete container
inventory are byte-identical before and after.

Task 5.5 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-5.5-tailnet-preview`;
the SHA-256 of its `SHA256SUMS` is
`00949ceff54bc1fcf2efe5472e6973325332dbc9b0ce4f19c64d945840e39f66`.

The exact resume point is task 5.6 of `onboard-beautips-on-ax42`.

Task 5.6 is complete and change progress is `31/45`. The reviewed
`functional-smoke` and `customer-smoke` mediator plans remain execution
disabled by default and were invoked with their exact bounded argv and
600-second timeouts. The functional smoke passed its health, setup, admin,
salon, catalog, public customer, registration, QR accumulation/redemption and
business-query checks. Its one invented tenant was recorded by immutable
database id plus slug and exact-deleted after the customer smoke; all
tenant-scoped residual counts are zero and only `aurora-acceptance` remains.

Finite preview leases expired during authorised pauses. Each terminal
synthetic record was matched to the exact persisted ownership and deleted by
UUID before one replacement was activated. At most one preview was READY at
any time; runtime state, production routing and foreign preview resources did
not change.

The customer smoke found a real versioned fixture defect before acceptance:
raw value `SYNTHETIC_ACCEPTANCE` is not a valid
`TenantModuleActivationSource` and caused the public synthetic salon to return
HTTP 500. Programme commit
`a30117789d1bddfde804dbaa00a71f2975178d60` uses valid source `ADMIN`, updates
the SQL/manifest hashes, and changes no application or production data. The
exact runtime projection was replaced only after matching both previous
hashes. Two repeated idempotent reloads are byte-identical, the public page
returns HTTP 200 and the complete customer smoke then passes both invented
salons.

Playwright 1.60.0 ran from the operator laptop through the private tailnet
listener. Named synthetic login values travelled only through stdin and were
not retained. DOM assertions passed for the login, filtered single salon,
primary management action and exact customer `1`, active-program `1 / 1` and
active-access `1` KPIs. The credential identifier was sanitized before
capture. Inspected desktop `1440x900` and mobile `390x844` screenshots show
clear state/action hierarchy with no clipping, overlap or horizontal
overflow.

All helper containers were fully labelled and recorded by immutable ID. The
functional tenant, helper containers, newly pulled helper image, task-private
I/O directories and Playwright browser processes are absent. The pre-existing
operator Chrome is foreign and untouched. The three managed containers, four
volumes, internal network, canonical worktree and finite READY preview remain;
administrative Beautips and Atenea production, preview and isolated control
all remain `UP`.

Task 5.6 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-5.6-functional-playwright`;
the SHA-256 of its `SHA256SUMS` is
`4e6c5ded2a688a8d8d12d5846e354500b0c8dfd2fce8f459620fa31b8e95aefa`.

The exact resume point is task 5.7 of `onboard-beautips-on-ax42`.

Task 5.7 is complete and change progress is `32/45`. The sanitized desktop
PNG, mobile PNG and Playwright DOM JSON report are registered through the
authenticated `worksession-attachment/v1` boundary under exact WorkSession
UUID `6375c738-99da-4ef3-91f5-21e30d3b27d3`. Their deterministic immutable
attachment UUIDs are respectively
`8fdb5346-57c7-5aff-baa9-5c1b676ad4ad`,
`c420ebd1-b76b-5dcd-9a3e-58accf4be087` and
`a501aac8-2c2d-54e3-8b6e-f455eb5d785f`.

All three records declare synthetic identity and `EVIDENCE` retention with
opaque storage identities. Initial registrations returned HTTP 201. Repeating
the same identities, metadata and bytes returned HTTP 200 with the original
metadata and no duplicate retained files.

The finite preview was already `EXPIRED`. Its complete persisted ownership,
revision 4, exact UUID and absent listener were checked before exact terminal
synthetic deletion. Its record and the entire preview ingress range are now
absent while the three-container managed runtime remains running.

Two complete retrieval passes after teardown returned HTTP 200 for all three
metadata and all three content identities. Every byte count and SHA-256
matches the sanitized source and both passes are identical. No temporary
retrieval copy, token value, credential, cookie, authorization header,
environment dump or production data remains in evidence.

The first continuation observed the historical internal name `sessions/`
after registration and stopped before preview mutation. Canonical continuation
used `work-sessions/`, retained the failed non-mutating check and reused the
already idempotently accepted records.

Administrative Beautips, the managed runtime and Atenea production, preview
and isolated control remain `UP`. Canonical WorkSession Git remains unchanged.

Task 5.7 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-5.7-artifact-retention`;
the SHA-256 of its `SHA256SUMS` is
`aa7e4667e09ca367c3826563c7d8e8a254a1862ff6ea72b4096dc335fd1754fb`.

The exact resume point is task 6.1 of `onboard-beautips-on-ax42`.

Task 6.1 is complete and change progress is `33/45`. The exact WorkSession
branch `atenea/session-6375c738-99da-4ef3-91f5-21e30d3b27d3` contains one new
commit, `03f77b0389f5303153c47bc3f890b1e0e9e92eb8`, with tree
`ea2050c15dc7949515a432cce70f1b6f4362d7e0`. It adds only the two-line
`docs/ax42-onboarding-acceptance.md`; the AX42 and isolated-control worktrees
are clean.

The remote branch was absent at entry. Complete history moved through an
exact SHA-256-matched bundle, the isolated checkout advanced only by
`merge --ff-only`, and a normal push created the branch without force.
Atenea's authenticated `POST /api/sessions/2/publish` then created
`jlnieto/beautips#1`. GitHub reports one `OPEN` draft based on exact `main`
`e9e0b3c319c518363d4135f5378ebbddced96dfb`, headed by the exact WorkSession
commit, mergeable, with one commit, one file and two insertions. Atenea
persisted the same URL, branch and final commit.

The first endpoint attempt failed closed with HTTP 409 before GitHub mutation
because its disposable control lacked Git authentication for the remote-head
read. The accepted retry returned HTTP 200 through a temporary isolated
control and finite-lived credential pipes. The helper, FIFOs, control
container, temporary image, bundle and transfer ref were all removed by exact
identity. No credential value, authorization header, cookie, environment dump
or token is retained.

The WorkSession remains `OPEN` with zero running AgentRuns. Its exact managed
runtime, Git state and registered evidence remain present. Atenea production
and preview and the foreign administrative Beautips runtime remain `UP`.

Task 6.1 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-6.1-draft-delivery`;
the SHA-256 of its `SHA256SUMS` is
`9538eeed1f53788fc35b7da7bcad10fa2db948f0bc363a4742bd56b36ad7f82f`.

The exact resume point is task 6.2 of `onboard-beautips-on-ax42`.

Task 6.2 is complete and change progress is `34/45`. GitHub CI `Test and
build` passed for exact draft `jlnieto/beautips#1`. The one-file diff, base,
head and commit were reviewed before marking it ready and performing a normal
merge without branch deletion. Merge commit
`f836940d71ed761a4d12e560c3790eeba9778f85` has exactly pre-merge `main`
`e9e0b3c319c518363d4135f5378ebbddced96dfb` and immutable WorkSession head
`03f77b0389f5303153c47bc3f890b1e0e9e92eb8` as parents. Remote `main` points
to the merge and the WorkSession branch remains at its original head.

The real Atenea pull-request synchronization endpoint ran twice with finite
timeouts. Both calls returned HTTP 200 and retained byte-identical material
delivery fields: WorkSession `OPEN`, pull-request status `MERGED`, exact URL,
branch, final commit and original publication timestamp. Non-terminal
AgentRuns and push-notification rows remain zero.

Exact isolated-database project and WorkSession IDs `6102` acted as a
cross-session sentinel. Its selected-row MD5
`58e081bb652ef8549e821b086d94eb3b` remained unchanged across both real sync
calls. Cleanup required that MD5 plus the exact IDs, project, workspace
identity and final SHA, then removed only those two rows. Both fixture IDs are
absent.

Focused `WorkSessionGitHubServiceTest` passed `9/9`. The sync credential was
consumed only through a finite-lived FIFO and cached only by the ephemeral
process for the repeat call. The exact FIFO, temporary control and image are
absent. No force update, duplicate delivery response, cross-session mutation,
credential value, authorization header, cookie or environment dump is
retained.

Task 6.2 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-6.2-merge-sync`;
the SHA-256 of its `SHA256SUMS` is
`5d988f75682a2e8c830b1d7b1f31b5f5805bb1e9170d864382a6e34bf27e644d`.

The exact resume point is task 6.3 of `onboard-beautips-on-ax42`.

Task 6.3 is complete and change progress is `35/45`. Atenea's authenticated
close endpoint reconciled database WorkSession `2` and external WorkSession
`6375c738-99da-4ef3-91f5-21e30d3b27d3` to `CLOSED/CLOSED` at
`2026-07-29T22:38:08.356739Z`. Pull-request status remains `MERGED`; exact
workspace identity, URL, publication timestamp and final commit are unchanged.
There is no close block and retryable state is false.

The canonical close revalidated the GitHub repository, pull-request number,
base, head and head SHA. It fetched origin, checked out `main`, fast-forwarded
only to exact merge `f836940d71ed761a4d12e560c3790eeba9778f85`, then deleted
only the merged session branch from the disposable control clone and GitHub.
Both are absent. AX42 retains its clean local WorkSession branch at
`03f77b0389f5303153c47bc3f890b1e0e9e92eb8`; merged history and the pull
request retain the same commit.

The first close attempt stopped at retryable `CLOSING/fetch_failed` before
branch mutation because the ephemeral helper executable was placed on a
`noexec` tmpfs. Moving only that executable to temporary container `/tmp`
preserved the in-memory token and allowed the same canonical close to return
HTTP 200. The repository helper, temporary control and image are absent, and
no credential value or environment dump is retained.

Focused close reconciliation tests passed `38/38`. The three-container managed
runtime, network, four volumes and three log files remain present. The exact
six registered attachment files remain `158452` bytes. Tasks 5.6, 5.7, 6.1
and 6.2 checksum hashes are unchanged. Atenea production, preview and the
foreign administrative Beautips runtime remain `UP`.

Task 6.3 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-6.3-canonical-close`;
the SHA-256 of its `SHA256SUMS` is
`0179ea4a72eb629225289db4e93211caf4213256cd3e9b450f1367532f32fda2`.

The exact resume point is task 6.4 of `onboard-beautips-on-ax42`.

Task 6.4 is complete and change progress is `36/45`. Only
`atenea-agent-run-worker-v1.service` restarted. Its PID changed from `726675`
to `916853` and the service returned `active/running` on the same tailnet-only
listener at `2026-07-29T22:44:53Z`. No host or project runtime restarted.

The durable execution file remained byte-identical at SHA-256
`f65e488816560e022ac8e7d4a68adf55483cf772e387e6f20b990bf31c53734a`.
Its sanitized protocol, worker, global status histogram and exact Beautips
ownership projection also remained byte-identical at
`a4628fb6f07b76fcebb129e1cc3ff6f46366ce1a09485b6ce576718f317828ac`.

The restarted mediator returns the same two `SUCCEEDED`, revision-5 Beautips
executions under exact WorkSession ownership. Immutable dispatch, execution
and turn IDs and shared thread `019faf5f-0a96-7592-a936-583cb044dae8` are
unchanged. Its journal has zero new execution POSTs and the WorkSession has
zero runner/Codex processes. Atenea still has two terminal AgentRuns, four
turns and zero non-terminal runs, proving no prompt or dispatch reran.

The first readiness request used noncanonical `/health` and returned 404
without mutation. Authenticated `/v1/health` then reported worker `ax42-01`
healthy with zero normal/heavy capacity in use and zero queued work. Git, the
three-container runtime, network, four volumes and six attachments remain
unchanged. Production, preview, isolated control and administrative Beautips
remain `UP`.

Task 6.4 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-6.4-worker-restart-reconciliation`;
the SHA-256 of its `SHA256SUMS` is
`18574badb39b2faf4045aa1a2cc36f0f43d01b5a2bd56c5ed29ece85a4fe9a19`.

The exact resume point is task 6.5 of `onboard-beautips-on-ax42`.

Task 6.5 is complete and change progress is `37/45`; phase 6 delivery, close
and continuity is complete. The installed Beautips contract verifies with
selection and execution enabled and one exact persisted workspace. Its
configuration SHA-256 is
`f3fb28e3f4b81ae6b584e8f21bfa3a1742e77772d1a2701fdf56b14b1e12592a`.
The allocation-owned managed web health returns `UP` with the same three
containers.

The worker loads only the exact Beautips config/runner and the generic Atenea
config/runner. Atenea remains `false/false` with no workspace; the root-owned
static allowlist has only key `beautips`. The isolated control has only its
exact Beautips project gate enabled and the generic project gate disabled.
Unknown projects have no installed config or runner authority.

Focused Atenea routing tests pass `11/11`, covering exact selection, disabled
selection, partial/foreign identity, missing capability and unknown-project
denials. The worker's exact Beautips route test passes `1/1` and accepts only
the exact registered workspace. No real session, run, dispatch or prompt is
created: durable worker SHA-256 is unchanged, Atenea remains at two terminal
AgentRuns, four turns and zero non-terminal runs.

A first HTTP probe targeted allocated Redis port `23826` and received an empty
reply. The persisted allocation identified correct managed web port `21379`,
where `/actuator/health` returns `UP`; the failed probe mutated nothing.
Production, preview, isolated control and administrative Beautips remain
healthy.

Task 6.5 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-6.5-availability-denials`;
the SHA-256 of its `SHA256SUMS` is
`912123af504fb6f672cb191bdcdfdc02c67279d9ee1dfbb9423499f4543e6a26`.

The exact resume point is task 7.1 of `onboard-beautips-on-ax42`.

Task 7.1 is complete and change progress is `38/45`. New Beautips selection
and execution are disabled in the AX42 installed config while its exact
persisted workspace remains registered for reconciliation. The disposable
Atenea control was replaced from its own committed image with only the
Beautips project gate overridden to false; its database, port and health
remain unchanged.

Accepted RootlessKit records `1`, `2` and `3` first matched their complete
parent/child tuples and were then deleted by immutable ID. The exact mediated
`runtime-cleanup` removed only containers `5e59b7d8e112`, `adaa784a6bc2`,
`4096ca2c7a3c`, network `f5b9c323b395`, four session volumes and local image
`aaefc03e7b80`. The exact runtime root contained only
`fixtures`, `secrets` and `tomcat`; realpath, non-symlink and top-level-name
checks passed before removing that file projection.

Final exact-session counts are zero containers, networks, volumes, images,
RootlessKit records, allocated listeners and runtime root. Preview records
remain zero. Mirror, allocation, clean worktree at
`03f77b0389f5303153c47bc3f890b1e0e9e92eb8`, six attachments and programme
evidence remain retained. WorkSession state is `CLOSED/MERGED` with zero
non-terminal AgentRuns.

Administrative Beautips retains its original three containers and remains
`UP`. Atenea production, preview and disabled isolated control remain `UP`;
no production, foreign WorkSession, unrelated slot or routing resource
changed.

Task 7.1 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-7.1-disable-exact-cleanup`;
the SHA-256 of its `SHA256SUMS` is
`27225617d206dc430f9ccc7eb349f15333afb2ae3475980f4b39aab96ce8199d`.

The exact resume point is task 7.2 of `onboard-beautips-on-ax42`.

Task 7.2 is complete and change progress is `39/45`. Repeating the exact
worker disable and allocation-derived mediated cleanup from the 7.1 empty
boundary returned exit 0 and deleted nothing: managed container, network,
volume and image counts remained zero.

Four stopped slot 4 internal-network fixtures represented unlabelled, partial,
foreign full ownership and ambiguous exact labels with a non-allocation name.
Their normalized immutable ID, name, labels and internal-state projection had
SHA-256
`42c7ecbb4bd3253556242ca3b733cbc2fe8cfbdc060702ef721183b0406b9e34`
both before and after cleanup. Every rejected resource therefore remained
intact. Only after equality passed were the four fixtures deleted by their
recorded immutable IDs; fixture count is now zero.

Selection/execution remains `false/false` with one persisted workspace.
Worktree, mirror, allocation, six attachments and evidence remain retained.
Administrative Beautips, production, preview and isolated control remain
`UP`.

Task 7.2 passing evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-7.2-idempotent-rejection`;
the SHA-256 of its `SHA256SUMS` is
`2e6f5a561ff71c75db9c4b8cd3f4a53dd4303276cb461e8932d84b1024aea467`.

The exact resume point is task 7.3 of `onboard-beautips-on-ax42`.

Task 7.3 is complete and change progress is `40/45`. Versioned admission
released exact `slot4`; normal usage is `0/4`, heavy usage `0/2` and this
session has no heavy permit. The original allocation remains retained and its
archived evidence copy is byte-exact at SHA-256
`0e46cc38968509fbdd6585e3741f8c8e1eecb32f0161139400ec923780f49dbc`.
After release/archive verification, the exact disabled workspace registration
was removed; selection/execution remains false and workspace count is zero.
Mirror, worktree, Git, merged delivery, attachments and evidence remain.

Task 7.3 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-7.3-release-allocation-archive`;
its `SHA256SUMS` hash is
`6d351ceea4a05eaad092fec129e1f31694fe5deb860ff1022be186171cffb3db`.

The exact resume point is task 7.4 of `onboard-beautips-on-ax42`.

Task 7.4 is complete and change progress is `41/45`. Read-only comparison
retained the three exact administrative slot 1 container IDs and proved all
nine accepted Atenea production/preview rootful Docker identities byte-exact.
Slots 2–4 contain no containers; all four rootless Docker daemons and
restricted proxies are active. AX42 RAID remains `[UU]`, storage is healthy,
SSH, Tailscale and platform services are active, and administrative Beautips,
production, preview and isolated control are `UP`. No lifecycle or foreign
resource mutation was performed.

Task 7.4 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-7.4-nonimpact-fingerprints`;
the SHA-256 of its `SHA256SUMS` is
`4c1f3d71c2030e5f5d58c911fdfd6e4d2ca9427aa10073c81ab78165fb003e22`.

The exact resume point is task 7.5 of `onboard-beautips-on-ax42`.

Task 7.5 is complete and change progress is `42/45`. Two independent
regression cycles each pass the Beautips worker aggregate, manifest ownership
and exact-cleanup `3/3`, Beautips focused `29/29`, Beautips full `30/30`,
Atenea focused `37/37` and Atenea full `411/411`. Each full Atenea cycle
applied all 49 migrations from a new empty PostgreSQL 16 schema on a separate
internal network and isolated workspace.

The initial harness warmup proved `-DskipTests` did not prefetch the Surefire
JUnit provider; the internal network rejected its unplanned lookup before
product tests. Exact cleanup returned its resources to zero. The corrected
bounded warmup ran one unit test before both accepted internal-network cycles.
Final test containers, networks and volumes are zero. Generated Python cache
files were removed by exact path. Programme, Beautips and Atenea Git are clean;
administrative Beautips, production, preview and isolated control remain
`UP`, and RAID remains `[UU]`.

Task 7.5 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-7.5-double-regressions`;
the SHA-256 of its `SHA256SUMS` is
`320dc72e85cc38a889423e18ed6186dc96afbf37f9bafa3f0eeed1294e4c9b7a`.

The exact resume point is task 8.1 of `onboard-beautips-on-ax42`.

Task 8.1 is complete and change progress is `43/45`. All 60 pre-existing
sealed evidence packages validate file by file, covering 579 files and
1,417,430 bytes. The rollup indexes the two accepted Playwright screenshots
and 36 command metadata files. Filename and value-shape audits found zero
retained auth files, environment dumps, cookies, credentials, tokens,
authorization values, GitHub-token shapes or JWT shapes.

Task 8.1 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-8.1-evidence-rollup`;
the SHA-256 of its `SHA256SUMS` is
`39b6a069bd459c2a7820edd4d7c47bce5385d672abd015ec11e28eb19526951f`.

The exact resume point is task 8.2 of `onboard-beautips-on-ax42`.

Task 8.2 is complete and change progress is `44/45`. The accepted close
window lasted 909 seconds. Normalized samples at minute `0`, `5`, `10` and
`15` are byte-identical at SHA-256
`26dd67580ef414aa28c66c39074d0572a79b6a467bf0565e1b55cb9ddddf1685`.
Selection/execution and workspace count remain false/false/zero; admission is
released; exact owned resources, listeners, non-terminal AgentRuns, active
leases, active remote routing, preview records and browser processes remain
zero.

Allocation, mirror, clean worktree, six attachments and evidence remain.
Administrative Beautips retains its exact three identities; RAID is `[UU]`;
production, preview, administrative Beautips and isolated control remain
`UP`. The isolated acceptance control retains 17 active refresh-token rows
for its one synthetic operator, all created before task 7.1 and stable across
the four samples. They are not WorkSession or production ownership; no hash
or value was read, retained or modified.

Three pre-window read-only diagnostics rejected a nonexistent mirror ref,
untrusted Git ownership and attachment traversal before an accepted sample.
The corrected harness restarted at minute zero and completed without drift or
mutation.

Task 8.2 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-8.2-disabled-clean-observation`;
the SHA-256 of its `SHA256SUMS` is
`76999878fcce8fc53ea5310f02e391d304daf74005a3fb70384410da88ee30c1`.

The exact resume point is task 8.3 of `onboard-beautips-on-ax42`.

Task 8.3 completes `onboard-beautips-on-ax42` at `45/45`. Pre-archive strict
validation passes, and the programme stops after canonical archive, final
all-spec validation and synchronized push. No subsequent project onboarding,
routing activation, runtime, deployment or production mutation is started.

Canonical OpenSpec archive creates
`openspec/changes/archive/2026-07-30-onboard-beautips-on-ax42` and promotes
the seven accepted requirements into authoritative capability
`openspec/specs/beautips-project-onboarding/spec.md`. Post-archive strict
validation passes all 11 authoritative specs. Final non-impact checks retain
disabled zero-workspace selection/execution, released slot 4 admission, clean
WorkSession Git, RAID `[UU]`, exact administrative Beautips identities and
`UP` health for administrative Beautips, production, preview and isolated
control.

Task 8.3 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/onboard-beautips-on-ax42/runs/task-8.3-strict-archive`;
the SHA-256 of its `SHA256SUMS` is
`6e5912431f7f5201119a1e7b0f3e00684a2c8f9ba88907586b728d4ac8c890a4`.

The programme is paused after Beautips onboarding. No next project has been
selected or started.

## Independent external backup progress

The `establish-independent-worker-backup` acceptance is complete through task
6.3. Backblaze B2 is provisioned in the independent operator account as a
private bucket with provider-side encryption, Object Lock disabled and
bucket-scoped read/write credentials restricted to the owned AX42 restic
prefix. Credential values and the restic password were installed out of band
as root-owned mode-0600 inputs and never entered Git, chat, command arguments,
logs or evidence.

The exact source policy accepts 3,234 files totalling 10,914,251 bytes. Its
normalized aggregate manifest SHA-256 is
`6d22bd9d8dc81594c3a6148471c07190bf1674355ce4f73adf42020de8b22f16`.
Snapshot `b0738177a5983e4f597f0be1ee8344a4b91876b6a641995d99b2e622ea9bbb28`
passed repository check. A second accepted backup was restored into a new
empty isolated projection: all 3,234 files, byte count and manifest matched
exactly, after which only that exact projection was removed.

A mediated scheduled boundary produced checked snapshot
`9e9c8c2768089e0e2cbf663cc61905bbd5d434f16e7c2fdd86ad51668f1fa25b`.
Retention removed only the superseded intermediate snapshot and leaves the
first and scheduled accepted recovery points. The daily backup and weekly
integrity timers are enabled and persisted. A concurrent integrity attempt
failed closed after its 30-second lock timeout, and the isolated retry passed.

Disable and full rollback were each repeated twice. They removed only the
installed programme components and did not alter credentials, local persisted
state, routing fingerprints, evidence or either retained remote snapshot. The
accepted version was then reinstalled and only its two backup timers were
re-enabled. A deliberately missing-input invocation returned fixed exit `65`
without changing state or routing.

Final non-impact checks retain boot ID
`5cc2a4e3-020d-4d19-8a55-6ecae77f22ce`, all three RAID1 arrays at `[UU]`,
active SSH, Tailscale and worker services, four rootless daemons, slot
container counts `3/0/0/0`, admission usage `0/4` normal plus `0/2` heavy,
13 running and zero unhealthy Atenea containers, and healthy production,
preview and administrative Beautips. Atenea and Beautips selection/execution
remain disabled with zero registered workspaces.

Sanitized acceptance evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/establish-independent-worker-backup/runs/task-6.1-evidence-rollup`;
the SHA-256 of its `SHA256SUMS` is
`90c2acc6882d8f498bd70742d9dcb3b7699edbe3628e6d1fba829938ecc18b4c`.

The external-backup prerequisite is lifted and all 32 tasks are complete.
`establish-independent-worker-backup` is archived at
`openspec/changes/archive/2026-07-30-establish-independent-worker-backup`;
post-archive strict validation passes all 11 authoritative specs. No active
OpenSpec change remained at that acceptance point. Beautips routing and
authoritative retained state were then disabled pending the separate
activation recorded below.

## Beautips production remote routing activation

`activate-beautips-remote-routing` promotes only Beautips from accepted
onboarding to normal remote operation. All `32/32` tasks are complete and the
change is archived as
`openspec/changes/archive/2026-07-30-activate-beautips-remote-routing`.
Atenea source
`4efd3f9b96924e9d2668a19ba1110eca18b49791` is deployed and synchronized.
The production backend has the global and exact Beautips gates enabled, the
generic project gate disabled, the private AX42 endpoint configured and
previews allowlisted only for `Beautips`.

WorkSession `4` is open and pinned to worker `ax42-01`, remote UUID
`2ac2a5fd-2981-49cf-8fac-8132e46a2d64`, workspace identity
`remote:ax42-01:work-session:2ac2a5fd-2981-49cf-8fac-8132e46a2d64` and branch
`atenea/session-2ac2a5fd-2981-49cf-8fac-8132e46a2d64`. AX42 retains the clean
accepted Beautips commit `e9e0b3c319c518363d4135f5378ebbddced96dfb` in
slot 4 with one normal admission, one allocation, three healthy runtime
containers, one network, four volumes and the exact three loopback listeners.

Runs `63`, `64` and `65` succeeded on Codex thread
`019fb299-8752-7f31-bfe5-6bc91f7d0551`. The final dispatch
`fdc7c547-fd07-42e2-abfe-863cfb30eb58` has one terminal worker execution and
two byte-identical terminal reads. The four earlier fail-closed attempts remain
auditable and were not rewritten.

The invented acceptance dataset contains one tenant, operator, customer,
consent, module, loyalty chain, catalogue and import; WhatsApp credentials,
outbox and Redis remain empty. Preview
`eeaa6195-322f-43ff-b84b-06fe9d55c430` is `READY` only on
`100.81.98.93:19000`; loopback ingress is rejected. Playwright verified the
real Beautips login at `1440x900` and `390x844`, with all critical controls
visible and zero horizontal overflow.

Disable was repeated without moving the open WorkSession or deleting retained
ownership. Both disabled configurations had SHA-256
`20957d326aadf1a00ca516972ad4010669b5335aa0c1f4378ed4df2d3be7aad7`;
workspace, allocation, admission, Git, runtime resources, preview, backup and
the foreign slot 1 stack remained unchanged. Two isolated reconciliation and
rejection cycles passed, including `16/16` worker tests per cycle. Exact
reconciliation restored the original enabled configuration SHA-256
`87ba464a62af351912407f7fe9fd225d7b9826b1d5c5c6fbe791326f1b5fd0ad`.

Two final Atenea regressions pass `413/413`. RAID remains `[UU]` on all three
arrays, rootful Docker remains inactive, external-backup timers are
enabled/active, production and preview are `UP`, all 13 Atenea containers are
running with zero unhealthy, and no unrelated remote WorkSession exists.

Sanitized acceptance evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/activate-beautips-remote-routing/runs/task-7-closure`;
the SHA-256 of its `SHA256SUMS` is
`bd9a02bd00281e3ee400ae24365f2d12a9f1c32b6b3f58f94bd5c02b87906006`.

The programme resume point is the next separately approved real project
activation. No unrelated project has been enabled or started.

## Atenea production remote routing activation

`activate-atenea-remote-routing` is complete through task 3.3 at `12/19`.
The global and exact Atenea gates are enabled for one open WorkSession pinned
to worker `ax42-01`, remote UUID
`c750641d-3226-44c3-81dc-d9149aac0de1`, workspace identity
`remote:ax42-01:work-session:c750641d-3226-44c3-81dc-d9149aac0de1` and branch
`atenea/session-c750641d-3226-44c3-81dc-d9149aac0de1`.

The archived development session's released slot-2 allocation marker was
retired only after its exact SHA-256 matched sealed task-7.3 evidence and its
containers, networks, images and listeners were proven absent. The record was
preserved byte-for-byte under its retired filename; no runtime or foreign
resource was removed.

The first two operator turns remain as auditable pre-dispatch failures: neither
received a remote execution identity. They exposed that activation commands
restarted the worker from inside its own workspace-ensure request. Programme
commit `8631dcb5cb26dfd7b76698c5d5158caac505ad4a` replaces that sequence with
one atomic project activation write. The worker reads the configuration on
every request, so no self-restart is required. An idempotent activation repeat
kept the exact worker PID and zero restart count.

Run `74` then completed automatically with dispatch
`bf03e0d2-289c-44d9-911c-934614968240`, execution
`fd8042b4-4422-46dd-9a1f-43c11882efd0` and one persisted Codex response.
The bounded read-only answer reported the session branch, accepted commit
`d5ea39e7b575b63c6fff3a66a0400c5af5e9ff2b` and a clean worktree without
modifying a file. The control plane contains one row for the successful
dispatch and the worker retains one terminal revision-5 execution.

Sanitized task-3.3 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/activate-atenea-remote-routing/runs/task-3.3-first-terminal-turn`;
the SHA-256 of its `SHA256SUMS` is
`1c617cf8c5f538725448268cc272a97b7d0ed630f62223d74ce90f3b43e2f2d1`.

Task 3.4 is complete and change progress is `13/19`. Run `75` completed with
dispatch `e94aa212-da6b-4a26-a193-2c460eb8b4fd` and execution
`a1cafad8-0909-44b0-bd2a-7781f09118ca`. Runs `74` and `75` both use exact
persisted Codex thread `019fb47f-1934-75f1-889a-506ec94c71d8`, have distinct
turn identities, reached terminal revision 5 and retained one response each.
Each dispatch occurs once in the control plane; there is no duplicate
delivery.

Sanitized task-3.4 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/activate-atenea-remote-routing/runs/task-3.4-thread-continuation`;
the SHA-256 of its `SHA256SUMS` is
`b04248a347f18adae29b74f7411909f2e1707fc29673d2cc1f7003b8c2424168`.

Task 3.5 is complete and change progress is `14/19`. Production and preview
are `UP`; all 13 Atenea containers are running with zero unhealthy. All three
RAID arrays remain `[UU]` and the worker service is active with zero
non-terminal AgentRuns and zero active leases.

Slot container counts remain `3/0/0/3`. The Atenea session has zero owned
containers, networks, allocated listeners and residual execution processes,
while its worktree remains clean. Administrative Beautips in slot 1 retains
its three containers. Routed Beautips in slot 4 retains its three containers,
one session network and all three allocated listeners. The only remote
WorkSessions are the accepted Beautips and Atenea identities; the unrelated
remote-session count is zero.

Sanitized task-3.5 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/activate-atenea-remote-routing/runs/task-3.5-nonimpact`;
the SHA-256 of its `SHA256SUMS` is
`c1a031d8450a1d6b88eba91ce0bdca60e4bc4ec2bee2b912f2a689fea6df8698`.

Task 4.1 is complete and change progress is `15/19`. Only Atenea selection
and execution were disabled. WorkSession `6` remains `OPEN` with exact worker,
remote UUID, workspace identity and persisted Codex thread unchanged.
Workspace and allocation records are byte-identical. The Beautips
configuration remains byte-identical at SHA-256
`87ba464a62af351912407f7fe9fd225d7b9826b1d5c5c6fbe791326f1b5fd0ad`;
there are zero non-terminal Atenea AgentRuns.

Sanitized task-4.1 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/activate-atenea-remote-routing/runs/task-4.1-disable-retain`;
the SHA-256 of its `SHA256SUMS` is
`7d425a082e4c1bd77788abbfc849dbb950bc46889334c06add85360a0d5cb5d1`.

Task 4.2 is complete and change progress is `16/19`. Exact retained Atenea
selection/execution was re-enabled without replacing its workspace. Run `76`
completed with dispatch `5370587e-b583-4fb5-82d1-667eb436ed26`, execution
`14eccdcd-22b8-4270-98da-98c3bb859b26` and the same persisted Codex thread
`019fb47f-1934-75f1-889a-506ec94c71d8`. The dispatch occurs once, one response
is persisted and the read-only turn reports accepted commit
`d5ea39e7b575b63c6fff3a66a0400c5af5e9ff2b` without changing a file.

Sanitized task-4.2 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/activate-atenea-remote-routing/runs/task-4.2-reenable-final-turn`;
the SHA-256 of its `SHA256SUMS` is
`c5563f8d4f4e665a845ab994a95ed62ed29f3b138952de4f7827728d489265e4`.

Task 4.3 is complete and change progress is `17/19`. The rollup verifies six
task `SHA256SUMS` manifests, nine root evidence sidecars and 52 pre-rollup
files totalling 76,528 bytes. Filename and value-shape audits found zero
retained auth files, environment dumps, cookies, credentials, tokens,
authorization values, private keys or JWT-shaped values.

Sanitized task-4.3 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/activate-atenea-remote-routing/runs/task-4.3-evidence-rollup`;
the SHA-256 of its `SHA256SUMS` is
`a931b973b04dca14fce3ab1cf59e9941f76e339ce98149ca70b2ef091519b353`.

Task 4.4 is complete and change progress is `18/19`. Pre-archive strict
validation passed. Canonical archive moved the change to
`openspec/changes/archive/2026-07-30-activate-atenea-remote-routing` and
synchronized the accepted Atenea activation behavior into authoritative
`atenea-project-onboarding` and `remote-worker-control`. Post-archive strict
validation passes all 11 authoritative specifications; there are no active
changes.

Sanitized task-4.4 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/activate-atenea-remote-routing/runs/task-4.4-openspec-archive`;
the SHA-256 of its `SHA256SUMS` is
`70ba3ebb5f3be3c8ad689302c81a7190f128777fe7143619c5da1f28ebdd9e22`.

Task 4.5 completes `activate-atenea-remote-routing` at `19/19`. Atenea source
is clean and synchronized at
`1bef4b01a0ddd71f71279721bad908867cc21c3c`; the programme archive parent is
clean and synchronized at
`a3b8add8afeaf6a01691f9abe79789d2a7030dfd`. All 11 authoritative OpenSpec
specifications pass strict validation and no active change remains.

Atenea and Beautips selection/execution are enabled only for their exact
retained workspace identities. Production and preview are `UP`; the worker is
active; all three RAID arrays are `[UU]`; slot container counts remain
`3/0/0/3`; and non-terminal AgentRuns plus unexpected remote WorkSessions are
zero.

Sanitized task-4.5 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/activate-atenea-remote-routing/runs/task-4.5-final-state`;
the SHA-256 of its `SHA256SUMS` is
`5ceeff47febe959e5b9b3dd3dc64a9eb87947e4d1fe7e685aae2c37ea5bd3473`.

The Atenea activation change is complete and archived. Atenea and Beautips are
ready for normal remote work from the laptop and mobile application.

## Codex session operations

The active OpenSpec change is `add-codex-session-operations`. It defines the
next programme phase for professional day-to-day Codex operation through
Atenea: effective model and reasoning-effort selection, sanitized intermediate
progress, self-service run recovery, reusable Android notifications and a
separately authorized managed Codex version lifecycle.

The change contains 57 ordered tasks across safe execution foundations,
contracts, the Atenea control plane, AX42, web and Android experience,
notifications, version administration and final acceptance. Progress is
`0/57`; the exact resume point is task 0.1. Implementation must proceed task by
task and retain the disable-first rollback boundary. A real AX42 Codex version
activation remains subject to separate explicit authorization at task 6.7.

The accepted control boundary does not expose arbitrary Codex flags, commands,
providers, endpoints, paths, environment values or host services. Model and
effort changes apply only to future AgentRuns; each run retains its immutable
effective profile and Codex version. Intermediate progress is bounded and
sanitized and must never retain hidden reasoning, raw command output, prompts,
answers, credentials or tokens.

The first broad Atenea implementation attempt is retained only as a stale
unvalidated draft. It started from
`d5ea39e7b575b63c6fff3a66a0400c5af5e9ff2b`, four commits behind canonical
`1bef4b01a0ddd71f71279721bad908867cc21c3c`, overlaps newer canonical web and
Android prompt-delivery fixes, contains no new tests and has a compile-time
duplicate parameter in `RemoteAgentRunCoordinator`. AgentRun `78` records
successful Codex process completion, not accepted work. No draft file may be
committed, rebased, ported, deployed or discarded before task 0.1 fingerprints
it and the foundation gates permit reviewed recovery.

Task 0.1 is complete and change progress is `1/57`; the exact resume point is
task 0.2. The retained draft remains byte-identical before and after capture
at HEAD `d5ea39e7b575b63c6fff3a66a0400c5af5e9ff2b`, with an unchanged clean index,
28 tracked modified files and 16 untracked files. Its tracked binary diff
fingerprint is
`fe004b66dc9d76da024c6c514ccd7992b6846b2556fab8694bbfd3feb6257fa8`;
its untracked manifest fingerprint is
`b7b2d520213300600bdbb3bd005ede283fd505f24be31d4e018e90a144fc4fa8`.

Canonical Atenea remains clean and synchronized at
`1bef4b01a0ddd71f71279721bad908867cc21c3c`, four commits ahead. The exact
overlap is limited to `WorkSessionConversationScreen.kt`, `web/src/App.tsx`
and `web/src/api.ts`. Allocation remains `slot2/heavy1`; the slot and rootless
Docker service are active while owned containers, networks, listeners,
session processes, Codex executions, project runners and Playwright/Chromium
processes are all zero.

Sanitized task-0.1 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-0.1-stale-draft-fingerprint`;
the SHA-256 of its `SHA256SUMS` is
`7cdfa7a4b8861bd4a27cd59e1742bd79db156ca508eaa6b84044e3275da38ee9`.

Task 0.2 is complete and change progress is `2/57`; the exact resume point is
task 0.3. Its initial static-pin experiment was deliberately discarded before
commit after proving that a repository cannot embed its own current branch
HEAD as a stable constant: the commit containing that constant immediately
creates a different HEAD.

Atenea now observes its fixed remote branch at runtime before the first
write, requires the canonical checkout to be on that branch, clean and exactly
equal to the remote commit, then persists the ref, commit, observation
fingerprint and time. The immutable value is copied into AgentRun. AX42
independently resolves the root-owned mirror ref before workspace admission
and dispatch; configuration, workload, mirror, registered workspace and clean
WorkSession HEAD must all match. The independently observed worker commit is
returned and persisted before dispatch.

The Atenea implementation is published cleanly at
`5dfa8d4174b67019216a9c97746d502431e1959c`. Two complete backend passes each
ran 420 tests with zero failures, errors or skips. Two worker passes each ran
8 project-runner, 18 AgentRun-worker and 4 Beautips-compatibility tests plus
shell syntax validation. External timeouts were 600 seconds for backend
passes and 120 seconds for worker passes. Negative acceptance covers stale
ancestor, divergence, tracked/untracked dirt, missing or ambiguous ref, moved
control-plane ref, moved worker mirror and conflicting workload commit.

The stale WorkSession remains byte-preserved at
`d5ea39e7b575b63c6fff3a66a0400c5af5e9ff2b` with clean index, 28 tracked
changes, 16 untracked files and zero session processes. No worker install or
production deployment occurred. The installed mirror remains deliberately at
`1bef4b01a0ddd71f71279721bad908867cc21c3c`; the new contract rejects that
difference from canonical instead of fetching, resetting, reassigning or
inventing ownership during admission.

Sanitized task-0.2 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-0.2-canonical-source-admission`;
the SHA-256 of its `SHA256SUMS` is
`da95d1047002253d983e1b877eac1d955d598065d9a60d33f820d8cd30ca8fb9`.

Task 0.3 is complete and change progress is `3/57`; the exact resume point is
task 0.4.

Atenea now has a durable `DRAFT_BLOCKED` state separate from active and closed
sessions. The mediated recovery locks the exact stale remote Atenea
WorkSession, refuses non-terminal AgentRuns, observes the accepted canonical
source and requests a sanitized AX42 fingerprint. The worker accepts only its
fixed root-owned Atenea route, current mirror commit, exact registered
WorkSession and inactive execution ownership. The result contains hashes,
counts and immutable identities only; fixed Git operations have finite
timeouts plus bounded entry and byte limits.

The old WorkSession is flushed as `DRAFT_BLOCKED` before the replacement
`OPEN` row is inserted, preserving the one-active-session database invariant
inside one transaction. The replacement receives a new remote identity,
workspace branch and accepted canonical observation. External thread, final
commit and draft metadata are not transferred. A completed recovery is
idempotent and returns its persisted replacement without another worker call
or session creation. No rebase, merge, reset, commit, checkout, clean, copy or
draft deletion occurs.

The Atenea implementation is published cleanly at
`a94c119e561fe9a70b158fae54cd333a8507c541`. Two accepted backend passes each
ran 425 tests with zero failures, errors or skips and validated all 51 Flyway
migrations. Two worker passes each ran 20 tests with zero failures; shell
syntax validation also passed. External timeouts were 600 seconds for backend
passes and 120 seconds for worker passes.

The real retained draft remains unchanged at
`d5ea39e7b575b63c6fff3a66a0400c5af5e9ff2b`, with tree
`7e4531a5c5538d4f30fdb63d588db1afc9e34ddc`, clean index, 28 tracked
changes, 16 untracked files and zero session processes. Its tracked and
untracked fingerprints still match task 0.1. No backend, migration or worker
deployment and no real recovery occurred; task 0.10 retains ownership of
creating the current clean Atenea WorkSession after all remaining foundation
gates pass. The installed worker mirror therefore remains deliberately at
`1bef4b01a0ddd71f71279721bad908867cc21c3c`.

Sanitized task-0.3 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-0.3-retained-draft-recovery`;
the SHA-256 of its `SHA256SUMS` is
`246f3f7aa197a4907faf88851c32ce5b09b3022540243cf1753ba4ab469869d1`.

Task 0.4 is complete and change progress is `4/57`; the exact resume point is
task 0.5.

AgentRun now persists a separate terminal process outcome constrained to agree
with lifecycle status. A successful Codex process therefore means only
`processOutcome=SUCCEEDED`; it does not imply build, test, review, publication
or task acceptance.

WorkSession independently persists `DRAFT`, `VALIDATING`, `BLOCKED`,
`VALIDATED` and `INTEGRATION_READY` acceptance states. The projection binds an
exact source-tree SHA-256, observation time, validation-projection SHA-256 and
validator-definition revision. Blocked state names one bounded missing or
failed check and the next permitted action. Integration readiness is accepted
only from `VALIDATED` with the identical tree, projection and definition
revision and performs no implicit commit, publication or deployment.

Starting another AgentRun conservatively removes earlier validation and
readiness. Observing any different tracked or untracked source-tree
fingerprint clears the complete validation projection and both validated and
integration-ready times atomically. Re-observing the identical tree preserves
the accepted projection.

AX42 source now includes a closed source-tree fingerprint operation. It accepts
only the fixed current Atenea route, current mirror commit and exact registered
WorkSession, runs fixed bounded Git operations and returns only the HEAD,
fingerprint and counts. File names, contents, caller commands, paths and
environment values are not returned or accepted.

Task 0.5 is complete and change progress is `5/57`; the exact resume point is
task 0.6.

Atenea now exposes only the symbolic `BACKEND_TEST`, `WEB_BUILD` and
`ANDROID_BUILD` validation operations. Each operation derives its immutable
identity from the exact remote WorkSession, current sanitized source-tree
fingerprint and versioned validator definition. Repeating that identity returns
the durable operation instead of starting a duplicate. The persisted result
contains only lifecycle state, exit code, bounded duration, sanitized summary
and artifact-manifest SHA-256. The acceptance projection remains separate and
becomes `VALIDATED` only after all three exact operations succeed; no commit,
publication, routing or deployment is implied.

The AX42 worker accepts an exact fixed-field request and independently
re-observes the registered Atenea workspace before admission. Unknown or extra
fields, foreign ownership, altered operation or definition, and changed source
fail closed before the mediator starts. The root-owned mediator accepts exactly
four validated positional identities, resolves the worktree only from the
root-owned registry, uses fixed commands in an isolated copy, applies
900/600/1200-second timeouts and deletes raw command output after hashing it.
The Android definition uses an empty environment and explicitly unavailable
secret files, so validation has no APK or Firebase credential authority.
Interrupted durable `RUNNING` validations reconcile to a sanitized `BLOCKED`
terminal state after worker restart.

Two accepted backend passes each ran 435 tests with zero failures, errors or
skips and validated all 53 Flyway migrations. Two worker passes each ran 24
tests with zero failures. Python compilation, shell syntax checks and strict
OpenSpec validation passed. The isolated database container and network were
removed after the suites; the named test database volume remains retained.
No worker installation, service restart, real validation, production change or
WorkSession mutation occurred in task 0.5.

Sanitized task-0.5 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-0.5-closed-validation-operations`;
the SHA-256 of its `SHA256SUMS` is
`2d81fdae178520a167e8d698faea2de184d354924d44108d38317f1ce5791877`.

Task 0.6 is complete and change progress is `6/57`; the exact resume point is
task 0.7.

`PLAYWRIGHT_ACCEPTANCE` extends the same immutable validation identity and
acceptance projection with definition
`atenea-playwright-acceptance-v1` and a fixed 600-second outer timeout. Its
root-owned runner derives the exact slot only from the WorkSession allocation,
requires the locked Playwright 1.60.0 module and image, builds the web source in
the isolated validation copy and starts one exact-labelled rootless container.
The caller supplies no URL, route, viewport, assertion, browser, image, network,
mount, path, slot or environment.

The browser container has no network, drops all capabilities, has a read-only
root, bounded memory/PIDs and a finite writable `/tmp`. A fixed in-container
loopback server presents the built SPA. Playwright separately proves HTTP/data,
non-empty visible DOM and no horizontal overflow at `1440x900` and `390x844`,
then retains only the two PNGs and a sanitized report containing dimensions,
counts, booleans and SHA-256 values. Pages, contexts, browser and server close
in `finally`. `--rm` removes the exact container; timeout cleanup removes it
only when all three immutable ownership labels match, and retains any foreign
same-name object fail-closed.

Two synthetic Playwright passes completed with HTTP 200, visible critical
content, no horizontal overflow and deterministic two-viewport reports.
Desktop and mobile screenshots were inspected at original resolution: content,
state and long identifiers are readable with no clipping, overlap or
off-screen rendering. Two worker passes each ran 24 tests without failure. Two
backend passes each ran 435 tests with zero failures, errors or skips and
validated all 54 Flyway migrations. Shell syntax, JavaScript syntax and strict
OpenSpec validation passed. Temporary browser processes, backend containers and
networks were removed; no real WorkSession, worker installation, production,
routing, preview or Beautips resource changed.

Sanitized task-0.6 evidence, including the inspected original-resolution PNGs,
is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-0.6-closed-playwright-acceptance`;
the SHA-256 of its `SHA256SUMS` is
`db4611b2c718a19ea78737e78268da4ef9f24c1d5659b223680f8394267515be`.

The Atenea implementation is published cleanly at
`e4947afc0cc6011df14d5d8a6396ec31a977fe8d`. Two backend passes each ran 431
tests with zero failures, errors or skips and validated all 52 Flyway
migrations. Two worker passes each ran 21 tests with zero failures; Python and
shell syntax validation also passed. No backend, database migration or worker
deployment occurred, and no real production acceptance projection was
written. The retained stale draft and installed worker mirror remain
unchanged.

Sanitized task-0.4 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-0.4-truthful-acceptance`;
the SHA-256 of its `SHA256SUMS` is
`88a9e53f34bc1026003d187b6709a8c0f86dd04176373c513208d09e3c0b1006`.

Task 0.7 is complete and change progress is `7/57`; the exact resume point is
task 0.8.

Atenea now persists one closed three-role repository set per remote
WorkSession. `ATENEA_CODE`, `PROGRAMME_OPENSPEC` and `WORKER_SOURCE` share one
immutable change identity while retaining their own exact repository, branch,
commit, mirror/worktree SHA-256 identity, validation profile and readiness.
The database requires both source and validation-projection fingerprints
before a role can become `VALIDATED` or `INTEGRATION_READY`. Linked readiness
cannot advance while any component remains `DRAFT`.

The AX42 worker contract accepts no caller repository, path, branch, mirror,
authority, validation profile or command. Its fixed root-owned mediator derives
the registered Atenea workspace, creates distinct programme and worker-source
worktrees from the reviewed programme commit and assigns them to separate
non-login operating-system identities with no group/other permissions. The
installed root-owned worker remains outside both writable roles. Repeating the
same identity is idempotent; alternate change identities, foreign commits,
extra fields, missing ownership and ambiguous pre-existing paths fail closed.

Two backend passes each ran 440 tests with zero failures, errors or skips and
migrated an empty PostgreSQL database through all 55 Flyway migrations. Two
worker passes each ran 26 tests with zero failures and each also passed the
synthetic multi-worktree ownership scenario. Focused API/service, Python
compile, shell syntax, diff and strict OpenSpec validation passed. Test
containers and networks were removed while the named test database volume was
retained.

No worker installation, deployment, real repository-role creation,
production, routing, preview or Beautips change occurred. The installed worker
and its deliberately stale mirror refs remain unchanged pending later
foundation rollout tasks.

Sanitized task-0.7 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-0.7-multi-repository-roles`;
the SHA-256 of its `SHA256SUMS` is
`de64f78e63956ff9c466ed185700a8454b300c61e7d58b60fd7fd81d3f469022`.

Task 0.8 is complete and change progress is `8/57`; the exact resume point is
task 0.9.

Every new exact remote project AgentRun now persists the reviewed instruction
bundle revision, combined SHA-256, platform-source SHA-256, fixed
`AGENTS.md` path and repository-source SHA-256 before dispatch. Atenea and
Beautips have separate project and combined fingerprints while sharing one
root-owned platform policy. Historical runs remain truthful rather than being
backfilled with an instruction identity they did not execute.

The worker accepts only the project-specific closed fingerprints. The runner
independently verifies the root-owned, non-writable platform file and compares
the worktree bytes of `AGENTS.md` with both the expected SHA-256 and
`HEAD:AGENTS.md` from the exact accepted commit. A changed file,
`AGENTS.override.md`, repository `.codex` content, missing source, unsafe
ownership or any conflicting fingerprint blocks before Codex starts.

Inside the reviewed Bubblewrap namespace, global `AGENTS.md` and
`AGENTS.override.md` plus automatic repository instruction discovery are
masked. The verified platform and repository contents are instead injected as
one explicit developer-instruction bundle. `--ignore-user-config` continues to
exclude personal configuration and `--ignore-rules` excludes ambient
exec-policy rules. The request has no instruction content, rule-source path,
configuration fragment or override authority.

Two backend passes each ran 441 tests with zero failures, errors or skips and
migrated empty PostgreSQL databases through all 56 Flyway migrations. Each of
two worker rounds passed 26 AgentRun-worker tests, 9 instruction-runner tests,
4 Beautips adapter tests, 5 Beautips mediator tests and the synthetic install
lifecycle. Python compilation, shell syntax, JSON parsing, immutable source
hashes, diff checks and strict OpenSpec validation passed.

No worker installation, real AgentRun, deployment, production, routing,
preview or Beautips resource changed. The installed worker and deliberately
stale mirror remain unchanged for the later foundation rollout gate.

Sanitized task-0.8 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-0.8-reviewed-instruction-bundle`;
the SHA-256 of its `SHA256SUMS` is
`de0d49bf0d2f9d7880401273d8e269c8bdd5b5948677aca6f0d28e812b497631`.

Task 0.9 is complete and change progress is `9/57`; the exact resume point is
task 0.10.

The project dispatch schema and worker now carry a permanent negative
authority matrix. Caller-supplied commands, images, Compose files,
environments, paths, hosts, slots, endpoints, credential references and rule
sources are rejected as unknown fields. Foreign repositories fail the fixed
project identity, while foreign WorkSession ownership fails the exact
registered workspace identity. The schema additionally binds the workspace to
`remote:ax42-01:work-session:<canonical UUID>` before the worker verifies its
persisted session relationship.

Every matrix case is asserted to stop before execution state is created or a
runner process can start. The durable worker execution map remains identical
and the root-owned project configuration remains byte-equivalent after each
denial. Atenea's client test independently proves that the control plane emits
only the thirteen reviewed workload fields and none of the prohibited
authorities.

Two backend passes each ran 441 tests with zero failures, errors or skips from
empty PostgreSQL databases through all 56 Flyway migrations. Two worker rounds
each passed 27 AgentRun-worker tests, 9 runner/schema tests and 4 shared
Beautips adapter tests. JSON parsing, diff checks and strict OpenSpec
validation passed.

No installation, real WorkSession or AgentRun, deployment, production,
routing, preview or Beautips resource changed. All rejected inputs were
synthetic non-secret references and no rejected credential value was read.

Sanitized task-0.9 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-0.9-closed-authority-denial`;
the SHA-256 of its `SHA256SUMS` is
`05e616c10027e88191efe897004142bfb4ef5c93b9afc7d0651fe517e22cea9c`.

Task 0.10 is complete and change progress is `10/57`; the exact resume point
is task 1.1.

The final Atenea source is clean and synchronized at
`ec867f75bd4bb58f582607cf0025a003400f02c8`. Two clean-container backend
passes against separate empty PostgreSQL databases each ran 445 tests with
zero failures, errors or skips and applied all 56 Flyway migrations. Two final
worker passes each accepted the AgentRun worker, Atenea runner, Beautips
adapter, Beautips operation mediator, multi-repository, Playwright, retained
installer and shared installer suites; shell syntax also passed. The accepted
backend passes took 40 and 43 seconds, and the worker passes took 58 and 57
seconds, under external timeouts of 600 and 180 seconds respectively.

Production runs backend image
`sha256:7b62d5459831ede557e6277e6252a891e79230e2b52ce57d4ac9277c0928e36d`
with zero restarts and schema V56. A root-only, profile-gated command invoked
the same transactional recovery service without reading an operator token or
modifying the database directly. Its exact authority bound database row 6,
remote UUID `c750641d-3226-44c3-81dc-d9149aac0de1`, retained HEAD
`d5ea39e7b575b63c6fff3a66a0400c5af5e9ff2b` and accepted commit
`ec867f75bd4bb58f582607cf0025a003400f02c8`. The sanitized result exposed no
values, marked row 6 `DRAFT_BLOCKED` and created row 7 `OPEN` with remote UUID
`83356a20-421c-4d5f-8de6-05c98cce1c32`.

The stale draft remains byte-equivalent to task 0.1: clean index, 28 tracked
changes, 16 untracked files, tracked diff SHA-256
`fe004b66dc9d76da024c6c514ccd7992b6846b2556fab8694bbfd3feb6257fa8`
and untracked manifest SHA-256
`b7b2d520213300600bdbb3bd005ede283fd505f24be31d4e018e90a144fc4fa8`.
Its released allocation conflicted with safe reuse of fixed slot 2. Following
D-048 and new decision D-051, the marker was renamed to
`runtime-allocation-v1.retired.json` only after semantic equality with sealed
task-0.1 evidence and zero owned containers, networks, images, listeners and
runtime unit were proved. SHA-256 remained
`f143453718f4c8758665a02986ce44c607feff3f44cc0971100fb63ab4ac1cac`
before and after the rename.

The replacement worktree is clean on branch
`atenea/session-83356a20-421c-4d5f-8de6-05c98cce1c32` at the exact accepted
commit. It owns slot 2 and heavy 1, one allocation and the only enabled Atenea
worker registration, but no runtime container, network, listener or process
was started. The installed registration path disables optional Git locks so a
root-owned read cannot replace the worker-owned index; an idempotent activation
repeat preserved index ownership `atenea-worker:atenea:0644`.

Non-terminal AgentRuns and previews for the retained/replacement sessions are
zero. Slot inventories remain `3/0/0/3`; production, preview, administrative
Beautips and WorkSession Beautips are `UP`; rootful Docker remains
inactive/masked; SSH, Tailscale, UFW and the worker are active; all RAID arrays
remain `[UU]`. Temporary installer fixtures were removed by exact identity.

Sanitized task-0.10 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-0.10-foundation-current-worksession`;
the SHA-256 of its `SHA256SUMS` is
`76cbdf25b6f49ed78c3ba16a536edc20e52e43053adb7ed2a60153679ee4cc0b`.

Task 1.1 is complete and change progress is `11/57`; the exact resume point is
task 1.2.

The entry baseline records clean synchronized Atenea source at
`ec867f75bd4bb58f582607cf0025a003400f02c8`, clean synchronized programme
source at `54f489d2d2b8b5359c11812f59c474b210a64741`, and AX42 mirror refs at those
same commits. Production and preview are `UP`; the backend and both App Server
containers are running with zero restarts; schema remains V56. The two open
remote sessions are only Beautips row 4 and current Atenea row 7, with zero
non-terminal AgentRuns.

The installed worker is active with protocol `agent-run-worker/v1`, capacities
4 normal and 2 heavy, plus synthetic and exact-project capabilities. Atenea and
Beautips each have one exact enabled registration. Installed programme and
runner fingerprints pass verification, slot inventories remain `3/0/0/3`,
SSH, Tailscale and UFW are active, and all RAID arrays remain `[UU]`.

The effective AX42 project runner currently invokes standalone Codex CLI
`0.145.0` with SHA-256
`a2a05dafaa1acb002a45eaec0a462de5b13694fcfcd7bc43305f14781ce7be14`.
Production and rescue App Servers contain Codex CLI `0.130.0`. The runner has
no explicit model or reasoning-effort option, ignores user configuration and
ambient rules, and persists no AgentRun effective model, effort or Codex
version. This observed difference is retained truthfully for the precedence
and catalog decisions in task 1.2 rather than being normalized during capture.

The FCM/device projection contains two active Android devices, one reporting
app `0.5.94` and one `0.5.95`, plus three sent `RUN_SUCCEEDED` notification
records and zero notification records for current Atenea session 7. No push
token, device identifier, notification body, credential, environment dump,
auth file, prompt, answer or execution result was read or retained.

Sanitized task-1.1 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-1.1-entry-baseline`;
the SHA-256 of its `SHA256SUMS` is
`b5d6a97596b072282ddc28adc629b341b711fd480ad765c08c50d7c922b0f6fb`.

Task 1.2 is complete and change progress is `12/57`; the exact resume point is
task 1.3.

Model and effort now resolve independently through exact `NEXT_TURN`,
`WORK_SESSION`, `PROJECT`, `PLATFORM` and `WORKER_DEFAULT` precedence. Every
future AgentRun must persist both field sources, the canonical values, catalog
revision and exact Codex version before dispatch. Settings never rewrite an
earlier run.

The worker catalog fields, digest boundary and per-model effort advertisement
are fixed. The only recognized effort vocabulary is `none`, `low`, `medium`,
`high`, `xhigh` and `max`, intersected fail-closed with worker and
platform/project policy. Friendly aliases, Pro mode and Ultra operation do not
become persisted execution-profile values. These decisions were checked
against the current official GPT-5.6 migration and prompting guidance rather
than inferred from the older installed CLI baseline.

The exact progress taxonomy contains thirteen sanitized categories. Identical
consecutive category/message pairs coalesce before sequence allocation; each
run retains its newest 200 normalized events without sequence reuse, while
current/latest/terminal/elapsed/next-action projections remain independent.
Raw reasoning, commands, output, environment and secret-bearing payloads remain
forbidden.

The routine, privileged and platform-administrator role matrix is now closed.
Binary update planning and staging require platform administration; activation
uses a finite single-use exact authorization, and an operator-requested
rollback requires a separate authorization. The activation authority covers
only automatic restoration of its exact previous version after a failed gate.

`RUN_COMPLETED`, `RUN_FAILED` and `ACTION_REQUIRED` default enabled for active
Android devices without an explicit preference. Explicit device preferences
survive re-registration/application upgrade, while intermediate progress stays
in-app/SSE and produces no push notification.

Final read-only checks confirmed clean Atenea source, production and preview
`UP` with zero backend/App Server restarts, active AX42 worker/SSH/Tailscale/UFW,
all RAID arrays `[UU]` and rootful Docker inactive. No runtime, routing,
database, WorkSession, AgentRun, slot, device or notification delivery changed.

Sanitized task-1.2 evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-1.2-contract-freeze`;
the SHA-256 of its `SHA256SUMS` is
`07bcd219a0316538df281f0069b5d00c73c209e42b760519721cd64c0871ba24`.

Task 1.3 is complete and change progress is `13/57`; the exact resume point is
task 1.4.

The production baseline remains Flyway V56. The accepted design reserves V57
through V61, in order, for execution profile/catalog inventory, bounded
progress, idempotent recovery, generic notification events/preferences/
deliveries and managed Codex update inventory/operations. Every migration is
expand-only: legacy AgentRuns and push logs are neither backfilled with
invented values nor deleted, and migration itself enables no behavior.

Five independent profile, progress, recovery, notification-outbox and
managed-update gates are fixed default-false. Rollout applies the schema, then
deploys reader-compatible backend, dual-compatible worker and clients before
synthetic capability-by-capability activation. Notification cutover stops the
old category producer before its generic dispatcher starts, preventing a
dual-send window.

Before any production V57 application, the production backup authority must
create a PostgreSQL 16 custom-format V56 backup and restore it in a disposable
network-isolated fixture. That fixture must reproduce the sanitized baseline,
accept V57–V61 twice with the second pass a no-op, pass candidate tests and run
the exact intended rollback image. If that image rejects future Flyway history
or expanded reads, production migration remains blocked until a compatibility
image containing V57–V61 with every new gate disabled passes.

Rollback is explicitly disable-first: reject new update/recovery/profile work,
stop generic push/progress publication, block new affected dispatch and
reconcile persisted ownership before restoring only a fixture-proven
compatible application. Expanded rows, devices, deliveries, WorkSessions,
routing and affinity remain. Flyway repair, destructive down migration,
notification replay and automatic schema contraction are forbidden.

Task 1.3 was documentation-only. No backup was created, no migration was
applied and no runtime, production, routing, database or worker state changed.
Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-1.3-migration-rollback-design`;
the SHA-256 of its `SHA256SUMS` is
`0ac9a8a867df7078bede37d6164072728dce34192291aa2538c23b375499b98e`.

Task 1.4 is complete and change progress is `14/57`; the exact resume point is
task 1.5.

The programme now contains additive executable schemas for the canonical
worker model catalog, `project-codex-v2` dispatch/result, sanitized progress
and the closed authenticated settings/recovery/update API request union. The
currently installed `project-codex-v1` remains unchanged; v2 is a contract for
the later worker implementation tasks, not an implicit activation.

The v2 workload adds only canonical model, effort, catalog revision and Codex
version. API callers may name persisted WorkSession, AgentRun, plan, candidate
and authorization identities, but cannot submit a workspace, command,
provider, endpoint, path, service, host, slot, environment, credential or
release URL. Those authorities remain fixed and server-derived.

Schema validation is followed by exact semantic validation. The catalog digest
is canonical, its model identities are unique, each default effort belongs to
the advertised model set, and dispatch must match the accepted catalog/Codex
version plus the exact registered `(sessionId, workspaceIdentity)` pair. Thus a
well-formed arbitrary model or foreign UUID still fails before execution state
or process creation.

The synthetic corpus covers twelve negative model, effort, catalog, command,
provider, endpoint, path, service, update and foreign/ambiguous ownership cases.
Progress separately rejects reasoning, raw command/output and environment
fields. The new contract tests and existing v1 project runner/worker suites ran
40 tests with zero failures, errors or skips in under four seconds. Every JSON
document parses, `git diff --check` and strict OpenSpec validation pass.

No contract was installed and no runtime, production, routing, database,
WorkSession, AgentRun, worker service or Codex process changed. Sanitized
evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-1.4-closed-schemas-negative-fixtures`;
the SHA-256 of its `SHA256SUMS` is
`f7ff10be7fd6c70f168432d7bbc35a949bf77d89220941cdf95a8041f2e81030`.

Task 1.5 and Phase 1 are complete. Change progress is `15/57`; the exact
implementation resume point is task 2.1. Tasks 2.2 and later remain pending.

Strict OpenSpec validation passes from the programme environment, every JSON
contract parses and the same new contract suite passes in the canonical
repository. The Atenea host itself does not have the `openspec` executable, so
its attempted command returned 127 without changing state; this is an explicit
tooling fact, not a validation failure or an authority to install host-global
software.

Atenea code remains clean and synchronized at
`ec867f75bd4bb58f582607cf0025a003400f02c8`; programme code was clean and
synchronized at the task-1.4 commit before this closure. Production and preview
are `UP`, backend/App Server restarts are zero, AX42 worker/SSH/Tailscale/UFW
are active, every RAID array is `[UU]`, rootful Docker is inactive and no v2
schema is installed on the worker.

Task 2.1 must begin by implementing only V57 and its persistence model in the
Atenea code repository: nullable WorkSession/project defaults, immutable
AgentRun model/effort plus independent sources, catalog revision, Codex version
and normalized worker catalog inventory. Existing V56 rows remain explicitly
profile-absent, all five feature gates stay false and no production migration,
v2 installation or managed Codex update is implied.

Sanitized phase-closure evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-1.5-phase-1-closure`;
the SHA-256 of its `SHA256SUMS` is
`d8ccbe68b5d0dc10616254de146c8fab684cdc65036f37fae373445effe22a3e`.

Task 2.1 is complete and change progress is `16/57`; the exact implementation
resume point is task 2.2. Tasks 2.2 and later remain pending.

Atenea commit `77c813104d02290ecd7c4c263055ace7e56ad71c` adds only V57
and its persistence model. Project and WorkSession model/effort defaults are
independently nullable. An AgentRun execution profile is immutable and must be
either absent for legacy V56 history or complete with effective model, effort,
both independent sources, catalog revision and Codex version. Normalized worker
catalog, model and effort inventory is present, and only the canonical efforts
`none`, `low`, `medium`, `high`, `xhigh` and `max` are accepted.

Five focused persistence tests and two complete 450-test passes against
separate fresh PostgreSQL 16 databases passed with zero failures, errors or
skips. The exact V56-to-V57 fixture retained legacy null history, accepted an
independent WorkSession effort default and a complete AgentRun snapshot, and
rejected `ultra` plus partial snapshots fail-closed. Test containers and
networks were removed. Raw authentication integration logs were not retained.

The canonical Atenea branch and remote are clean and synchronized at that
commit. Production and preview remain `UP` with zero backend restarts;
production remains on Flyway V56. No production migration, routing, runtime,
WorkSession, AgentRun, worker, notification or device state changed.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-2.1-execution-profile-persistence`;
the SHA-256 of its `SHA256SUMS` is
`7d0e0ac7e09c9fe52f710bf0eadef84e64ea3aaddfeb6de48f7ca403ee45e6fc`.

Task 2.2 is complete and change progress is `17/57`; the exact implementation
resume point is task 2.3. Tasks 2.3 and later remain pending.

Atenea commit `63bd7c1eac15cbd1865f6718f8c17aec28c230af` adds V58,
the durable event entity/repository and transactional append/replay service.
An exact AgentRun row lock serializes allocation. Identical consecutive
category/template pairs coalesce before allocation, sequences are never reused
and insertion beyond 200 events advances the retained floor and removes only
older detail rows. Current/latest state, terminal outcome, elapsed time and
required next action remain separate AgentRun projections.

The thirteen category messages are closed templates enforced in both Java and
PostgreSQL; free-form or credential-shaped message insertion is rejected. A
terminal category must match the persisted AgentRun outcome. A client below
the retained floor receives the projection and retained gap, while a legacy
run with no progress remains explicitly projection-absent.

Eleven focused persistence tests passed. Two complete 456-test passes against
separate fresh PostgreSQL 16 databases migrated through V58 passed with zero
failures, errors or skips in 43 and 44 seconds. Source and Maven dependencies
were read-only, database ports were not published, and the exact fixed test
workspace was separately writable. No test container, network, volume or raw
authentication log remains.

The canonical Atenea branch and remote are clean and synchronized at that
commit. Production and preview remain `UP` with zero backend restarts;
production remains on Flyway V56. No production migration or operational
state changed.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-2.2-bounded-progress`;
the SHA-256 of its `SHA256SUMS` is
`c2db676350664d73e2d7552cf80cf3c16ea3f5dab55c1a8e198740790ee77a30`.

Task 2.3 is complete and change progress is `18/57`; the exact implementation
resume point is task 2.4. Tasks 2.4 and later remain pending.

Atenea commit `cf3dfacaa6b6b4b732b38a536fafa58ee5e13296` adds V59
and closed recovery persistence. Operator accounts default to
`ROUTINE_OPERATOR`; every operation snapshots that persisted role and binds an
exact operator, WorkSession, AgentRun, action, idempotency key and canonical
request fingerprint. Composite ownership rejects a foreign run/session pair.
Exact repetition returns the existing operation, while conflicting key reuse
fails closed.

Routine cancel, retry, reconciliation and diagnostic requests are permitted.
A routine restart attempt is retained as an actionable `ROLE_REQUIRED`
rejection without invoking any service. Privileged roles may persist only the
two fixed mediated restart actions. State/outcome, exact sanitized summary and
next-action combinations are constrained in PostgreSQL. `RETRY_CREATED`
requires one same-session result AgentRun with immutable `retryOfRunId`
lineage to the failed source; the original attempt remains unchanged.

Seventeen focused persistence tests passed. The final two complete 462-test
passes against separate fresh PostgreSQL 16 databases migrated through V59
passed with zero failures, errors or skips in 45 seconds each. Read-only source
and dependencies, isolated workspaces and databases without published ports
were used. No task container, network, volume or raw authentication log
remains.

The canonical Atenea branch and remote are clean and synchronized at that
commit. Production and preview remain `UP` with zero backend restarts;
production remains on Flyway V56. No real recovery operation, production
migration or operational state change occurred.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-2.3-idempotent-recovery`;
the SHA-256 of its `SHA256SUMS` is
`85086b48f96e6de5e69a3ef8bad6a42b8b21012135ec9f6b395df8ebe505e025`.

Task 2.4 is complete and change progress is `19/57`; the exact implementation
resume point is task 2.5. Tasks 2.5 and later remain pending.

Atenea commit `a15719e8c2c54502c4b66a586481e62b061c2f20` adds V60
and the generic notification outbox persistence service. Events are limited to
`RUN_COMPLETED`, `RUN_FAILED` and `ACTION_REQUIRED`; their title/body and link
kind are exact `agent-run-safe-v1` database-enforced templates. Event identity
binds category, AgentRun and source revision to a SHA-256 deduplication key and
composite WorkSession/AgentRun ownership. Prompt, answer, internal worker detail
and device token are absent from event and delivery rows.

An absent per-device/category preference means enabled, while an explicit row
wins and survives re-registration. Each active enabled device receives at most
one `(event, device, FCM)` delivery with bounded attempt/expiry state ready for
the later dispatcher task. This task persisted no real event and did not
activate or invoke FCM.

Twenty-three focused persistence tests passed. Two complete 468-test passes
against separate fresh PostgreSQL 16 databases migrated through V60 passed
with zero failures, errors or skips in 47 and 44 seconds. Read-only source and
dependencies, isolated workspaces and unexposed databases were used. No task
container, network, volume or raw authentication log remains.

The canonical Atenea branch and remote are clean and synchronized at that
commit. Production and preview remain `UP` with zero backend restarts;
production remains on Flyway V56. No device, notification, production database
or operational state changed.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-2.4-generic-notification-outbox`;
the SHA-256 of its `SHA256SUMS` is
`413a2e015ecce66a12bbdb90c47c0b27c5001bf4883dfaa9718e63c96ba80bbc`.

Task 2.5 is complete and change progress is `20/57`; the exact implementation
resume point is task 2.6. Tasks 2.6 and later remain pending.

Atenea commit `b95ea1682bccdc65db45a102a2f580e5eda6d919` exposes
authenticated catalog, project/WorkSession future settings, immutable run
detail, durable progress replay, recovery request, owned device preference and
platform-administrator inventory endpoints. Profile writes require exact
current catalog/model/effort membership and never rewrite a historical
AgentRun. Recovery reuses V59 ownership and idempotence.

All write endpoints compare the exact JSON field set before conversion, so an
additional provider, endpoint, host, path, service, command or other authority
is rejected. Foreign devices are hidden. Administrator authority is resolved
from the current active database account instead of a token claim. Catalog and
inventory responses omit endpoint, credentials and device-token values.

The five independent profile, progress, recovery, notification-outbox and
managed-update gates now exist and default false. Seven focused HTTP tests and
the existing mobile controller regression set passed. Two final complete
475-test passes against separate fresh PostgreSQL 16 databases at V60 passed
with zero failures, errors or skips in 46 seconds each. The first pre-acceptance
full run exposed and led to removal of an incompatible principal constructor;
both final runs prove the corrected design.

The canonical Atenea branch and remote are clean and synchronized at that
commit. Production and preview remain `UP` with zero backend restarts;
production remains V56 and no new endpoint or gate was deployed or enabled.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-2.5-authenticated-operations-apis`;
the SHA-256 of its `SHA256SUMS` is
`45dc30a1433681cfed5087039fb2b394953a3dcc0cf418662498fd65ece31b96`.

Task 2.6 is complete and change progress is `21/57`; the exact implementation
resume point is task 2.7. Tasks 2.7 and later remain pending.

Atenea commit `5938c5d87db64d0f5b4f947bc0d81ce332109661` publishes
committed V58 progress through the existing shared web/mobile event feed while
the independently default-disabled progress gate is enabled. Each safe closed
category carries its persisted sequence and stable
`progress:{runId}:{sequence}` identity. Existing session, turn, run and
deliverable items also have stable identities, and the SSE connection seeds
and polls the bounded 200-item window by identity instead of timestamp.

When a run has committed terminal progress, the enabled feed publishes one
progress terminal and suppresses the parallel legacy lifecycle terminal. Its
single persisted `TURN_CODEX` remains the only conversation response. With the
gate disabled, no progress is published and the legacy terminal feed remains
available without rewriting history. Web and Android accept the same additive
identity and sequence fields.

Eleven focused backend/API/SSE tests passed. Two complete 478-test passes
against separate fresh PostgreSQL 16 databases at V60 passed with zero
failures, errors or skips in 43.274 and 48.058 seconds. Web production builds
and Android API Kotlin compilation each passed twice. The final suites used a
globally disabled synthetic bootstrap so only authentication tests created
their own operator. No task container, network, database volume or raw test
log remains.

The canonical Atenea branch and remote are clean and synchronized at that
commit. Production and preview remain running with zero backend restarts;
production remains V56 and no new code, migration or gate was deployed or
enabled.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-2.6-shared-progress-stream`;
the SHA-256 of its `SHA256SUMS` is
`88d2054778bc56c9c682dc36f35d9774fd95e4f0fc8a537acb6481f5016050e1`.

Task 2.7 and Phase 2 are complete. Change progress is `22/57`; the exact
implementation resume point is task 3.1. Tasks 3.1 and later remain pending.

Atenea commit `652eaa08934dd1e6a6261407596a95c5a6630aeb` adds the final
focused migration integration check and no production functionality. It
proves Flyway V57–V60 ordering, the expected additive tables, columns and
ownership constraints, and all five capability gates default false. Together
with the focused suites added throughout tasks 2.1–2.6, Phase 2 now has direct
migration, repository, service, authorization, API, SSE, idempotency and
sanitization coverage.

The combined focused set passed 34 tests. Two complete 479-test passes against
separate fresh PostgreSQL 16 databases at V60 passed with zero failures,
errors or skips in 43.592 and 43.434 seconds. Global synthetic authentication
bootstrap was disabled; authentication-specific tests opt in with their exact
fixture. No task container, network, database volume or raw test log remains.

The canonical Atenea branch and remote are clean and synchronized at that
commit. Production and preview remain running with zero backend restarts;
production remains V56. Phase 2 was not deployed or enabled, and AX42 worker
protocol/capability work begins only at task 3.1.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-2.7-focused-control-plane-tests`;
the SHA-256 of its `SHA256SUMS` is
`c4402aca10b2075045bc97b3c41d2a43204abba4ef1f215e309d7085eef2088b`.

Task 3.1 is complete and change progress is `23/57`; the exact implementation
resume point is task 3.2. Tasks 3.2 and later remain pending.

Programme/worker commit `48c201034bdfdbc4fcc10fcceb8a653c3194f769`
adds authenticated `GET /v1/codex/catalog` and the independent
`codex-model-catalog-v1` capability. The closed catalog contains exact worker
identity, Codex `0.145.0`, canonical revision, generation time and sorted model
entries. Its digest excludes generation time and matches accepted revision
`125b9437e38f83e04cb10996fc70d3ab44c32082009b8e897cb08bb340b13187`.

The initial inventory exposes only canonical `gpt-5.6-sol`, availability
`AVAILABLE`, default effort `medium` and its exact `none`, `low`, `medium`,
`high`, `xhigh`, `max` set. It exposes no alias, Pro/Ultra mode, provider,
endpoint, path, flag, configuration or credential. The strict v1 health shape
is unchanged, and executable `agent-run-project-codex-v2` remains withheld
until tasks 3.2 and 3.3 complete.

Two final 33-test worker/catalog passes succeeded with zero failures, errors
or skips under 120-second command bounds. AX42 independently reported the
fixed runner binary as `codex-cli 0.145.0`; its installed worker remained
active/running with zero restarts, the same program SHA-256 and the same
tailnet-only listener. This task was not installed or enabled. Production and
preview remained running with zero backend restarts and production stayed V56.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-3.1-worker-codex-catalog`;
the SHA-256 of its `SHA256SUMS` is
`a1109ee47e17724280e996148670790e1448bd7c3e7265ae9f16b01da5bf13dc`.

Task 3.2 is complete and change progress is `24/57`; the exact implementation
resume point is task 3.3. Tasks 3.3 and later remain pending.

Programme/worker commit `b42534bac10840c701b206032e344b78a490b291`
adds staged `project-codex-v2` validation and its canonical immutable request
fingerprint. Exact model, effort, catalog revision and Codex version extend the
existing complete project, source, manifest, instruction and persisted
session/workspace ownership identity rather than replacing it.

Unsupported model/effort, stale revision/version, foreign workspace and added
provider or other caller authority all fail before an execution row or process
exists. A valid v2 create also remains fail-closed as
`profile_execution_unavailable`; task 3.3 must make the fixed runner enforce
the profile before v2 execution can be persisted or scheduled.

Two final 35-test worker/protocol passes succeeded with zero failures, errors
or skips under 120-second bounds. They prove an effort change changes the
fingerprint and every rejection retains empty execution state. AX42's installed
worker remained active with zero restarts, identical program SHA-256 and the
same private listener; nothing was installed, enabled or dispatched.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-3.2-profiled-workload-fingerprint`;
the SHA-256 of its `SHA256SUMS` is
`0f84ee5c2281a93cda6e9e5ab3475e8a519bbcff462dccd1e30a9af0a597f36d`.

Task 3.3 is complete and change progress is `25/57`; the exact implementation
resume point is task 3.4. Tasks 3.4 and later remain pending.

Programme/worker commit `7c3a66ca83e76e9cbb4ac85733a0e57e26d5d4df`
connects validated `project-codex-v2` requests to the existing fixed project
runner. Only exact `--model` and canonical `model_reasoning_effort` arguments
are added to the reviewed command. Prompt remains stdin-only, while provider,
profile, endpoint, path, environment, credential and arbitrary flags remain
outside caller authority.

The runner probes only the fixed Codex binary and requires exact
`codex-cli 0.145.0` before execution. It echoes model, effort, catalog revision
and Codex version; the worker rejects a mismatching effective result as a
sanitized failure. Existing v1 execution remains compatible, and v2 capability
appears only under the existing exact project-selection gate.

Two final 47-test worker/runner/contract passes succeeded with zero failures,
errors or skips under 120-second bounds. AX42's real CLI help and version were
observed read-only. Its installed worker and runner hashes, private listener,
active service and zero restart count remained unchanged; nothing was
installed, enabled or dispatched.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-3.3-fixed-profiled-runner`;
the SHA-256 of its `SHA256SUMS` is
`4bec258f08e5d24d9c2ecd94ac1cdc6208df9346f9d9ba652ed4ef2fd39a94e2`.

Task 3.4 is complete and change progress is `26/57`; the exact implementation
resume point is task 3.5. Tasks 3.5 and later remain pending.

Programme/worker commit `54e0df2e310e0e65c80578389921f87e73bdead4`
adds a closed Codex JSONL normalization boundary. Recognized lifecycle and
tool shapes become only fixed messages from the thirteen-category taxonomy.
Reasoning, agent messages, command arguments, command output, searches,
environment values, unsupported events and every other source payload field
are discarded rather than copied or sanitized heuristically.

The worker accepts only exact category/message pairs from that boundary,
replaces the source timestamp, binds dispatch and execution identity, assigns
monotonic sequences, coalesces identical consecutive events before sequence
allocation and retains the newest 200 without sequence reuse. Progress remains
separate from final answer and effective profile. Restart/delivery
idempotence remains task 3.5.

Two final 50-test worker/runner/contract passes succeeded with zero failures,
errors or skips in 3.67 and 3.86 seconds under 120-second bounds. The Beautips
session/worker compatibility suite also passed. AX42's installed worker,
runner, Codex version, private listener, active service and zero restart count
remained unchanged; nothing was installed, enabled, dispatched or restarted.
Production and preview remained running with zero backend restarts.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-3.4-safe-progress-normalization`;
the SHA-256 of its `SHA256SUMS` is
`9bc042d2a5980f96f527b155b598caa0f91788e6c37757a9e55faaefada3c6b2`.

Task 3.5 is complete and change progress is `27/57`; the exact implementation
resume point is task 3.6. Tasks 3.6 and later remain pending.

Atenea commit `4765c93a0fb871a4e2b8e1ab1902eb3701c9dfc6`
adds a V58 worker-source cursor and imports only strictly owned, ordered,
fixed-message progress. The coordinator takes the owning AgentRun's
pessimistic row lock before processing a response. Imported sequences,
terminal status, external thread/turn identities and the single result turn
therefore share one transaction; repeated polling, concurrent coordinators and
startup reconciliation cannot create a second persisted event or response.

Programme/worker commit `7bf5c1d7011b49c02e549b2af070e0b99d3329e4`
adds byte-stability coverage for a terminal execution reloaded from durable
worker state. Repeating its immutable create request returns the same execution
identity, lifecycle revision, result and normalized sequence list.

Two final 30-test Atenea passes against separate empty PostgreSQL 16 databases
migrated to V60 succeeded with zero failures, errors or skips in 24.08 and
22.96 seconds. Two final 51-test worker/runner/contract passes also succeeded
in 3.85 and 3.78 seconds. Every exact synthetic database container was removed
by recorded ID. AX42's installed service, hashes, version, private listener and
zero restart count remained unchanged; production and preview remained
running with zero backend restarts. Nothing was installed or enabled.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-3.5-idempotent-progress-terminal-replay`;
the SHA-256 of its `SHA256SUMS` is
`25f00ab8cf37018644bb7d8a33e5649747904036572a9251ef071a001bbfcb08`.

Task 3.6 is complete and change progress is `28/57`; the exact implementation
resume point is task 3.7. Task 3.7 and all later tasks remain pending.

Programme/worker commit `3c9af70133f7a865646b24974ceddd99ebc2079d`
adds authenticated exact-cancel, read-only reconciliation inspection and
sanitized doctor routes. The new operations require complete dispatch-path,
execution, session, workspace and lease ownership. Added command, host,
service, path, slot, endpoint, environment or credential fields and all
foreign, stale or partial identities fail before mutation. The established v1
cancel surface remains compatible.

Doctor is constrained by `agent-run-doctor-v1` to fixed ownership/status fields,
one closed process observation, recovery booleans and bounded progress counts.
It excludes workload, prompt, result, command, output and operational host
detail. Reconciliation returns the existing execution and never creates,
resumes or replaces a turn. Atenea commit
`b5a5c814448324860dec587ada12873902c936d8` derives all three request envelopes
from the persisted AgentRun; coordinator cancellation now uses exact ownership.

Two final 22-test Atenea client/coordinator passes succeeded with zero failures,
errors or skips in 10.37 and 10.26 seconds. Two final 54-test
worker/runner/contract passes succeeded in 4.84 and 4.75 seconds. AX42's
installed service, hashes, Codex version, private listener, zero project
runners and zero restart count remained unchanged; production and preview
remained running with zero backend restarts. Nothing was installed or enabled.

Sanitized evidence is beneath
`/srv/atenea/artifacts/program/remote-codex-platform/add-codex-session-operations/runs/task-3.6-exact-recovery-operations`;
the SHA-256 of its `SHA256SUMS` is
`bece4b14c5f6def0aced6f1aa666682296b510c1c00b410b17155ab564359ae6`.
