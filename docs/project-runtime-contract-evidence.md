# Project runtime contract evidence

## Status

- Change: `establish-project-runtime-contract`
- Worker: `codex-worker-01`
- Started: 2026-07-24
- Atenea production routing: unchanged
- Real projects schedulable through Atenea: none

## Runtime manifest contract

Version 1 of the project runtime manifest is defined at
`runtime-contract/project-runtime-v1.schema.json` using JSON Schema draft
2020-12. It requires:

- canonical GitHub repository identity and default branch;
- explicitly versioned toolchains;
- either a Compose or legacy Tomcat runtime with declared internal ports;
- argument-array lifecycle, health, log, private-preview and browser commands;
- repository-relative artifact paths and named secret references;
- an explicit normal or heavy workload class.

The schema has no fields for privileged execution, host namespaces, devices,
arbitrary mounts or daemon sockets, and every object rejects undeclared
properties. Two safe examples cover Java 21 Compose and JDK 17 build/Java 8
Tomcat runtime. Eight negative fixtures cover absolute/traversing paths, literal
secret values, missing lifecycle, daemon socket mounts, host networking,
privileged execution and unsupported schema versions.

Local contract verification on 2026-07-25 used a draft-2020-12 validator:

- the schema passed its meta-schema check;
- both safe examples validated;
- all eight negative fixtures were rejected;
- every JSON document passed syntax parsing.

These fixtures define the contract only. They do not activate a project,
install worker prerequisites or prove the mediated runtime boundary.

## Session allocation and state contract

`runtime-contract/session-runtime-v1.md` fixes the v1 ownership and recovery
rules. The WorkSession UUID is the allocation key; the full UUID derives the
runtime identity, and the project name alone never identifies a runtime
resource. Canonical mirrors, worktrees, logs and run artifacts have separate
deterministic roots beneath `/srv/atenea`.

`session-allocation-v1.schema.json` defines the four slot identities, the two
heavy permits, deterministic worktree/runtime fields, allocated loopback ports
and reconciliation states. `dev-envelope-v1.schema.json` defines stable
operations, health, URL, blocked/error states and actionable non-secret error
codes.

Both schemas passed their draft-2020-12 meta-schema checks on 2026-07-25. The
normal allocation example and heavy-capacity blocked envelope passed schema and
format validation. This is contract evidence only; task 3 implements it and
task 5 exercises idempotency, collision and denial behaviour.

## Session mirror and worktree provisioning

Task 3.1 adds `session-workspace-v1.sh`, executed as the non-login
`atenea-worker` service identity. It creates one bare repository per project
under `/srv/atenea/repositories` and maps fetched canonical branches only to
`refs/remotes/origin/*`; session-owned branches remain under `refs/heads/*` and
cannot be overwritten by a canonical fetch.

Each WorkSession owns a worker-written record at
`/srv/atenea/workspaces/sessions/<uuid>/workspace-v1.json`. The record binds the
complete session UUID to the credential-free canonical remote, original base
commit, session branch, mirror, worktree and worker hostname. Provisioning is
serialized per project and refuses symlinks, foreign ownership, unsafe fetch
maps, mismatched records/remotes, branches checked out elsewhere, unowned
paths and orphan local branches. Recovery never resets, cleans, switches or
overwrites an existing worktree.

The companion synthetic suite passed twice in the programme worktree and twice
on `codex-worker-01` as `atenea-worker`. It proved:

- initial mirror, ownership record and worktree creation;
- byte-stable idempotent allocation while uncommitted work is present;
- canonical branch advancement without changing the session branch;
- reconciliation from a matching `provisioning` record;
- reattachment of a persisted session branch after its synthetic worktree was
  removed through Git;
- denial of cross-session branch reuse, identity changes, unowned paths,
  mismatched remotes, orphan branches and unsafe fetch mappings;
- serialization of two concurrent requests for the same session.

Only the two versioned scripts are staged at
`/srv/atenea/worker/workspace-v1`. Their worker and repository SHA-256 hashes
match. The suite permits only isolated `file:///tmp/...` remotes in test mode
and removed every temporary fixture. Before and after the worker run,
`/srv/atenea/repositories` remained empty and
`/srv/atenea/workspaces/sessions` remained absent. No real project, mirror,
session worktree, service, container or Atenea route was created.

## Session runtime allocation

Task 3.2 adds `session-runtime-allocation-v1.sh`, which consumes the ready
task 3.1 workspace ownership record and does not invoke Git, start a project
runtime or implement the later `dev` and runtime-manager tasks. The helper:

- derives the runtime identity and every Compose, network, volume, process-unit
  and Tomcat name from all 32 lowercase hexadecimal WorkSession UUID
  characters;
- serializes allocation through one worker-owned `flock`;
- persists a mode `0640` `runtime-allocation-v1.json` beside
  `workspace-v1.json` and returns it byte-identically on repetition;
- binds every declared internal port to a distinct allocation on
  `127.0.0.1`, skipping persisted allocations and live listeners;
- creates deterministic session roots for runtime data, logs and retained run
  artifacts without modifying mirrors or worktrees;
- isolates Maven, Node, OCI and browser caches per session and writes a policy
  marker declaring them rebuildable, non-authoritative and unavailable for
  secrets;
- fails closed on mismatched identity, slot, manifest, state, ownership, port
  registry, mode or symbolic-link state.

The helper deliberately accepts only `normal` workload manifests in task 3.2.
Heavy admission and pressure limits remain task 4.4. Lifecycle commands and
stable `dev --json` output remain tasks 3.3 and 3.4.

The synthetic suite passed twice in a local clone of
`program/remote-codex-worker-platform` and twice on `codex-worker-01` as
`atenea-worker`. The first worker invocation inherited the inaccessible
`/home/jose` current directory and emitted two harmless `find` restore
warnings while still passing; repeating from `/tmp` produced clean evidence.
The suite proved:

- byte-stable idempotent repetition;
- two sessions declaring internal ports `8080` and `5005` receive different
  loopback ports and full-UUID-derived names;
- a live synthetic loopback listener is skipped;
- identity, incompatible state, unsafe mode, duplicate persisted port and
  symbolic-link cache ownership conflicts are rejected;
- logs, artifacts, cache entries, synthetic mirrors and uncommitted synthetic
  worktree files survive success and denial paths;
- cache policy and authoritative state remain separated;
- two competing allocations serialize and receive different ports.

Only the two versioned task 3.2 scripts were added to the existing staging
root `/srv/atenea/worker/workspace-v1/ops/worker`. Their worker and repository
SHA-256 hashes match:

| File | SHA-256 |
|---|---|
| `session-runtime-allocation-v1.sh` | `b576bf96a1734a71b7c125d46e1227087c97c17a1b24da8d2187752f71fdb28c` |
| `test-session-runtime-allocation-v1.sh` | `208c73be039110fb80656897bb09acdbb86af25addb4741a97d7cc714d217909` |

Before and after the worker suite, `/srv/atenea/repositories` remained empty,
`/srv/atenea/workspaces/sessions` remained absent, the existing manual
workspaces were unchanged, slot 2 retained the same four digest-pinned images
and zero containers, and task 3.1's staged hashes were unchanged. Rootful
Docker, `docker.socket` and containerd remained inactive and masked. Beautips
remained `UP` on `127.0.0.1:18083`, and Tailscale continued to report
`No serve config`.

No real Atenea project or WorkSession was created, no real mirror or worktree
was introduced, and Atenea production routing remained unchanged.

## WorkSession-aware dev compatibility

Task 3.3 adds `dev-session-v1.sh` with human output for `list`, `status`,
`build`, `up`, `stop`, `restart`, `redeploy`, `logs`, `url` and `doctor`.
Session-specific operations resolve exactly one WorkSession from:

- an explicit canonical `--session` UUID;
- the current directory beneath an owned session worktree; or
- an unambiguous project selector.

The client validates every discovered `workspace-v1.json` and
`runtime-allocation-v1.json`, their owner and mode, the worktree boundary and
the exact manifest path persisted by allocation. To make that resolution
deterministic, task 3.2's allocation record now includes
`manifestRelativePath`; it remains repository-relative and must resolve beneath
the owned worktree.

`list`, allocation `status`, loopback `url` and diagnostic state are rendered
directly from validated records. Lifecycle and log operations never execute
manifest `argv` arrays or contact Docker directly. They delegate to the fixed
root-owned `/usr/libexec/atenea-runtime-client-v1` boundary. That client is
intentionally absent until task 4.2, so lifecycle operations fail closed with
the required next action in the real staging state. The existing
administrative Beautips `dev` pilot was not replaced or modified.

The human rendering remains independent from the structured renderer added by
task 3.4. The same human assertions continue to pass without parsing or
round-tripping JSON.

`test-dev-session-v1.sh` passed twice in the programme worktree and once on
`codex-worker-01` as `atenea-worker`. It used two synthetic WorkSessions for
the same project and a synthetic mediated adapter beneath `/tmp`. The suite
proved:

- human list and aggregate status output include both session identities;
- explicit UUID, current-worktree and project selection resolve safely;
- duplicate project selection is rejected as `SESSION_AMBIGUOUS`;
- missing selection and project/session mismatch fail closed;
- all lifecycle/log commands reach only the selected synthetic adapter;
- `logs --tail` remains bounded and reaches the adapter unchanged;
- `url` uses the persisted loopback port and declared preview path;
- status visibly reports the pending task 4.2 boundary when the runtime client
  is absent;
- `--json`, invalid option combinations, unsafe record modes and symbolic-link
  manifests are rejected;
- allocation records, uncommitted worktree state and mirror evidence remain
  byte-identical;
- deliberately failing manifest lifecycle commands are never executed
  directly.

The staged repository/worker SHA-256 hashes are:

| File | SHA-256 |
|---|---|
| `session-runtime-allocation-v1.sh` | `b576bf96a1734a71b7c125d46e1227087c97c17a1b24da8d2187752f71fdb28c` |
| `dev-session-v1.sh` | `fcbebfabde4659cdb945576491ac6b77f60f038601097772d6f0df98dda55fa5` |
| `test-dev-session-v1.sh` | `bc7ea5cdf0fb32e7e74647ad029f6abe2ff88a8e50b3de26b3569914aa425a2a` |

Before and after the worker suite, `/srv/atenea/repositories` remained empty,
`/srv/atenea/workspaces/sessions` remained absent, manual workspaces were
unchanged, slot 2 contained zero containers, and no temporary fixture
remained. Rootful Docker, `docker.socket` and containerd remained inactive and
masked. Beautips remained `UP` on loopback and Tailscale continued to report
`No serve config`.

No runtime manager, real manifest, real project, real WorkSession or Atenea
route was introduced.

## Stable dev JSON envelopes

Task 3.4 extends `dev-session-v1.sh` with a separate `--json` renderer while
preserving task 3.3's human output. Every successful or failed structured
invocation writes exactly one JSON document to stdout. Fixed actionable
diagnostics use stderr, and raw runtime-client stdout/stderr is never copied
into an envelope.

The renderer always includes `schemaVersion`, `operation`, `state` and a UTC
timestamp. A selected operation also includes the validated WorkSession and
project identities plus the schema-valid allocation. Health and the
loopback-only preview URL are included when applicable. Blocked and error
states contain a reserved code, short non-secret message, `retryable` flag and
one next action.

Lifecycle, log and selected runtime-state operations still delegate only to
the fixed mediated client boundary. For structured calls, `dev` accepts from
that boundary only one minimal result containing enumerated `state` and
`healthState` values. It does not parse human prose, execute manifest `argv`
arrays, contact Docker or expose raw adapter output. The real
`/usr/libexec/atenea-runtime-client-v1` remains absent until task 4.2, so real
selected status, doctor and lifecycle JSON calls fail closed with an
actionable `blocked` envelope rather than simulating a runtime.

Implementation exposed one contract inconsistency. The original envelope
schema required `sessionId` and `projectId` for every lifecycle operation,
including `SESSION_REQUIRED` and `SESSION_AMBIGUOUS`, where no unique identity
exists and one must not be invented. The schema now excepts only those two
selection failures from that identity requirement. Every other selected
operation still requires both identities. This was the only documentation/
observed-behaviour correction required by task 3.4.

The expanded synthetic suite ran beneath `/tmp`:

- locally, with every output validated by a draft-2020-12 JSON Schema
  validator and format checker;
- in the canonical Atenea programme worktree, with the same formal schema
  validation;
- on `codex-worker-01` as `atenea-worker`, using the dependency-free structural
  checks because the worker intentionally has no Python `jsonschema` package.

The suite covered all ten operations: `list`, `status`, `build`, `up`, `stop`,
`restart`, `redeploy`, `logs`, `url` and `doctor`. It proved:

- one valid JSON document on stdout and no successful-path prose or
  diagnostics mixed into it;
- stable stdout/stderr separation on failures;
- schema-valid allocation, health and URL fields for selected operations;
- `SESSION_REQUIRED`, `SESSION_AMBIGUOUS`, `SESSION_IDENTITY_CONFLICT`,
  `RUNTIME_OWNERSHIP_CONFLICT` and `MANIFEST_INVALID` blocked envelopes;
- actionable blocked state while the task 4.2 runtime client is pending;
- actionable error state when the synthetic mediated adapter fails;
- lifecycle and bounded log delegation only through the synthetic adapter;
- suppression of raw adapter output and an environment secret marker;
- byte-identical allocation records, synthetic mirrors, uncommitted worktree
  files, retained logs and retained artifacts;
- continued human-output assertions for every task 3.3 command;
- no direct execution of deliberately failing manifest lifecycle commands.

The versioned staging hashes after task 3.4 are:

| File | SHA-256 |
|---|---|
| `session-runtime-allocation-v1.sh` | `b576bf96a1734a71b7c125d46e1227087c97c17a1b24da8d2187752f71fdb28c` |
| `dev-session-v1.sh` | `2bed3984e335482a5224f8ebf8790f0b8ab20eb4e8c3bcf9b34888efb756cb7e` |
| `test-dev-session-v1.sh` | `572ff2c80d466c048f05dc9d304294ffab27d7e8fda8592ab6c96edbef85605c` |
| `dev-envelope-v1.schema.json` (repository) | `6289cf6c7f75d7383580bab0003d69d1f4e6c1dedf6360bce63d5f865535582a` |

Before and after the worker suite and after refreshing staging,
`/srv/atenea/repositories` contained zero mirrors,
`/srv/atenea/workspaces/sessions` remained absent, and no synthetic fixture
remained under `/tmp`. Slot 2 retained the same four digest-pinned images and
zero containers. Rootful Docker, `docker.socket` and containerd remained
inactive and masked. Beautips returned `UP` from
`127.0.0.1:18083/actuator/health`, and Tailscale continued to report
`No serve config`. The administrative Beautips `dev` at
`/home/jose/.local/bin/dev` was not replaced.

Atenea production remained on
`feature/actualizar-conversacion-en-web` with its pre-existing uncommitted web
changes untouched. Its running backend and local Codex App Server containers
contained no observed AX42 routing reference. No application was deployed, no
service was restarted, no real session state was created, and no commit or
push was performed.

## Session-scoped runtime manager boundary

Task 4.2 adds three versioned artifacts:

- `runtime-client-v1.sh`, the unprivileged `dev` delegation boundary;
- `runtime-manager-v1.sh`, the root-boundary policy and operation-plan
  validator;
- `test-runtime-manager-v1.sh`, the synthetic engine, denial and preservation
  suite.

The client grants no Docker or filesystem authority. Its production contract
accepts only the fixed root-owned manager path and the `atenea-worker` service
identity. The manager ignores production path and identity overrides, verifies
the sudo caller, and independently revalidates:

- the canonical WorkSession UUID and exact allocation path;
- worker ownership, regular-file type and restrictive modes for both records;
- matching workspace, project, branch, mirror and worktree identities;
- the complete allocation namespace, runtime names, loopback ports, logs,
  artifacts and cache roots;
- the exact repository-relative manifest beneath the owned worktree;
- the complete version 1 manifest shape, including commands as data only,
  toolchains, runtime, preview, browser checks, artifacts and named secrets.

Before any operation reaches the fixed runtime-engine interface, the engine
must return one resolved policy report for exactly the manifest-declared
services. The manager rejects non-empty mounts, host namespaces, added
capabilities, devices, daemon sockets, unsupported runtime fields and every
resource name not derived from the selected allocation.

Accepted operations receive a temporary mode `0600` plan containing only
validated identity, allocation and policy. Its restrictions require
no-new-privileges, a read-only root filesystem, all capabilities dropped, no
host network/PID/IPC namespaces, no devices, no daemon sockets and no mounts.
The plan is removed on success and failure. Manifest lifecycle `argv` values
are never copied into the plan or executed by the manager.

The synthetic suite passed locally, in the canonical Atenea programme worktree
and on `codex-worker-01` as `atenea-worker` from `/tmp`. It covered:

- structured `status`, `doctor`, `build`, `up`, `stop`, `restart`, `redeploy`
  and `logs` through the fixed synthetic engine;
- human logs and JSON `dev → client → manager → engine` integration;
- manifest-level rejection of privileged, mount, host-network, capability and
  device fields;
- resolved-policy rejection of mounts, namespaces, capabilities, devices,
  Docker sockets, unsupported fields, foreign resources and mismatched
  WorkSession identity;
- unsafe record modes and cross-session allocation-path denial;
- suppression of raw engine stdout/stderr containing a secret marker;
- preservation of allocation/workspace records, manifest, uncommitted
  worktree content, synthetic mirror, logs and artifacts across accepted and
  denied operations;
- removal of every temporary plan and fixture;
- absence of direct Docker calls and direct manifest lifecycle execution.

The staged task 4.2 hashes are:

| File | SHA-256 |
|---|---|
| `runtime-manager-v1.sh` | `c7c394907706fc1f5699c9ac4f4167b4337981f5b26db4e7109cf0344148ee78` |
| `runtime-client-v1.sh` | `0792bfae3f583474f51fef0d18169e4e7ffaad445efcc5f0de2470892b4089cb` |
| `test-runtime-manager-v1.sh` | `4e77070cbbbd5ca913297d7beb035d85af34bda22fcb64e64e0955fa8049a9cc` |

The source and tests are staged under
`/srv/atenea/worker/workspace-v1/ops/worker`. They are deliberately not
installed under `/usr/libexec`, no sudoers rule or service was created, and the
real runtime engine remains uninstalled. Consequently
`/usr/libexec/atenea-runtime-client-v1` is still absent and no lifecycle
authority has been activated. The real Compose/Tomcat engine integration and
dummy applications remain task 4.3.

Before and after the AX42 suite, `/srv/atenea/repositories` contained zero
mirrors and `/srv/atenea/workspaces/sessions` remained absent. Slot 2 retained
four digest-pinned images and zero containers. Rootful Docker,
`docker.socket` and containerd remained masked and inactive. Beautips remained
`UP` on `127.0.0.1:18083/actuator/health`, and Tailscale Serve continued to
report `No serve config`.

The only stale documentation found during 4.2 was the worker README still
describing task 3.4 as pending and `--json` as rejected. It now describes the
implemented structured renderer and the staged manager boundary. Atenea
production and its pre-existing dirty web worktree were not modified.

## Administrative Codex bridge

The bridge is intentionally separate from the future managed AgentRun executor.
It runs as the named administrator inside a private SSH/tmux session and is
restricted by Codex's `workspace-write` sandbox. It is suitable for beginning
manual work while the session runtime manager is developed; it is not isolation
evidence for Atenea dispatch.

Verified state:

| Check | Result |
|---|---|
| Official standalone Codex release | `0.145.0` |
| Authentication | ChatGPT device authorization; sanitized status passed |
| Model/config | `gpt-5.6-sol`, medium reasoning, workspace-write, network enabled |
| Doctor | 17 ok, 0 warnings, 0 failures; WebSocket HTTP 101 |
| Linux sandbox prerequisite | Ubuntu `bubblewrap 0.9.0`; specific AppArmor profile enforced |
| Real inference | Returned exactly `AX42_CODEX_OK` from the worker |
| Disconnect continuity | `codex-main` retained the same tmux pane PID across independent SSH connections |
| Operator helper | `codex-work`; laptop shortcut `axcodex` |

No laptop `auth.json`, histories, sessions, logs, caches, SSH keys, complete
Codex home or embedded project credentials were copied. The worker performed
its own device authorization.

## Allowlisted Codex context

Task 2.3 promoted the administrative context from seven explicitly allowlisted
sources at Git revision
`e89b7460ee642dd75cbecf3d2fad0073d43f1067`: one configuration file, one global
instruction file and five OpenSpec skills. `codex-context-lock-v1.txt` pins
every source hash. The effective manifest on the worker records context version
`remote-codex-admin-v1` and aggregate SHA-256:

`afa03516a02362c216876b930145b9ab03c3561e138f9da10be8b26509a21b35`

The reviewed bundle contains only its promotion automation, allowlist, lock and
the seven allowed sources. It is staged at
`/srv/atenea/worker/context-v1`. The first apply created the protected rollback
snapshot
`/var/backups/atenea-worker-runtime/20260725T121420Z-codex-context`; the second
apply reported the context already current. All seven installed hashes and
their declared modes matched the manifest.

The pre-existing configuration contained Codex-generated NUX and project-trust
state not present in the versioned global configuration. Promotion removed
those non-allowlisted entries and restored the pinned configuration. The
instruction file already matched its pinned hash; no custom skills existed
before promotion.

Hashes for `auth.json` and `history.jsonl`, the absence of `history.json`, and
the count of two session files were identical before and after promotion.
Authentication, histories, sessions, logs, caches, state databases, SSH keys
and project secrets are explicitly excluded. A sanitized post-promotion check
confirmed Codex `0.145.0`, ChatGPT login, loaded configuration, healthy state
databases and a successful WebSocket connection.

Operationally, the standalone Codex binary is available through the worker
login profile, not a bare non-login SSH PATH. Validation therefore used a login
shell; future managed execution must set its declared PATH explicitly rather
than inheriting an interactive profile.

## Version-pinned worker toolchains

`ops/worker/toolchain-lock-v1.sh` and
`install-toolchain-prerequisites.sh` define the Ubuntu 24.04 amd64 host
prerequisites, the existing rootless Docker package versions and immutable OCI
manifest-list digests for:

- Node 22.16.0;
- Maven 3.9.9 with Java 21;
- Tomcat 8.5.100 with Java 8;
- Playwright 1.60.0's browser image with Chromium 148.0.7778.96.

The host verification passed on 2026-07-25 without changing an installed
package: all declared package versions matched, and rootful Docker, its socket
and containerd remained inactive.

The four pinned images were installed in the previously empty rootless
`slot2`. Version probes ran without a network namespace and proved Node,
Maven, Java 21, Java 8 and the pinned Chromium binary. Repeating the complete
image installation reported every digest up to date and all probes passed,
providing idempotency evidence.

The first browser probe incorrectly used `npx`, which attempted package
resolution despite the image containing browsers rather than the npm package.
The probe was interrupted, left no process or container behind, and was
replaced by a direct finite-timeout Chromium version check. The corrected
verification passed.

No application was deployed, no real project was assigned to slot 2, no
service was restarted and no host-global Java, Node or Playwright installation
was introduced.

## Beautips administrative source

GitHub and the clean laptop checkout both resolved `main` to
`a6d2f2815153bf54a977f54bb69be3931075e175`. The Atenea checkout resolved to a
different local commit and was not used as the source.

Because the private GitHub remote is not yet authenticated on the worker, a Git
bundle containing committed refs only was verified and used to create:

`/srv/atenea/workspaces/manual/beautips`

The checkout is clean, its `origin` remains
`https://github.com/jlnieto/beautips.git`, and the `codex-beautips` tmux session
is ready. The AX42 performed an independent GitHub device authorization for
account `jlnieto`; a sanitized `fetch` proved `HEAD == origin/main`. No laptop
GitHub token or credential file was copied.

## Beautips rootless runtime

The official Docker repository key was verified as
`9DC858229FC7DD38854AE2D88D81803C0EBFCD88`. The installed runtime is pinned:

| Component | Version |
|---|---|
| Docker Engine/CLI/rootless extras | `29.6.2` |
| containerd | `2.2.6` |
| Buildx | `0.35.0` |
| Compose | `5.3.1` |

The rootful Docker, Docker socket and containerd services are masked. User
`atenea-slot1` owns a rootless daemon and has no sudo or `atenea` group access.
Its systemd user slice is limited to 400% CPU, 10 GiB `MemoryHigh`, 12 GiB
`MemoryMax` and 4096 tasks. The administrative bridge reaches only this
rootless slot through `/run/atenea-runtime/slot1/docker.sock`; it does not
receive `/var/run/docker.sock`.

The same empty isolated base was prepared for slots 2–4. All four rootless
daemons and restricted proxy sockets are active; only slot 1 currently has a
workspace/runtime assignment. No repository, secret or application was placed
in the unused slots.

Local development credentials were generated on the AX42 and stored at
`/etc/atenea-worker/manual-sessions/beautips.env` as `root:atenea-slot1` mode
`0640`. WhatsApp integration secrets remain unset. No credential value is
recorded here.

`dev up beautips` built the declared Node 22, Maven 3.9.9 and Java 21 stages,
then started PostgreSQL 16, Redis 7 and the application. Acceptance evidence:

- application health returned `UP`;
- PostgreSQL and Redis reported healthy;
- Codex itself successfully executed `dev status beautips` from its
  `workspace-write` sandbox;
- all published ports bind to worker loopback only;
- Playwright loaded `/admin/login` through a private SSH tunnel at `1440x900`
  and `390x844`;
- both DOM checks had visible content and no horizontal overflow;
- inspected screenshots showed the complete, readable Superadmin login without
  clipping or overlap.

The root `/` returning 403 is expected for this application; `/admin/login` is
the declared visual smoke route.

## Tailnet-only browser preview

Tailscale Serve was explicitly enabled by the tailnet owner and configured in
background mode:

`https://codex-worker-01.tailf11cbc.ts.net/` proxies
`http://127.0.0.1:18083`.

Evidence:

- Serve reports `tailnet only`; Funnel/public sharing was not enabled;
- MagicDNS resolves the worker FQDN to `100.81.98.93`;
- TLS 1.3 completed with a valid Let's Encrypt certificate for the exact worker
  FQDN;
- HTTPS `/actuator/health` returned `UP`;
- HTTPS `/admin/login` returned the rendered Superadmin login;
- Playwright passed at `1440x900` and `390x844` through the HTTPS Serve URL with
  visible content and no horizontal overflow;
- the two registered Android devices were online during validation.

The background Serve configuration survives tailscaled and host restarts. It
can be disabled without stopping Beautips using:

`sudo tailscale serve --https=443 off`

### 2026-07-25 state refresh

The earlier acceptance proves that the tailnet-only route worked at that time;
it does not describe the current route state. A read-only refresh on
2026-07-25 returned `No serve config`. Beautips itself remained healthy at its
loopback health endpoint, all four rootless slot daemons were active, and
Atenea contained no AX42 routing reference.

Consequently, the private Serve preview is currently **inactive** and MUST be
re-established and revalidated before it is presented as available. This drift
does not invalidate the browser evidence already collected, and it does not
make Beautips schedulable through Atenea.

This pilot assigns the worker's single default HTTPS root to Beautips. Multiple
simultaneous project previews require the generic session preview registry and
path/service allocation defined for the later preview phase.

Beautips remains an administrative pilot rather than an Atenea-schedulable
project until the generic manifest/broker, data fixtures, full test suite,
cleanup and remote AgentRun routing gates pass.

## Immediate usage

From the configured laptop:

```bash
axcodex beautips
```

Detach without terminating Codex with `Ctrl-b`, then `d`. The same command
reattaches to the existing conversation.

Start the private browser tunnel on the laptop:

```bash
axpreview beautips
```

The current URL is `http://127.0.0.1:18083/admin/login`. Stop the tunnel with
`axpreview beautips stop`; stopping the tunnel does not stop Beautips or Codex.

From another authorized Tailscale device with an SSH client:

```bash
ssh -t jose@codex-worker-01 \
  /home/jose/.local/bin/codex-work beautips \
  /srv/atenea/workspaces/manual/beautips
```

The mobile/Atenea conversational interface remains part of the managed remote
routing phase; SSH is the initial continuity bridge.
