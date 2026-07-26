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

## Synthetic runtime engine and fixtures

Task 4.3 adds one fixed engine and two completely synthetic fixtures:

- `runtime-engine-v1.sh`;
- `test-runtime-engine-v1.sh`;
- `runtime-contract/fixtures/valid/dummy-compose`;
- `runtime-contract/fixtures/valid/dummy-tomcat`.

Both manifests are schema-valid, normal workloads with no secrets or external
services. Both declare HTTP port `8080`. Their lifecycle command arrays contain
the deliberate marker `MANIFEST_ARGV_MUST_NOT_RUN`; the marker never reached
the Docker command log or an executed process.

Engine v1 accepts only the two fixture project/runtime pairs. It revalidates the
manager's mode `0600` plan, the allocation identity, slot 2, loopback port and
default-deny restrictions. It then verifies exact SHA-256 hashes for every
executable fixture input and copies only those files into a private,
engine-owned snapshot. Dockerfiles and the restrictive Compose document are
generated from the validated plan; manifest paths, flags, names and `argv`
values do not become daemon commands.

Every image, container and network name derives from the complete WorkSession
runtime ID and carries engine, session and runtime labels. Existing resources
are reused, stopped, removed or rebuilt only after all ownership labels match.
The selected daemon is derived from the persisted slot and fixed to the
rootless proxy; neither `/var/run/docker.sock` nor another slot is accepted.

The generated containers enforce:

- loopback-only publication to distinct allocated ports;
- read-only root filesystems;
- `no-new-privileges`;
- all capabilities dropped and none added;
- private network/PID/IPC namespaces;
- no privileged mode, devices or bind mounts;
- no Docker or other daemon socket;
- bounded PIDs and only small `tmpfs` runtime paths where required.

Local tests ran first and exclusively beneath `/tmp`. The schema validator
accepted both fixtures. The task 3.2, 3.3/3.4 and 4.2 regression suites plus
the new engine suite passed in the local inspection tree and in the canonical
Atenea programme worktree. The same four suites then passed on AX42 as
`atenea-worker` from `/tmp` using the dependency-free fake-Docker boundary.

The real AX42 exercise used two synthetic WorkSession UUIDs, fixed loopback
ports `27301` and `27302`, and only rootless slot 2. A temporary broker beneath
`/tmp` verified the Unix peer UID was `atenea-worker`, accepted only the exact
synthetic session paths and manager operations, and invoked the root-owned
temporary engine. It did not install a service, sudoers rule or global
executable, and it was removed after the run.

The complete real chain
`dev -> runtime client -> runtime manager -> engine -> rootless slot 2`
passed twice from `/tmp`. For both fixtures it covered `build`, `up`, `status`,
health, `logs`, `url`, `stop`, repeated `stop`, `restart` and `redeploy` in
human and JSON modes. Both applications were healthy simultaneously while
listening internally on `8080`; their host bindings remained independent at
`127.0.0.1:27301` and `127.0.0.1:27302`.

The Compose response identified `dummy-compose` with state `UP`. The Tomcat
response identified `dummy-tomcat`, state `UP` and runtime
`java8-tomcat8`. Direct pinned-image probes and retained build metadata proved:

| Toolchain boundary | Observed result |
|---|---|
| Build compiler | `javac 17.0.19` |
| Runtime VM | Temurin OpenJDK `1.8.0_402` |
| Servlet runtime | Tomcat `8.5.100` |

Direct daemon inspection confirmed both containers were unprivileged,
read-only, `no-new-privileges`, `cap_drop=ALL`, with no added capabilities,
devices, binds or host namespaces. Port `8080/tcp` mapped only to each declared
loopback port. Cross-session allocation use and a modified Compose fixture
requesting a host mount were rejected. The task 4.2 denial regression also
reconfirmed rejection of mounts, host namespaces, capabilities, devices,
Docker sockets, unsupported fields and foreign names.

Repeated lifecycle calls preserved byte-identical workspace/allocation records,
fixture source, retained runtime logs and the session-derived toolchain
artifact. No environment marker or raw engine output entered human or JSON
stdout. `stop` did not remove records, worktrees, logs or artifacts.

Before and after the real run:

- `/srv/atenea/repositories` contained zero mirrors;
- `/srv/atenea/workspaces/sessions` remained absent;
- slot 2 contained zero containers and exactly the same four pinned image
  digests;
- rootful Docker, `docker.socket` and containerd remained inactive and masked;
- Beautips returned `UP` on `127.0.0.1:18083/actuator/health`;
- Tailscale reported `No serve config`;
- the administrative Beautips `dev` hash remained
  `db58c7ac7e2dc71fab0d7ef6a04591236ec34ff223b0ae52951e3538aa6234d5`;
- Atenea production retained branch `feature/actualizar-conversacion-en-web`,
  its pre-existing web changes and no observed AX42 routing reference.

All two-session containers, networks, images, broker files and fixture roots
were removed only after their synthetic ownership was inspected. The
digest-pinned JDK 17 reference introduced for the direct version probe was also
removed; slot 2 returned to its exact four-image baseline. No real mirror,
worktree, WorkSession, project deployment, authentication material, history,
secret, service restart or production change occurred.

The repository and staging hashes after task 4.3 match:

| File | SHA-256 |
|---|---|
| `runtime-engine-v1.sh` | `3144aff72b6e53a3022aa8229ac19fc81f1b470a7797dd1cf26cc193ba6d8ebb` |
| `test-runtime-engine-v1.sh` | `7d153d650ff5415ac8c30c58080b47b5a480613a8bd88bb0fb9c41ed8dcf80ad` |
| Compose `runtime.json` | `db26ac0eb81d38c23c7883f2dda2c95c7dcbd3e4a9ee3509438293096938c5cb` |
| Tomcat `runtime.json` | `f36c7a10e65cd148f4bbc0aa29fa6efdd5e097a0d9d573967cea20cf469f9d6a` |

Observed documentation differences were corrected during the task:

- the programme resume title still pointed to completed task 3.4;
- the worker README still described the engine fixtures as pending;
- the Tomcat digest actually contains Temurin `1.8.0_402`, so the initial
  fixture declaration of `8.0.412` was corrected before acceptance;
- an initial AX42 invocation inherited `/home/jose` and produced harmless
  `find` CWD warnings; it was not used as evidence, and the complete suite was
  repeated cleanly from `/tmp`.

At the task 4.3 boundary, the original task 4.2 staging hashes remained
unchanged. The global client, manager and engine were deliberately absent, no
runtime sudoers entry or service existed, and capacity/resource-pressure work
had not started.

## Four-slot and heavy-operation admission

Task 4.4 adds two staged artifacts:

- `runtime-admission-v1.sh`;
- `test-runtime-admission-v1.sh`.

The admission helper is deliberately separate from task 3.2's deterministic
runtime allocation and task 4.2's privileged mediation. It runs before either
boundary and has no operation that can start a process or container. It
serializes all state changes through one worker-owned `flock`, then rebuilds
the complete capacity view from mode-restricted records persisted by canonical
WorkSession UUID.

Each held record owns exactly one of `slot1` through `slot4`. A WorkSession may
also own one of the independent `heavy1` or `heavy2` permits, but only while it
holds a normal slot. Release changes the persisted lease state to `released`
instead of deleting the record. Repeating an active acquisition returns the
same byte-stable record, while a fresh helper process recovers all held slot
and permit ownership from disk.

Before granting new capacity, the helper checks the real host or a
test-injected, strictly shaped metric document for:

- one-minute load no greater than 75% of online CPU capacity;
- at least 8 GiB of available host memory;
- no more than 8192 host processes.

Existing acquisitions remain repeatable and releasable under pressure. A new
normal request blocked by capacity or headroom returns
`NORMAL_CAPACITY_EXHAUSTED`; a heavy request returns
`HEAVY_CAPACITY_EXHAUSTED`. Human output is concise, and JSON output contains
the operation, state, WorkSession where applicable, current limits and an
actionable fixed error. Neither output includes environment values, raw
commands or secret material.

Local tests ran first and exclusively in the temporary inspection tree beneath
`/tmp`. The task 3.2, 3.3/3.4, 4.2, 4.3 and new 4.4 suites all passed. The new
suite proved:

- four concurrent normal requests receive `slot1` through `slot4` exactly
  once, and a fifth is blocked with `NORMAL_CAPACITY_EXHAUSTED`;
- two concurrent heavy requests receive `heavy1` and `heavy2`, and a third is
  blocked with `HEAVY_CAPACITY_EXHAUSTED`;
- normal and heavy leases are released and safely reused;
- concurrent duplicate requests serialize to one byte-identical record and
  result;
- a new process invocation recovers four normal owners and the persisted heavy
  owner without in-memory state;
- cross-session record identity, duplicate slot, duplicate permit, unsafe mode
  and symbolic-link conflicts fail closed;
- injected CPU, memory and process pressure blocks new work without creating a
  record, process or container marker;
- blocked and successful human/JSON states remain actionable and suppress an
  environment secret marker;
- persisted records survive release and synthetic worktrees, logs and
  artifacts remain byte-identical.

The same five suites passed on `codex-worker-01`, invoked from `/tmp` as
`atenea-worker`. A second AX42 exercise omitted the injected metric document
and used the host's real `/proc` state. It admitted four synthetic sessions
onto all four slots and two heavy operations, then returned the required codes
for the fifth normal and third heavy requests. Its observed ready state was:

| Metric | Observed | Admission threshold |
|---|---:|---:|
| One-minute load | `260` milli-load | `12000` maximum |
| Available memory | `63926693888` bytes | `8589934592` minimum |
| Host processes | `323` | `8192` maximum |
| Normal ownership | `4/4` | four |
| Heavy ownership | `2/2` | two |

All four installed rootless slot slices report the existing limits of 400% CPU,
10 GiB `MemoryHigh`, 12 GiB `MemoryMax` and 4096 tasks. A bounded 12-second
pressure exercise ran only inside the explicitly assigned `atenea-slot2` user
slice, with stricter transient limits of 50% CPU, 96 MiB `MemoryHigh`, 128 MiB
`MemoryMax` and 32 tasks. During the accepted run:

| Pressure observation | Result |
|---|---:|
| CPU control | `50000 100000`; 11 throttling events |
| Memory | `74616832 / 134217728` bytes |
| PIDs | `14 / 32` |
| Host available memory | `63866757120` bytes |
| Host process count | `338` |
| Beautips during load | `UP` in `0.004205` seconds |
| Slot 2 containers before/after | `0 / 0` |
| Transient unit final state | `inactive` |

The first pressure harness draft left its unbounded CPU generator active after
the intended observation interval. Its cleanup trap stopped the transient unit
and removed the script; a read-only check confirmed the unit inactive, no
matching slot process, zero slot 2 containers and Beautips `UP`. The generator
was corrected with its own 12-second timeout before the accepted run above.
No destructive or exhaustion load was attempted.

Read-only checks before the suites found no state divergence from the resume
protocol. After task 4.4:

- all task 4.2 and 4.3 protected hashes remain unchanged;
- `/srv/atenea/repositories` contains zero mirrors and
  `/srv/atenea/workspaces/sessions` is absent;
- slot 2 has zero containers and the same four pinned image digests;
- every slot daemon reports the rootless security option, and the rootful
  `docker` group has no member;
- rootful Docker, `docker.socket` and containerd remain inactive and masked;
- the global client, manager and engine remain absent, with no runtime sudoers
  entry or service;
- Beautips remains `UP`, Tailscale Serve remains `No serve config`, and the
  administrative Beautips `dev` hash remains
  `db58c7ac7e2dc71fab0d7ef6a04591236ec34ff223b0ae52951e3538aa6234d5`;
- Atenea production remains on `feature/actualizar-conversacion-en-web`, with
  its pre-existing dirty web changes untouched and no AX42 routing reference.

The repository and staging hashes for task 4.4 match:

| File | SHA-256 |
|---|---|
| `runtime-admission-v1.sh` | `f78f7f26b0ba16ffb4d05eaa60d3bc09cb6fd07c6d73172b9042b5d44b0187de` |
| `test-runtime-admission-v1.sh` | `e8b1f12768f646cd79f45c7c7d82adbbee702e174d2a3b2a07ec1f94e3383232` |

No real project, mirror, worktree or WorkSession was created. No application
was deployed, no global component was installed, no service or host was
restarted, and no Atenea route, production file, authentication material,
history, secret, commit or push was changed.

## Global runtime-contract verification

Task 5.1 adds `test-project-runtime-contract-v1.sh`, one minimum global suite
that composes the previously accepted boundaries without changing their
implementations. Its protected hash table fixes the accepted task 4.2, 4.3 and
4.4 sources, tests and fixture manifests before and after every complete run.

The suite has eight ordered blocks:

1. project-runtime schema and invalid-authority corpus;
2. session workspace ownership and cross-session denial;
3. runtime allocation, same-port uniqueness and serialization;
4. concise human `dev` output and stable JSON envelopes;
5. manager/client default-deny and cross-session policy;
6. both synthetic fixture lifecycles through the fixed engine;
7. four-slot/two-heavy admission and pressure denial;
8. integrated admission-to-allocation capacity, concurrency, simultaneous
   loopback identity and retained-evidence checks.

The integrated block acquires four normal sessions concurrently, uses each
persisted admitted slot for allocation and proves four distinct loopback
bindings for the same declared internal port `8080`. Two bounded synthetic
HTTP adapters remain healthy simultaneously and return only their owning
WorkSession identity. A fifth normal session and third heavy request return
the stable capacity codes without creating an admission record, workspace or
allocation.

Concurrent duplicate admission and allocation requests return byte-identical
results. Cross-session slot use is rejected before allocation changes. The
composed regressions additionally deny neighbouring workspace/branch use,
foreign allocation paths, record identities, manager inspection results,
engine resources, mounts, host namespaces, capabilities, devices, daemon
sockets and unsupported authority.

Before and after idempotent and denied operations, the suite hashes workspace
and allocation records, uncommitted synthetic worktree content, synthetic
mirror evidence, retained logs and artifacts. Counts remain exactly four
admission records and four allocation records. The two fixture lifecycle
suites cover build, up, status, logs, URL, restart, redeploy, stop and repeated
stop in human and JSON modes without duplicate resources. Environment markers,
raw adapter stdout/stderr and manifest lifecycle command markers are rejected
from normal output, normalized diagnostics and envelopes.

Local verification ran first from a fresh
`/tmp/remote-codex-platform-5.1.*` copy. Python `jsonschema` 4.26.0 performed
the draft-2020-12 meta-schema check, accepted both fixed fixtures and rejected
all eight invalid manifests. Shell syntax, ShellCheck and the complete suite
passed:

```text
[PASS] schema-corpus
[PASS] workspace-boundary
[PASS] allocation-regression
[PASS] dev-regression
[PASS] manager-regression
[PASS] engine-regression
[PASS] admission-regression
[PASS] integrated-capacity
Project runtime contract v1 integration tests passed (8/8).
```

The same suite then passed on `codex-worker-01` from `/tmp` as
`atenea-worker`. AX42 intentionally has no host-global Python `jsonschema`
package, so that run used the suite's explicit dependency-free corpus checks
and the manager denial regression. Runtime execution used the existing
fake-Docker adapter and temporary loopback listeners; no real slot container,
network or image was created. The listeners and every suite fixture were
removed by bounded cleanup.

The first AX42 invocation stopped in the schema block because the existing
staging root contained the two valid fixtures but not the schema or invalid
corpus. No lifecycle block or test process had started. The versioned
`project-runtime-v1.schema.json` and eight invalid JSON fixtures were added
only beneath `/srv/atenea/worker/workspace-v1`, and the complete invocation was
then repeated successfully. This staging-location dependency was the only
documentation/observed-state difference found during 5.1.

The repository and AX42 staging hash for the new suite match:

| File | SHA-256 |
|---|---|
| `test-project-runtime-contract-v1.sh` | `985a06efef41b9797d2b8f77c218f8c12f8f2c8abac9224629739218aeb4546e` |

The final read-only AX42 audit observed:

- zero mirrors beneath `/srv/atenea/repositories` and no
  `/srv/atenea/workspaces/sessions`;
- zero slot 2 containers and exactly the original Node, Maven, Tomcat and
  Playwright images with digests
  `048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34`,
  `3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e`,
  `e3ca75a4b11560bfb30894c3fa5d066ff0105e2e8e1ad183711df97606321e51`
  and
  `9bd26ad900bb5e0f4dee75839e957a89ae89c2b7ab1e76050e559790e946b948`;
- all four slot daemons still rootless, without rootful Docker authority, and
  all four user slices still at 400% CPU, 10 GiB `MemoryHigh`, 12 GiB
  `MemoryMax` and 4096 tasks;
- rootful Docker, `docker.socket` and containerd inactive and masked;
- Beautips `UP`, Tailscale `No serve config`, an empty rootful Docker group and
  the administrative `dev` hash unchanged at
  `db58c7ac7e2dc71fab0d7ef6a04591236ec34ff223b0ae52951e3538aa6234d5`;
- no global client, manager, engine or admission root, zero runtime sudoers or
  runtime services, and zero matching temporary suite roots beneath `/tmp`.

Atenea production remained at commit
`7e8afa6c7039a70aea3b330234ddeabdcf2a6587` on
`feature/actualizar-conversacion-en-web`. Its pre-existing dirty-state
fingerprint remained
`b3ede1645fc6b8b74dba9a0b09aab95cbdd37c02d5b15f7601b1b160be7ee022`,
and neither repository sources nor running container configuration contained
an observed AX42 routing reference.

No real project, mirror, worktree or WorkSession was used. No component was
installed globally, no service or host was restarted, no rootful Docker or
Tailscale route was enabled, and no production routing, authentication
material, history, secret, commit or push was changed.

## Runtime health, browser retention and proven-owned cleanup

Task 5.2 adds three staged artifacts without changing the accepted task 4.2,
4.3, 4.4 or 5.1 implementations:

- `project-runtime-browser-check-v1.js`;
- `runtime-cleanup-v1.sh`;
- `test-project-runtime-health-browser-cleanup-v1.sh`.

The Playwright check accepts only explicit cases and artifact roots beneath
`/tmp`. Every registered case binds one canonical synthetic WorkSession UUID
and run ID to its declared `127.0.0.1` allocation, route and viewport. It
requires HTTP success, visible non-empty body content and the fixture-specific
DOM text, then rejects horizontal overflow or viewport clipping before
capturing a fixed screenshot. Browser, context and page closure runs from
`finally`, and navigation, locator, screenshot and process execution all have
finite timeouts.

Artifacts are registered deterministically beneath:

`/tmp/codex-visual-checks/remote-codex-platform/<session>/runs/<run>/browser`

The registry records session, run, source, declared loopback URL, route,
viewport, content type, retention, path, SHA-256 and the visual measurements.
Repeating the same four checks retained exactly four PNGs and produced a
byte-identical registry rather than appending duplicate artifacts.

The cleanup helper independently validates the synthetic allocation, manifest,
complete runtime identity, fixed fixture project and assigned rootless socket.
It inspects every existing container, network and image before removing the
first resource. All three ownership labels must exactly match the fixed engine,
WorkSession and runtime identities. Empty labels, a foreign session/runtime and
a partial/ambiguous label set were each rejected while the resource remained
present. A matching engine temporary root also requires its exact ownership
marker. Workspace and allocation records, mirrors, worktrees, branches, logs
and run artifacts are outside the cleanup target set.

The local suite ran first beneath `/tmp` with two synthetic loopback HTTP
adapters and fake Docker state. Both health routes returned the expected
fixture and `UP` state. Playwright 1.60.0 with Chromium checked and captured:

| Fixture | Declared route | Desktop | Mobile |
|---|---|---|---|
| Compose | `/health` | `1440x900` | `390x844` |
| Tomcat | `/` | `1440x900` | `390x844` |

DOM and screenshot inspection found visible content, no empty state, no
clipping, no overlap and no horizontal overflow. Tomcat's longer JSON wrapped
legibly in the mobile viewport. The first adapter draft served `/health` as a
downloadable type, so Chromium correctly refused to produce a rendered page.
The accepted adapter explicitly returns `application/json`; the complete suite
was then repeated successfully. A second full run reconfirmed deterministic
artifact identities, label denial, retained hashes and zero fake-Docker
resources.

The required local regressions then passed individually:

- `test-project-runtime-contract-v1.sh` (`8/8`);
- `test-session-runtime-allocation-v1.sh`;
- `test-dev-session-v1.sh`;
- `test-runtime-manager-v1.sh`;
- `test-runtime-engine-v1.sh`;
- `test-runtime-admission-v1.sh`.

On AX42 the same six regressions passed from `/tmp` as `atenea-worker`.
The real fixture exercise used only rootless slot 2 and synthetic WorkSessions
`018f47a2-6b0c-7a31-9c2d-4f5a6b7c8dc3` and
`018f47a2-6b0c-7a31-9c2d-4f5a6b7c8dc4`. Their declared loopback endpoints were
`127.0.0.1:27421/health` and `127.0.0.1:27422/`. Both applications were
healthy simultaneously and returned only their expected fixture identity,
`UP` state and, for Tomcat, `java8-tomcat8`.

The worker has the pinned Playwright browser image but no host-global
Playwright installation. Rootless `host-gateway` cannot and should not reach a
host service bound to `127.0.0.1`. The accepted browser run therefore attached
one temporary, session-labelled Playwright container to each exact synthetic
runtime network and allowlisted only that session-derived container name as
the browser transport. The artifact registry continued to record the declared
operator loopback URL and port. No host network, wider bind, Tailscale route or
global package was introduced.

All four real-worker screenshots were inspected after retrieval. Compose and
Tomcat content was complete and readable at `1440x900` and `390x844`; the
recorded DOM measurements reported `horizontalOverflow=false` and
`clipped=false`. The Tomcat mobile response wrapped without losing content.
The retained Tomcat toolchain artifact still reported
`17.0.19 8 8.5.100`.

After browser verification, both runtimes retained their normalized logs,
records, worktrees, synthetic mirror markers and browser/toolchain artifacts
through `stop`. The first cleanup removed exactly the two labelled containers,
networks, fixture images and engine temporaries. The second cleanup reported
all removal booleans `false`. Hashes of retained evidence were identical before
and after cleanup, and no environment marker, raw diagnostic or secret-like
assignment appeared in logs, JSON, registry or artifacts.

Two harness defects were observed before the accepted worker run: a shell
comparison newline stopped the first draft after startup, and pre-created
root-owned browser directories blocked the rootless browser writer. In both
cases the new cleanup helper removed only the exact labelled fixture resources,
read-only checks confirmed no residue, and the exercise restarted from a fresh
fixed `/tmp` root. The accepted run completed after using a writable synthetic
artifact root owned by the run.

The final AX42 audit observed:

- zero mirrors and no `/srv/atenea/workspaces/sessions`;
- zero slot 2 containers and exactly the original Node, Maven, Tomcat and
  Playwright image digests;
- zero resources carrying the synthetic engine label in any slot;
- all four daemons rootless and all four slices unchanged at 400% CPU,
  10 GiB `MemoryHigh`, 12 GiB `MemoryMax` and 4096 tasks;
- rootful Docker, `docker.socket` and containerd inactive and masked, with an
  empty rootful Docker group;
- Beautips `UP`, Tailscale `No serve config` and the administrative `dev` hash
  unchanged;
- no global client, manager, engine or admission root, no runtime sudoers or
  runtime service and no matching temporary suite root or browser process;
- only the four declared browser PNGs, their registry and the declared Tomcat
  toolchain artifact retained beneath the synthetic `/tmp` evidence root.

The temporary Playwright npm tree, extracted browser probe, wrappers and live
harness were removed after acceptance. No global executable, package, service,
sudoers rule, group membership, rootful daemon, real project, mirror,
WorkSession, production route, secret, commit or push was introduced.

Repository and AX42 staging hashes match:

| File | SHA-256 |
|---|---|
| `runtime-cleanup-v1.sh` | `4ac7edfe60764f2f37a825c92faf982b168a574db11bc456569f4ac5aa77cae3` |
| `project-runtime-browser-check-v1.js` | `3b235ab654561998051ccff4b841038cdb692ad87496e6e48ea24f3e351c3dff` |
| `test-project-runtime-health-browser-cleanup-v1.sh` | `cff656808b5eb2804389d0c2ad1099458cda93fd72243c8f4adb6f6f277c343b` |

## Task 5.3 controlled reboot attempt — blocked

Task 5.3 remains incomplete after the single authorized controlled reboot on
2026-07-26. No reconciliation or post-reboot runtime suite was accepted.

The initial read-only audit matched the documented programme state. The
programme worktree was clean at local and remote commit
`5fac27b313b9e06e175915ba8ee8e95e0ae3e76a`; OpenSpec was valid at `18/21`.
Atenea production remained on
`feature/actualizar-conversacion-en-web` at
`7e8afa6c7039a70aea3b330234ddeabdcf2a6587`, with dirty-state fingerprint
`b3ede1645fc6b8b74dba9a0b09aab95cbdd37c02d5b15f7601b1b160be7ee022`
and no observed AX42 routing.

One precheck difference was found and repaired before the reboot. The private
network path from Atenea to AX42 reached TCP/22, but Atenea had no accepted SSH
identity even though the archived bootstrap evidence declared this path
working. A dedicated control-plane ED25519 identity was created for the
`atenea` account, the previous worker administrator `authorized_keys` was
backed up beneath `/var/backups/atenea-worker-runtime`, and exactly that public
key was added idempotently. Fresh Atenea-to-worker administration, laptop
administration and root key-only break-glass access then passed. No sshd,
firewall or Tailscale policy was changed.

All remaining prechecks passed before reboot:

- no real `atenea-worker` job, mirror, WorkSession or admission root;
- slot 2 had zero containers and exactly the four pinned image digests;
- all four rootless daemons and proxy sockets were active;
- every user slice retained 400% CPU, 10 GiB `MemoryHigh`, 12 GiB
  `MemoryMax` and 4096 tasks;
- rootful Docker, `docker.socket` and containerd were inactive and masked;
- all three RAID arrays were `[UU]`, with no operation active, and SMART
  passed;
- strict worker verification passed SSH, firewall, private networking, time,
  security updates and health timer checks;
- Beautips was `UP`, its administrative `dev` hash was unchanged and
  Tailscale Serve reported `No serve config`;
- protected runtime-contract hashes matched;
- no global runtime client, manager or engine, runtime sudoers, runtime
  service, browser process or temporary broker was present.

Because the retained 5.2 state did not include runtime records, the attempt
created a minimal synthetic reconciliation fixture. Four canonical synthetic
WorkSessions acquired `slot1` through `slot4`; only the sessions persisted for
the exercise used their admitted `slot2`, `slot3` and `slot4`. Slot 2 held one
live, fully labelled synthetic resource; slot 3 held one stopped, fully
labelled resource; slot 4 declared an absent resource. A deliberately
unlabelled synthetic resource in slot 2 provided the ambiguous-ownership denial
case. Records, logs, worktrees and artifacts had a pre-reboot hash manifest.
A root broker beneath `/tmp` accepted only the exact fixture operations and
paths and verified the Unix peer as `atenea-worker`. It terminated before the
reboot.

The reboot request was issued at `2026-07-26T14:42:04Z`. SSH returned within
the four-minute timeout. The boot ID changed from
`1ca0c820-0065-4e80-9758-845113996096` to
`0677f046-561d-4a36-a5c8-c5254c49d474`. Named administration, Atenea
administration and root break-glass access returned. Strict host verification,
RAID/SMART, SSH, firewall, Tailscale, time, health timer, all four rootless
slots, rootful-daemon masks and systemd limits also returned correctly.

Two acceptance-blocking differences then appeared:

1. Beautips did not recover health. PostgreSQL and Redis remained exited
   because their restart policy was `no`; the application repeatedly restarted
   with an unresolved `postgres` hostname and could not obtain a database
   connection. No Beautips container was deliberately restarted, replaced or
   redeployed.
2. The reboot removed both the retained 5.2 evidence root and the complete 5.3
   fixture under `/tmp`, including records, logs, worktrees, artifacts and the
   pre-reboot hash manifest. Rootless Docker resources persisted, but the
   declared filesystem evidence did not. This proves that `/tmp` is an
   execution location, not a valid reboot-retention boundary on this host.

The stop condition was applied immediately. Reconciliation, lifecycle,
isolation and Playwright suites were not run and 5.3 was not marked complete.
The exact synthetic containers were inventoried by name, creation time, pinned
image and, where applicable, complete engine/session/runtime labels. The two
labelled resources and the deliberately unlabelled resource created by this
attempt were removed; the temporary slot 3 image was removed; slots 2–4 were
returned to their pre-attempt synthetic-resource state. No second reboot was
attempted.

Before task 5.3 can be repeated, the programme needs an approved durable
synthetic evidence location outside reboot-cleaned `/tmp` while keeping
executables and live harnesses beneath `/tmp`, plus an explicit operator
decision for the Beautips post-reboot dependency/restart defect. A further
reboot requires new authorization. Task 5.4 remains out of scope.

## Task 5.3 controlled reboot and reconciliation — accepted

Task 5.3 was repeated and accepted on 2026-07-26 after the operator approved
the two blocking corrections and a further controlled reboot. Task 5.4 was not
started.

The administrative Beautips Compose source received only two declarative
changes: PostgreSQL and Redis now use `restart: unless-stopped`, matching the
application. Only those two dependency containers were recreated before the
reboot; the application was not deliberately replaced or restarted. All three
services became healthy/running with `unless-stopped`, and the protected
administrative `dev` hash remained
`db58c7ac7e2dc71fab0d7ef6a04591236ec34ff223b0ae52951e3538aa6234d5`.
The Compose source change was committed independently in the administrative
Beautips repository as
`5044a3b07b3db82895e9c8ff47bc4bc9b0e97130`; it was not pushed during this
evidence refresh.

The repeated precheck passed the complete archived host baseline and confirmed:

- no real job, mirror, WorkSession, admission state or Atenea-to-AX42 routing;
- slot 2 with zero containers and exactly the four pinned Node, Maven, Tomcat
  and Playwright image digests;
- all four rootless daemons and proxies, fixed 400% CPU, 10 GiB
  `MemoryHigh`, 12 GiB `MemoryMax` and 4096-task slice limits;
- rootful Docker, `docker.socket` and containerd inactive and masked;
- all three RAID arrays healthy at `[UU]`, SMART passing, archived SSH and
  firewall policy, private connectivity, time and health timer passing;
- laptop administration, root public-key break-glass and named administration
  from Atenea over the private network;
- Beautips `UP`, Tailscale `No serve config`, protected source/staging hashes,
  no global runtime executables, runtime sudoers/services, browser process or
  temporary broker.

After that empty-state gate, four unambiguously synthetic UUIDs acquired
`slot1` through `slot4`. Executables and the peer-credential-authenticated
broker stayed beneath `/tmp`, but admitted records used
`/srv/atenea/worker/runtime-admission-v1`; WorkSession state and worktrees used
`/srv/atenea/workspaces/sessions`; logs and artifacts used
`/srv/atenea/artifacts/sessions`; controlled caches used
`/srv/atenea/caches/sessions`. A 40-file pre-reboot manifest covered the
declared persistent fixture.

The real Docker matrix contained:

- one running, completely labelled resource in admitted `slot2`;
- one stopped, completely labelled resource in admitted `slot3`;
- one declared absent resource in admitted `slot4`;
- one unlabelled, one foreign-labelled and one partially labelled denial
  resource in admitted `slot2`.

The reboot was requested at `2026-07-26T15:16:54Z`. SSH returned within the
four-minute bound and the boot ID changed from
`0677f046-561d-4a36-a5c8-c5254c49d474` to
`0886b4d0-485c-4035-b8bb-1b0ab910e85c`. A first combined gate exited while
services were still settling; the direct sanitized verifier then showed all
13 checks passing and every strict repeat passed. The four proxy services were
correctly socket-activated on their first read-only Docker request. Before any
runtime action, RAID/SMART, firewall, SSH, private networking, Tailscale, time,
rootful masks, rootless daemons, slice limits and all three administrative
paths passed. Beautips recovered automatically with all three containers
running under `unless-stopped`, remained `UP`, and Tailscale Serve remained
unconfigured.

The complete pre-reboot manifest verified after reboot. Admission still held
the four exact slots, all records, logs, worktrees, caches and artifacts
survived, and the expected Docker resources survived stopped. Real
reconciliation then:

- restarted only the completely owned live resource and set its WorkSession
  state to `ready`;
- preserved the stopped resource and terminal `stopped` state;
- kept the absent resource `blocked` with
  `recreate-if-authorized`;
- rejected unlabelled, foreign-labelled and partially labelled resources with
  `RUNTIME_OWNERSHIP_CONFLICT`;
- produced identical stable results on its second and third execution without
  duplicating the durable reconciliation artifact or changing terminal state.

The required local suites passed:

- `test-project-runtime-contract-v1.sh`;
- `test-session-runtime-allocation-v1.sh`;
- `test-dev-session-v1.sh`;
- `test-runtime-manager-v1.sh`;
- `test-runtime-engine-v1.sh`;
- `test-runtime-admission-v1.sh`;
- `test-project-runtime-health-browser-cleanup-v1.sh`.

The same six non-browser suites passed explicitly on AX42 as `atenea-worker`.
The browser/retention/cleanup suite also passed on AX42 inside the pinned
Playwright image in admitted slot 2 with network `none`, rootless Docker,
finite timeouts and complete synthetic ownership labels. The official image
contains browsers but not the npm module or `jq`; exact Playwright 1.60.0
modules and the worker's existing `jq` libraries were therefore staged only
beneath `/tmp`. Because the slot identity cannot traverse the staging parent
under `/srv`, an exact read-only copy of `workspace-v1` was used beneath
`/tmp`; no `/srv` permission was widened. The fixture's `cp -a` required only
the temporary container capability `CHOWN` after `cap-drop ALL`; it retained
`no-new-privileges`, rootfs read-only, CPU/memory/PID limits and no network.

The first local browser invocation used a shared visual root that already held
four older PNGs, so its global count check rejected eight files. Repeating in
an exclusive child root passed. Four local and four AX42 screenshots were
inspected at 1440×900 and 390×844: Compose and Tomcat text was complete,
legible and free of clipping or horizontal overflow, including wrapped Tomcat
mobile output. No Playwright or Chromium process remained.

Cleanup was proven twice against the real resources. The first pass removed
the two completely owned resources, rejected all three denial resources, then
removed those three only after their immutable Docker identity matched exact
synthetic creation records; it also removed the slot 3 fixture image. The
second pass removed zero resources and reported the image already absent.
Admission records were released, structured post-reboot state was archived,
and the canonical synthetic WorkSession/admission/cache roots were removed.

The final AX42 audit passed:

- zero mirrors, no `/srv/atenea/workspaces/sessions` and no
  `/srv/atenea/worker/runtime-admission-v1`;
- zero containers in slots 2–4, slot 2 with exactly its original four pinned
  images, and no fixture image in slots 3–4;
- no temporary fixture, broker, browser process, global runtime executable,
  runtime sudoers or runtime service;
- four rootless daemons/proxies and unchanged slice limits;
- rootful Docker, its socket and containerd still inactive and masked;
- strict archived host checks and three RAID arrays at `[UU]`;
- Beautips `UP` with unchanged `dev` hash and Tailscale `No serve config`;
- all protected runtime-contract hashes unchanged.

Twenty-seven declared synthetic evidence files remain under
`/srv/atenea/artifacts/sessions`, covered by
`final-retained-v2.sha256`. They include pre/post host state, reconciliation,
idempotent cleanup, browser artifact hashes, release records and the archived
structured state. They contain no detected secret marker or private key.

Atenea production remained on
`feature/actualizar-conversacion-en-web` at
`7e8afa6c7039a70aea3b330234ddeabdcf2a6587`, with unchanged dirty-state
fingerprint
`b3ede1645fc6b8b74dba9a0b09aab95cbdd37c02d5b15f7601b1b160be7ee022`,
an empty index and no observed AX42 routing before or after the reboot.

## Task 5.4 runtime-manager rollback — accepted

Task 5.4 was executed and accepted on 2026-07-26. Task 5.5 was not started.
The administrative Beautips commit
`5044a3b07b3db82895e9c8ff47bc4bc9b0e97130` was first fetched, revalidated
as the single direct descendant of the previous `origin/main`, and published
by a normal fast-forward push. Local and remote `main` now point to that exact
commit with zero divergence and a clean worktree. Compose configuration
validated with the existing administrative environment; PostgreSQL, Redis and
the application remained running under `unless-stopped`, and application
health remained `UP`.

The pre-rollback AX42 audit matched the accepted 5.3 boundary:

- zero mirrors and no real or synthetic WorkSession/admission roots;
- zero containers in slots 2–4, exactly the four fixed images in slot 2 and
  no fixture images in slots 3–4;
- four active rootless daemons and proxies with 400% CPU, 10 GiB
  `MemoryHigh`, 12 GiB `MemoryMax` and 4096 tasks per slice;
- rootful Docker, `docker.socket` and containerd inactive and masked;
- strict SSH, firewall, private-network, RAID/SMART, time, capacity and health
  checks passing, with all three arrays at `[UU]`;
- Beautips `UP`, Tailscale Serve at `No serve config`, the administrative
  `dev` hash protected and the 5.3 retained-evidence manifest intact;
- no global runtime client, manager or engine, runtime sudoers/service,
  temporary broker, fixture or Playwright/Chromium process.

The protected local suites ran first from an isolated `/tmp` copy, followed by
the same suites on AX42 as `atenea-worker`:

- `test-project-runtime-contract-v1.sh` (`8/8`);
- `test-session-runtime-allocation-v1.sh`;
- `test-dev-session-v1.sh`;
- `test-runtime-manager-v1.sh`;
- `test-runtime-engine-v1.sh`;
- `test-runtime-admission-v1.sh`.

One unambiguously synthetic target WorkSession and one admission-only filler
were then admitted so the target received persisted `slot2`. The target used a
local synthetic bare Git mirror, a real Git worktree, the fixed Compose
fixture, a dirty worktree marker, retained logs and retained run artifacts in
the canonical `/srv/atenea` roots. No real project, remote repository or
Atenea WorkSession was used.

The protected client, manager and engine plus one exact sudoers delegation
were installed only for the bounded exercise; no service was created. The real
chain built the completely labelled image. This exposed one previously
undocumented canonical-path defect: `engine-v1` inherited mode `2700` beneath
the setgid runtime root, while the next engine invocation requires exact mode
`0700`. The protected source remained unchanged. Only that synthetic engine
directory had its setgid bit removed before continuing. The real manager then
started the fixture, returned structured `ready/healthy` state and the
loopback health response identified only `dummy-compose` with `UP`.

Three additional stopped containers exercised denial with no labels,
foreign complete labels and partial labels. The rollback helper beneath
`/tmp` rejected each with `RUNTIME_OWNERSHIP_CONFLICT` and left it present.
Two manager `stop` calls returned the same structured terminal
`stopped/stopped` result. The first rollback pass removed exactly the owned
container, network, fixture image and engine temporary. The second pass
removed nothing. Both retained the same stable structured terminal state.

The rollback-boundary manifest verified byte-identical preservation of the
workspace record, allocation, runtime manifest, dirty worktree marker, runtime
log, an independent retained log and run artifact. The synthetic mirror ref
also remained unchanged. An earlier pre-lifecycle manifest comparison had
shown only `runtime.log` changing because the explicit manager `logs`
operation refreshes that declared log; the accepted rollback-boundary manifest
was therefore taken after lifecycle/log collection and before cleanup.

The three denied containers were finally removed only after their current
immutable Docker ID, name, creation time and image matched their recorded
synthetic creation identities. Admission was released, the temporary global
files and sudoers rule were removed, and only the exact synthetic mirror,
worktree, cache, admission/allocation roots and `/tmp` tools were deleted.
Thirty-seven declared evidence files remain under:

`/srv/atenea/artifacts/sessions/018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d54/runs/synthetic-run-5-4-rollback/rollback-evidence`

They are covered by `final-retained-v1.sha256`. The final audit returned to
the complete pre-fixture AX42 baseline, retained the 5.3 evidence manifest,
and found no runtime, Docker, broker or browser residue.

The repository and AX42 staging protected hashes remained:

| File | SHA-256 |
|---|---|
| `runtime-manager-v1.sh` | `c7c394907706fc1f5699c9ac4f4167b4337981f5b26db4e7109cf0344148ee78` |
| `runtime-client-v1.sh` | `0792bfae3f583474f51fef0d18169e4e7ffaad445efcc5f0de2470892b4089cb` |
| `test-runtime-manager-v1.sh` | `4e77070cbbbd5ca913297d7beb035d85af34bda22fcb64e64e0955fa8049a9cc` |
| `runtime-engine-v1.sh` | `3144aff72b6e53a3022aa8229ac19fc81f1b470a7797dd1cf26cc193ba6d8ebb` |
| `test-runtime-engine-v1.sh` | `7d153d650ff5415ac8c30c58080b47b5a480613a8bd88bb0fb9c41ed8dcf80ad` |
| Compose `runtime.json` | `db26ac0eb81d38c23c7883f2dda2c95c7dcbd3e4a9ee3509438293096938c5cb` |
| Tomcat `runtime.json` | `f36c7a10e65cd148f4bbc0aa29fa6efdd5e097a0d9d573967cea20cf469f9d6a` |
| `runtime-admission-v1.sh` | `f78f7f26b0ba16ffb4d05eaa60d3bc09cb6fd07c6d73172b9042b5d44b0187de` |
| `test-runtime-admission-v1.sh` | `e8b1f12768f646cd79f45c7c7d82adbbee702e174d2a3b2a07ec1f94e3383232` |
| `test-project-runtime-contract-v1.sh` | `985a06efef41b9797d2b8f77c218f8c12f8f2c8abac9224629739218aeb4546e` |

Atenea production remained on
`feature/actualizar-conversacion-en-web` at
`7e8afa6c7039a70aea3b330234ddeabdcf2a6587`. Its dirty-state fingerprint
remained
`b3ede1645fc6b8b74dba9a0b09aab95cbdd37c02d5b15f7601b1b160be7ee022`,
the index remained empty, private connectivity passed, and both repository
sources and running container environments contained zero observed AX42
routing references before and after rollback.

## Task 5.5 final validation and archive gate

Before archive, the canonical-path mode difference found by task 5.4 was
resolved in the protected engine. `runtime-engine-v1.sh` now explicitly removes
the inherited setgid bit and fixes the new engine state root at mode `0700`
before writing its ownership marker. No validation rule was weakened: repeated
invocations still reject a pre-existing engine root unless it is a regular
directory owned by the engine identity, mode `0700`, and carries the exact
WorkSession/runtime marker.

`test-runtime-engine-v1.sh` now prepares each synthetic runtime root at mode
`2770`, matching the canonical worker allocation boundary, and asserts that
the engine state root is `0700` immediately after `build`. The modified
regression failed against the previous protected engine with
`engine state root inherited the parent setgid mode` and passed against the
corrected source. This proves the test detects the observed defect instead of
only documenting it.

The corrected local verification ran first from an isolated `/tmp` copy:

- `test-project-runtime-contract-v1.sh` passed all eight blocks;
- `test-session-runtime-allocation-v1.sh` passed;
- `test-dev-session-v1.sh` passed;
- `test-runtime-manager-v1.sh` passed;
- `test-runtime-engine-v1.sh` passed with the setgid regression;
- `test-runtime-admission-v1.sh` passed.

The same six suites then passed on `codex-worker-01` from `/tmp` as
`atenea-worker`. No global client, manager or engine was installed and the
suites left no fixture, broker, container, network, image or process residue.
The final worker gate passed strict hostname, account, filesystem, SSH,
firewall, RAID/SMART, capacity, time, security-update, Tailscale and health
checks. All four rootless slots and proxies remained active with their fixed
limits; slots 2–4 contained zero containers, slot 2 retained exactly its four
fixed images, and slots 3–4 retained zero fixture images. Rootful Docker,
`docker.socket` and containerd remained inactive and masked.

Both retained manifests from tasks 5.3 and 5.4 verified after the correction.
Beautips remained `UP`, clean and synchronized at
`5044a3b07b3db82895e9c8ff47bc4bc9b0e97130`. Atenea production remained on
`feature/actualizar-conversacion-en-web` at
`7e8afa6c7039a70aea3b330234ddeabdcf2a6587`, with unchanged dirty-state
fingerprint
`b3ede1645fc6b8b74dba9a0b09aab95cbdd37c02d5b15f7601b1b160be7ee022`,
an empty index, working private connectivity and zero observed AX42 routing
references in repository sources or running container environments.

The final corrected hashes in both the repository and AX42 staging are:

| File | SHA-256 |
|---|---|
| `runtime-manager-v1.sh` | `c7c394907706fc1f5699c9ac4f4167b4337981f5b26db4e7109cf0344148ee78` |
| `runtime-client-v1.sh` | `0792bfae3f583474f51fef0d18169e4e7ffaad445efcc5f0de2470892b4089cb` |
| `test-runtime-manager-v1.sh` | `4e77070cbbbd5ca913297d7beb035d85af34bda22fcb64e64e0955fa8049a9cc` |
| `runtime-engine-v1.sh` | `0f6d3da1d2ad974f31935bb24105c0e6871174fb2918db2ae05d0ca240ac6850` |
| `test-runtime-engine-v1.sh` | `63830dfe9eaa3180cde0743311c1ac9aefcf1c1b71f070a8bb5c61807bccea46` |
| Compose `runtime.json` | `db26ac0eb81d38c23c7883f2dda2c95c7dcbd3e4a9ee3509438293096938c5cb` |
| Tomcat `runtime.json` | `f36c7a10e65cd148f4bbc0aa29fa6efdd5e097a0d9d573967cea20cf469f9d6a` |
| `runtime-admission-v1.sh` | `f78f7f26b0ba16ffb4d05eaa60d3bc09cb6fd07c6d73172b9042b5d44b0187de` |
| `test-runtime-admission-v1.sh` | `e8b1f12768f646cd79f45c7c7d82adbbee702e174d2a3b2a07ec1f94e3383232` |
| `test-project-runtime-contract-v1.sh` | `f5035927f697403ca605b2af585f63d4feaf3aea9cf264c1d42d2c91c306d694` |

The two active delta specs were assessed before archive. Sync updates the four
modified Codex environment requirements and the four modified isolated runtime
requirements while preserving the unaffected stable requirements. No delta
removal or rename is requested. Playwright was not run for task 5.5 because
the correction changes only engine filesystem mode handling and has no visible
surface.

The final OpenSpec gate completed at 21/21 tasks. Strict validation passed, and
`openspec archive establish-project-runtime-contract -y --json` synchronized
exactly eight modified requirements with zero additions, removals or renames.
The change is retained at
`openspec/changes/archive/2026-07-26-establish-project-runtime-contract`, and
`openspec list` reports no active changes. No subsequent phase was created or
started.

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
