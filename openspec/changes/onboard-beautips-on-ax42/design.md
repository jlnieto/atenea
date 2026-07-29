## Context

The Atenea onboarding proved the generic real-project protocol and then
returned it to a disabled/released boundary. Beautips is a different
composition: Java 21 plus a Node 22 frontend build, PostgreSQL 16, Redis 7,
persistent assets/imports and optional WhatsApp/bootstrap secrets.

AX42 already runs Beautips administratively in rootless slot 1 from
`/srv/atenea/workspaces/manual/beautips`. This is useful non-impact control
evidence, not a WorkSession that may be adopted. Its database and files have no
independent restore-tested external backup.

## Goals / Non-Goals

**Goals:**

- onboard only Beautips from one canonical GitHub identity;
- prove a separate session-owned worktree and runtime without touching slot 1;
- execute real Codex turns with exact ownership and normal Git delivery;
- verify build, tests, synthetic data, health and desktop/mobile UI;
- leave production, WhatsApp, the administrative pilot and other projects
  unchanged.

**Non-Goals:**

- migrating or backing up the existing administrative Beautips data;
- importing legacy or production-derived salon data;
- enabling WhatsApp, public preview or production deployment;
- reusing the manual `.env`, database, Redis, assets or imports volumes;
- enabling a wildcard or any other project.

## Decisions

### GitHub main is canonical

GitHub `jlnieto/beautips` branch `main` at
`5044a3b07b3db82895e9c8ff47bc4bc9b0e97130`, tree
`132719df7a400f7ba9e724499425e0a64f5b8991`, is canonical. The local
`a6d2f28` and Atenea `bd15a16` copies were clean ancestors and were advanced
only by `pull --ff-only`; AX42 already matched GitHub. All three are now clean
and synchronized.

The entry-gate source manifest was `ops/atenea-runtime.yml`, SHA-256
`09717d5633fe3909f97bdcd0cb7b30817f02b202a9b8d57933323efa041a44ec`.
It was pinned as an input, not accepted unchanged.

Task 2.1 replaces it at commit
`e4256d7fe1610e191099bd12ce993591a5cd4b7a`, tree
`8e52657add269c84700105aa7028728d4ddf2810`, with the runtime-contract v1
manifest `ops/atenea-runtime.json`, SHA-256
`365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82`,
and the separate managed Compose definition `ops/docker-compose.atenea.yml`,
SHA-256
`840e64166e8e1ddaefb74d11763fe150e6539074bb02c3173e2175a446555941`.
The manual Compose definition remains unchanged.

The managed definition has no fixed container name, project name, host port,
network or volume identity and does not reference a tracked or manual env
file. The worker allocation supplies the exact WorkSession paths, Compose
project, loopback ports, network and volume prefix; complete ownership labels
are mandatory. Its stop operation always requests volumes, orphans and local
images be removed. This declares the cleanup boundary without enabling or
executing it before the reviewed mediator and exact registry exist.

Task 2.2 adds the source registry
`ops/worker/project-codex-allowlist-v1.json`, SHA-256
`696a00eae3d35f9e54d3eebc55441252705c982dc19adb0aa9aa7aecd59a61b0`,
under the closed Draft 2020-12 schema
`runtime-contract/project-codex-allowlist-v1.schema.json`, SHA-256
`1fc4d61a46e10ea9a6b7201573daef5b50267f13d252e20c6dab062e6fee10e2`.
It contains exactly Beautips and pins repository, branch, commit, tree,
manifest and managed Compose identities to worker `ax42-01`, normal workload
and slots 2–4. Selection and execution are both false and the workspace map is
empty.

This registry is reviewed source only at this gate. It is not installed over
the disabled Atenea registry, consumed by routing or capable of lifecycle
execution. Tasks 2.3–2.6 must add the mediated operations, exact sandbox,
secret boundary and install/rollback tooling before any installed Beautips
record can exist.

Task 2.3 advances the accepted source to
`e9e0b3c319c518363d4135f5378ebbddced96dfb`, tree
`533d32f97ae362997ad003170a826da674c31c1d`, solely to make both smoke
scripts reject repository `.env` and missing named inputs in managed mode.
Their manual default mode remains compatible.

The source-only mediator
`ops/worker/beautips-operation-mediator-v1.py` accepts only a canonical
WorkSession UUID and one of ten symbolic operations. It derives allocation,
slot, worktree, Git, manifest, Compose, ports, runtime names, cache paths,
timeouts, fixed images and named secret references without accepting caller
commands, paths, endpoints or environment. Every generated plan validates
against `runtime-contract/beautips-operation-plan-v1.schema.json` and remains
`executionEnabled=false` until the later installation gate.

The exact operation set is Node build, Maven test, Compose build, runtime
start/health/logs/stop/cleanup and the two reviewed smoke scripts. Unknown
operations, slot 1, noncanonical sessions, duplicate or foreign ports, foreign
project/path/Git identity and modified manifest or Compose fail closed before
any operation can execute.

Task 2.4 adds
`ops/worker/beautips-project-codex-runner-v1.py`, SHA-256
`55e8f585e19f6a19d3c51aaf7532b1cf0f74f6b087ae0d1ef67faaea3029b73b`.
It hash-pins and reuses the accepted per-run runner at SHA-256
`de84b0c96908677e334184b9290691a2116b963dd37483022f97a0fd57ed44d1`;
only the immutable project, repository, branch, commit, manifest and Git
common-directory identity changes to Beautips.

The inherited boundary retains its collected transient systemd cgroup,
Bubblewrap workspace-write namespace, exact worktree and mirror mounts,
private-result directory, finite timeout, cancellation, thread continuity and
network denials for loopback, RFC1918, Tailscale and link-local destinations.
It does not mount the manual Beautips workspace, a Docker socket or a caller
path. The existing Codex-owned authentication/session boundary is referenced
only inside the accepted sandbox and is never read by onboarding
orchestration or evidence. The adapter remains uninstalled and no Codex
process runs at this gate.

Task 2.5 adds
`ops/worker/beautips-secret-boundary-v1.py`, SHA-256
`acbbb58f5ead82f47288fa499009c46797655bd277071d57e21b5c6ccfd504f6`.
It derives one exact secret directory from the validated WorkSession
allocation and creates only four separate synthetic names: PostgreSQL
password, smoke administrator email/password and smoke seal code.

The directory is mode `0700`; files and value-free metadata are mode `0600`
under the worker service ownership. Repeated preparation retains existing
valid files byte-for-byte. The result reports names and ownership state only,
with `valuesExposed=false`. The tool accepts no value, env-file or path
argument and never reads ambient manual or WhatsApp variables.

Any `.env`, WhatsApp, token, cookie, unknown, symlink, partial, foreign-owner
or unsafe-mode entry rejects the complete boundary before use. Synthetic test
values exist only beneath automatically removed `/tmp` roots. The tool remains
uninstalled and no real WorkSession secret is generated at this gate.

Task 2.6 installs the reviewed mediator, exact Beautips runner, secret
boundary, operation registry and immutable source allowlist under
`/usr/local/libexec/atenea`. It creates a separate default-disabled runtime
configuration with zero workspaces and one exact sudoers command. The
accepted shared runner remains separately hash-pinned and is never removed.

The lifecycle tool supports plan, apply, verify, selection enable, execution
enable, disable and rollback. Apply is byte-idempotent, verification checks
hashes, ownership, modes and sudoers syntax, and execution enable fails closed
unless selection is already enabled with exactly one persisted workspace.
Rollback requires disabled empty ownership and removes only exact installed
Beautips artifacts. The tool contains no listener, firewall, Tailscale or
service mutation and remains independently usable after deployment staging is
removed. Its installed final state is disabled with zero workspaces.

Task 2.7 adds one aggregate worker-contract regression over the pinned
Beautips commit. It validates the manifest schema, complete Compose ownership,
allocation-derived ports/names, exact cleanup command and absence of manual
paths or env files. Repeated mediation produces an identical exact cleanup
plan.

The aggregate also runs the existing ownership, sandbox, secret and lifecycle
tests plus the inherited project dispatch idempotence, exact cancellation and
restart reconciliation semantics. Test source paths are parameterized so the
same suite runs locally and from an exact temporary AX42 checkout. No
WorkSession, runtime or routing is enabled by these tests.

Task 3.1 adds a separate default-false Beautips selection gate to Atenea. A new
session selects `project-codex-v1` only when the global remote-worker gate and
the Beautips gate are both enabled, its project name and canonical repository
path match exactly, both project and session branches are `main`, and AX42
advertises the required workload capability. The existing generic
real-project gate cannot select Beautips.

Partial or foreign name, path, project branch and session branch identities
remain local without contacting the worker. Missing capability also fails
closed. Repository, commit, manifest, workspace and workload persistence are
not inferred at selection time; task 3.2 must pin them durably before
dispatch. No selector configuration is deployed or enabled at this gate.

Task 3.2 extends the existing additive AgentRun identity columns without a new
migration. A queued Beautips run is accepted only from an exact selected
session whose worker is `ax42-01`, remote UUID exists, workload is
`project-codex-v1` and workspace identity is derived exactly from that worker
and UUID. Before dispatch registration, the persisted run receives that
affinity plus project `beautips`, canonical repository URL/path, branch
`main`, accepted commit and manifest SHA-256.

A foreign workspace identity rejects before repository save. The existing
Atenea project and synthetic identities retain their current persistence
behavior. Worker payload, thread/turn mapping and terminal handling are not
changed here; task 3.3 consumes the exact persisted Beautips identity.

Task 3.3 lets the existing project payload accept the exact persisted
Beautips fingerprint from 3.2. Payload shape and protocol remain unchanged:
repository, branch, commit and manifest come only from the persisted AgentRun,
the saved dispatch UUID remains the idempotency key, and the WorkSession's
external thread ID is forwarded for continuation. Caller command, path,
endpoint and environment remain absent.

The project-neutral coordinator maps a Beautips terminal success through its
existing path: returned thread to WorkSession, returned turn ID to AgentRun
and one CODEX result turn. Re-observing the terminal run returns without
redispatch or duplicate turn. Existing Atenea mapping and synthetic payload
tests remain unchanged and passing.

Task 3.4 keeps the project-neutral coordinator unchanged and adds Beautips
continuity coverage. Cancellation targets the persisted remote execution
without replacement dispatch. Startup reconciliation polls a persisted
non-terminal execution without redispatch. A bounded partition fails the same
run with explicit operator review and no reassignment. Re-observation after a
terminal result creates neither another dispatch nor another result turn.

Equivalent Atenea continuity cases remain in the same suite. This source gate
does not restart production; installed restart continuity is reserved for the
later canonical WorkSession acceptance.

Task 3.5 closes control-plane allowlisting with two final focused executions
of `37/37` and two full Atenea executions of `411/411`. Each full run uses a
fresh internal network, PostgreSQL 16 database and isolated workspace volume;
all 49 migrations apply. Exact selection, payload, persistence, delivery,
denial, Atenea project and synthetic compatibility remain in the accepted
sets.

An initial full harness omitted the canonical workspace root and failed
correctly because integration repositories fell outside configured ownership.
The accepted harness separates source from `/workspace/repos`, sets the
canonical root explicitly and removes every labelled fixture after exact
identity verification. No production, task4 or AX42 resource participates.

### The administrative pilot is foreign retained state

The three slot 1 containers, their network, four volumes, loopback listener,
manual workspace and root-owned secret boundary are immutable non-impact
controls. Managed allocation MUST use a different free slot and MUST reject
any attempt to register, relabel, attach, stop, snapshot or clean those
identities.

### Acceptance data is synthetic and disposable

The managed runtime starts with empty PostgreSQL migrations, empty Redis and a
small versioned fixture containing only invented tenants, users, loyalty
events and files. It uses separate session-labelled database, Redis,
assets/imports volumes. No existing dump, backup, legacy import, production row
or administrative volume is mounted or copied.

Task 5.2 accepts 41 Flyway migrations and only their 13 source-versioned static
catalog rows as empty-migrated state. All tenant, user, customer, loyalty,
import, credential, channel, outbox and event tables remain empty and Redis
reports zero keys before task 5.3 fixtures.

The rootless runtime uses three exact containers, one `internal=true` network,
four labelled volumes and one local application image in assigned slot 4.
RootlessKit does not automatically publish Docker `PortBindings` from an
internal network, so three allocation-derived API records forward only
`127.0.0.1` to the exact container IP/ports while preserving no runtime
egress. Their immutable IDs and complete specs are retained for reconciliation
and exact cleanup.

The exact runtime parent is setgid. Secret creation therefore applies mode
`0700` after ownership so inheritance cannot leave the closed boundary at
`2700`; the regression suite covers this real parent mode.

Task 5.3 loads fixture bundle `beautips-acceptance-v1` from programme commit
`4f87be85b97dabcf5fcd975537b611e19815a7ee`. The canonical bootstrap APIs
create one invented tenant and owner using only the named synthetic secret
boundary. Idempotent SQL adds one invented customer, consent, loyalty module,
program, account, transaction, event, service catalog and completed import
record. The exact SVG and CSV bytes are projected only into the session-owned
asset/import volumes.

Two complete repeated loads retain byte-identical sanitized database
projections, file hashes and zero-key Redis state. Each declared synthetic
table contains exactly one row while tenant WhatsApp credentials and outbox
rows remain zero. A discovered boundary mismatch was corrected: the synthetic
seal generator now emits the four digits required by the canonical Beautips
domain, with focused boundary and lifecycle tests. Only the existing synthetic
seal for this WorkSession was shortened; every other named secret and the
enabled exact workspace configuration remain unchanged.

The running containers stopped accepting `docker exec` commands during this
gate without reporting a daemon error. Finite, fully session-labelled
PostgreSQL client containers therefore executed only on the exact internal
network, recorded their immutable IDs and removed themselves. Their
task-introduced image tag and private I/O directory were exact-cleaned after
acceptance. The failed preflight and schema/report attempts remain sealed and
show their fail-closed or transaction-rollback boundaries.

Task 5.4 proves the three exact managed containers mount only their four
session-owned volumes and attach only to the complete-ownership internal
network. That network has exactly the three managed endpoints, no default
route and no host-gateway DNS. It exposes no Docker socket and cannot reach
the administrative or daemon loopback ports from inside the network.

The synthetic tenant retains zero legacy batches/mappings, non-synthetic
imports, WhatsApp channels, credentials, outbox/messages, onboarding sessions
and webhook events. The pinned Compose keeps every external messaging input
empty and its scheduler false. Two fully labelled finite probes used the
already-running PostgreSQL image and removed themselves by immutable identity.
All four rootless slot inventories, rootful Docker state, administrative
Beautips, canonical Git, listeners, production, preview and isolated control
plane remain byte-identical before and after.

Local backup folders are on the laptop root filesystem and AX42 has no
configured restic, borg or rclone target. They do not satisfy independent
external backup. Therefore every acceptance artifact remains
non-authoritative and cleanup is mandatory.

### Secrets and external messaging fail closed

Only named secret requirements may enter manifests. No current manual env
file, value, token, cookie, credential or Codex authentication material enters
requests or evidence. Acceptance uses generated synthetic bootstrap values in
an exact session secret boundary. WhatsApp embedded signup, webhook,
scheduler, outbox delivery and external Graph API egress remain disabled.

### Tailnet preview; no localhost contract

The accepted UI paths use relative navigation and the WhatsApp/OAuth paths are
disabled. Beautips therefore declares no required localhost compatibility for
this acceptance. The managed preview is tailnet-only on an allocation-derived
port. Any absolute-localhost, cookie, redirect or browser failure blocks this
decision and requires an explicit manifest revision rather than an implicit
tunnel.

Task 5.5 activates one exact persisted synthetic preview through the installed
`session-preview/v1` coordinator. Its only ingress listener binds AX42
Tailscale IPv4 `100.81.98.93` inside the approved `19000–19031` range and
forwards to the allocation-owned loopback web port. The private admin login
returns HTTP 200 from AX42 and the operator laptop.

The manifest, persisted record and public response all retain
`localhostCompatible=false`. The response contains no tunnel metadata or
runtime port, localhost connection is denied, no localhost/wildcard ingress
listener exists and the page/headers contain zero absolute localhost
references. The single READY preview is retained only for tasks 5.6 and 5.7.

Task 5.6 passes both reviewed mediated smoke operations. The functional smoke
creates one invented tenant, records its immutable database id and slug, and
exact-deletes only that identity after the customer-web smoke. Every
tenant-scoped residual is zero and the deterministic `aurora-acceptance`
inventory is restored.

Finite five-minute preview leases expired during authorised pauses. Each
terminal synthetic record was ownership-checked and deleted by exact UUID
before one replacement was activated; no two READY previews coexisted and no
runtime, production route or foreign preview changed.

The customer smoke exposed an invalid raw-SQL fixture enum:
`SYNTHETIC_ACCEPTANCE` is not a canonical
`TenantModuleActivationSource`. Programme commit
`a30117789d1bddfde804dbaa00a71f2975178d60` changes only that fixture value to
valid source `ADMIN` and updates its pinned SQL hash. The exact runtime
projection was replaced only after matching both old hashes. Two repeated
idempotent reloads are byte-identical and the public synthetic salon changes
from HTTP 500 to HTTP 200.

Playwright on the operator laptop authenticates through the tailnet-only
preview using named synthetic values streamed only through stdin. It asserts
the login, exact filtered salon, primary action and customer/program/access
KPIs before sanitizing the credential identifier in screenshots. Inspected
desktop `1440x900` and mobile `390x844` images have clear state/action
hierarchy, no clipping or overlap and no horizontal overflow. No task-owned
browser, database helper, helper image, private I/O directory or transient
tenant remains; the operator's graphical Chrome is foreign and untouched.

Task 5.7 registers the two sanitized PNGs and DOM JSON report as three
deterministic immutable attachment UUIDs beneath the exact WorkSession. All
use explicit synthetic identity and `EVIDENCE` retention. Initial PUTs return
HTTP 201 and exact repeats return HTTP 200 with byte-identical metadata,
proving idempotence without duplicate stored bytes.

The expired preview is complete-ownership checked at revision 4 and deleted by
exact UUID. Its record and ingress listener are absent while the runtime
remains running. Two full metadata/content retrieval passes after teardown
return HTTP 200 and reproduce every registered byte count and SHA-256. The
temporary retrieval projection is removed. One pre-teardown observation used
the historical `sessions/` storage name and stopped after successful
registration; canonical continuation used `work-sessions/`, mutated neither
registered bytes nor preview during the failed check, and retains the attempt.

### Build, verification and delivery are fixed

The build uses the committed Node 22/Maven 3.9.9/Java 21 Dockerfile and the
canonical Maven test entrypoint. Health uses `/actuator/health`; functional
checks use reviewed bounded smoke scripts and Playwright verifies exact
desktop/mobile states. Delivery uses one exact WorkSession draft pull request,
reviewed non-force merge synchronization and canonical close.

Task 6.1 commits only `docs/ax42-onboarding-acceptance.md` as
`03f77b0389f5303153c47bc3f890b1e0e9e92eb8` on the exact WorkSession branch.
The branch is transferred with complete history and created remotely by a
normal non-force push. Atenea verifies the equal remote head and creates
`jlnieto/beautips#1` through its authenticated publish endpoint. GitHub reports
one open draft with exact `main` base, exact WorkSession head, one commit and
one changed file. A missing disposable Git credential fails closed before
GitHub mutation; the accepted retry uses only finite-lived credential pipes
and removes the helper, control, image, bundle and transfer ref by exact
identity.

Task 6.2 reviews the exact one-file pull request after its CI succeeds, marks
it ready and creates normal merge commit
`f836940d71ed761a4d12e560c3790eeba9778f85`. Its parents are the immutable
pre-merge `main` and WorkSession heads; the remote WorkSession branch remains
unchanged. Two real Atenea synchronization calls return identical persisted
`MERGED` delivery state. An exact isolated-database cross-session sentinel
retains the same selected-row MD5 across both calls and is then deleted only
after its immutable ID, project, workspace identity, final SHA and MD5 match.
The focused delivery identity/idempotence regression passes `9/9`.

Task 6.3 closes the exact database WorkSession through Atenea's authenticated
canonical endpoint. The close revalidates the merged pull-request identity,
fetches origin, checks out `main`, fast-forwards only to exact merge
`f836940d71ed761a4d12e560c3790eeba9778f85`, and deletes only the merged
session branch from the disposable control clone and GitHub. AX42 retains its
clean local WorkSession branch and commit. A `noexec` helper placement first
produces retryable `CLOSING/fetch_failed` without branch mutation; moving only
the ephemeral executable to container `/tmp` lets the retry finish
`CLOSED/CLOSED`. Runtime, volumes, logs, attachments and accepted evidence
remain retained.

Task 5.1 projects assigned-slot access without changing ownership: shared
ancestors receive traverse only and the exact WorkSession worktree/cache
receive the slot ACL. Dependencies warm only into that cache; acceptance then
runs the exact digest-pinned Node and Maven plans with network none.

Real preflight closed three plan defects before acceptance. Root-owned Git
inspection now scopes `safe.directory` to the already validated exact
worktree, npm explicitly selects its mounted session cache, and Maven skips
the separately completed frontend goal using the same property as the
canonical Dockerfile. Focused mediator/install tests cover all three.

### Rollback is exact and observation is finite

Rollback first disables new Beautips selection, reconciles/cancels only the
persisted WorkSession execution and removes only its fully owned runtime,
preview, PostgreSQL, Redis, assets/imports and browser resources. Git and
sanitized evidence remain. The slot 1 administrative pilot and every foreign,
partial or ambiguous resource remain untouched.

Close observation lasts 15 minutes with normalized samples at minute 0, 5, 10
and 15. Any drift in the administrative pilot, production, other slots,
persisted ownership or health blocks archive.

## Migration Plan

1. Seal the canonical identity, administrative control and decisions.
2. Make the Beautips manifest and project registry session-safe while disabled.
3. Add control-plane allowlisting and focused denial tests.
4. Allocate one disposable WorkSession outside slot 1 and enable only it.
5. Execute real turns, build/tests, synthetic runtime, preview and browser
   acceptance.
6. Publish, synchronize, close, restart, disable, rollback and exact-clean.
7. Observe, retain evidence and archive before another project begins.

## Open Questions

No question may be answered implicitly. Independent external backup remains a
gate for authoritative non-Git Beautips state, and production promotion remains
outside this change.
