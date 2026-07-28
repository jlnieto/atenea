# Atenea development relocation evidence

Change: `relocate-atenea-development-to-ax42`.

## Task 1.1 — entry identity and pre-change sentinels

Accepted on 2026-07-27 after fresh inspection of the control plane and AX42.
This task recorded state only. It did not create a mirror, WorkSession,
allocation, worktree, runtime, route, container, network, image or fixture, and
it did not restart any service or host.

### Canonical Atenea source

The selected source is the configured GitHub `origin` for repository `atenea`.
The URL itself is intentionally omitted; its SHA-256 identity is sufficient for
future equality checks without disclosing a private repository location.

| Sentinel | Pre-change value |
|---|---|
| Capture time | `2026-07-27T09:18:17Z` |
| Remote host class | `github.com` |
| Remote URL SHA-256 | `67198bc7c082ef62555dfa69e4ebb7644feacebb1ca17f6f0f1b3848dd12dbf3` |
| Selected branch | `feature/actualizar-conversacion-en-web` |
| Accepted entry commit | `a9fe14989544308acc587e3eb71cb985fa637b2d` |
| Local HEAD | `a9fe14989544308acc587e3eb71cb985fa637b2d` |
| Fetched remote branch | `a9fe14989544308acc587e3eb71cb985fa637b2d` |
| Configured upstream | `a9fe14989544308acc587e3eb71cb985fa637b2d` |
| Accepted commit is remote ancestor | yes |
| HEAD tree | `2aae9b2a6c0dbc0da4182a9f1e124ebbae5e5f98` |
| Index tree | `2aae9b2a6c0dbc0da4182a9f1e124ebbae5e5f98` |
| Worktree entries | `0` |
| Index entries | `0` |

`git fetch origin` ran before recording these values. Local HEAD, upstream and
the fetched branch were identical, so no divergence, uncommitted authority or
control-plane-only source was accepted.

### Atenea production sentinels

| Sentinel | Pre-change value |
|---|---|
| Public application health | `UP` |
| Atenea containers | `9` present, `9` running, `0` unhealthy |
| Container configuration SHA-256 | `a56765986a7625accffa5dbd1c7976b145a465b75fecf3f9a7ff309e2bac1bff` |
| Container state SHA-256 | `e37e1b5c024db9dca86559df7b9eb7fb257bd7e5aea5bce3d8d440c84eabde53` |
| Production database identity SHA-256 | `295113c003ce8886500d1654d5e92962de05eae749a33ea077460a87ec550414` |
| AX42 routing matches in non-documentation source | `0` |
| AX42/remote-worker routing environment keys | `0` |
| Private worker DNS | pass |
| Private worker TCP/22 | pass |

The container configuration digest is calculated from sorted container names,
immutable image IDs, restart policy, Compose project/service labels and sorted
mount type/name/destination. Bind source paths and all environment values are
excluded. The state digest covers sorted name, running/health state and restart
count. The database digest covers only the production PostgreSQL container's
immutable image, restart policy and sorted non-secret mount identity.

### AX42 sentinels

AX42 capture time was `2026-07-27T09:19:10Z`.

| Sentinel | Pre-change value |
|---|---|
| Strict host verifier | `true`, `13/13` checks |
| Strict verifier JSON SHA-256 | `fbb8ba4b76540fed5fb1ecb84649587a2ea2ebedc593041a8c4daa956da2326e` |
| Repository mirrors | `0` |
| `/srv/atenea/workspaces/sessions` | absent |
| `/srv/atenea/worker/runtime-admission-v1` | absent |
| Global runtime client/manager/engine files | `0` |
| Runtime sudoers files | `0` |
| Atenea runtime systemd units | `0` |
| Rootless proxy services | `4/4` active |
| Rootful Docker service/socket/containerd | inactive and masked |
| Rootful Docker group members | `0` |
| RAID arrays | `3/3` at `[UU]`, no active recovery action |
| Tailscale Serve | `No serve config` |
| Playwright/Chromium processes | `0` |
| Temporary relocation/broker/browser fixtures | `0` |
| Retained 5.3/5.4 manifests | `2/2` valid |

All four rootless daemons reported the rootless security option. Their exact
slice values remained CPU `4s` per second (`400%`), `MemoryHigh=10737418240`,
`MemoryMax=12884901888` and `TasksMax=4096`.

| Slot | Containers | Images | Role |
|---|---:|---:|---|
| 1 | 3 | 3 | Existing administrative Beautips runtime |
| 2 | 0 | 4 | Fixed toolchain baseline |
| 3 | 0 | 0 | Empty |
| 4 | 0 | 0 | Empty |

The sorted slot 2 image-ID digest was
`f389d24080182c482f962877dfb45eb9975b34a4b4efe7df2a02f20ea927bebe`.

Beautips remained `UP`, clean and synchronized locally and remotely at
`5044a3b07b3db82895e9c8ff47bc4bc9b0e97130`. Its application, PostgreSQL and
Redis containers were running with `unless-stopped`; PostgreSQL and Redis were
healthy. The administrative `dev` SHA-256 remained
`db58c7ac7e2dc71fab0d7ef6a04591236ec34ff223b0ae52951e3538aa6234d5`.

### Gate result

The documented entry assumptions matched observed state. The canonical source
identity is accepted and the production/worker pre-change comparison point is
now durable. Task 1.2 may prepare and validate the committed Atenea runtime
manifest, but no runtime activation or routing is authorized by this evidence.

The post-recording comparison at `2026-07-27T09:23:01Z` reproduced the same
Atenea HEAD, clean state, health, container configuration/state hashes and zero
routing counts. AX42 reproduced the same strict-verifier and slot 2 image
hashes, zero mirrors, absent session/admission roots, empty slots 2–4 container
sets, rootful masks, `[UU]` arrays and `No serve config`. Beautips remained
clean, synchronized and `UP`.

## Task 1.2 — committed runtime manifest

Accepted on 2026-07-27. Atenea commit
`0c1bbd3a8c9ca580ec816d604ad7a84b27cea2af` adds only
`ops/atenea-runtime.json` directly above the accepted entry commit. The
worktree and index are clean. The commit is intentionally not pushed yet:
local Atenea is one commit ahead while the selected GitHub branch remains at
`a9fe14989544308acc587e3eb71cb985fa637b2d`.

The manifest SHA-256 is
`3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3`;
its sorted canonical JSON SHA-256 is
`093b5ef527e01751bdf6ef5651df4582826caede5f47d8608402bd1178c5bf78`.
Draft 2020-12 schema validation and additional semantic checks proved:

- repository identity and branch match the configured Atenea `origin`;
- workload class is `heavy`;
- Git `2.43.0`, Java build/runtime `21.0.7+6`, Maven `3.9.9`, Node
  `22.16.0`, Docker `29.6.2`, Compose `5.3.1`, Chromium `148.0.7778.96`
  and Playwright `1.60.0` match worker lock v1;
- the declared services are development PostgreSQL, Codex App Server and
  Atenea, with only their three reviewed internal ports;
- preview is private and the operator check declares both required viewports;
- all lifecycle/browser secret references resolve to five named
  development-only declarations, with zero literal secret fields;
- backend tests, web assets, runtime logs and browser evidence have explicit
  run/session retention paths.

AX42 independently reproduced the manifest hash, passed
`verify-slot 2` against toolchain lock v1 and retained exactly four images and
zero slot 2 containers. From an isolated `/tmp` checkout, the complete project
runtime contract suite passed `8/8` and the standalone admission suite passed.

The manifest deliberately points to
`ops/worker/docker-compose.ax42.yml` and
`ops/worker/atenea-runtime-v1.sh`, which do not exist until the reviewed
adapter tasks. Therefore schema/toolchain acceptance does not make the project
activable and cannot fall back to the current control-plane Compose file.
No mirror, WorkSession, allocation, runtime, route or secret value was created.

The post-commit comparison at `2026-07-27T09:58:47Z` reproduced the task 1.1
production container configuration, state and database hashes, application
health `UP` and zero routing environment keys. Source outside the new manifest
still has zero AX42/remote-worker matches. A deliberately broad literal scan
now finds one descriptive `AX42-only authentication boundary` purpose inside
the manifest; it is a secret-purpose description, not a host, URL, route or
production configuration.

AX42 reproduced its strict-verifier hash, zero mirrors, absent
session/admission roots, empty slot 2–4 container sets, slot 2's four images,
rootful masks, `[UU]` arrays and `No serve config`. Beautips remained clean,
synchronized and `UP`. This is the only documented difference from the task
1.1 source-text sentinel.

## Task 1.3 — empty PostgreSQL and synthetic fixture inventory

Accepted on 2026-07-27. Atenea commit
`7dffd34b6e3d49e2fc429ab7537928b1ef644562` adds only:

- `ops/atenea-development-data-v1.json`, SHA-256
  `ae757a4befde1ba02fca03abc205779d5a8f919d121c8c14bc02a24cc755046d`;
- `ops/atenea-development-migrations-v1.sha256`, SHA-256
  `360203affcb287b446a106651602affe11f6f60d0523283825d9a403d449a9ff`.

The commit is intentionally local and was not pushed. Atenea is clean and two
commits ahead of
`origin/feature/actualizar-conversacion-en-web`, which remains at
`a9fe14989544308acc587e3eb71cb985fa637b2d`.

### Reviewed data and authentication surface

The review covered the active proposal, design, capability deltas and task
list; the task 1.1/1.2 evidence; `ops/atenea-runtime.json`; all 45 Flyway
migrations; PostgreSQL and test scripts; application datasource/Flyway
configuration; operator security, bootstrap, JWT and refresh-token code; the
project bootstrap and repository model; WorkSession, SessionTurn and AgentRun
models; related unit/integration fixtures; and the React login, home and
project read paths.

The current application bootstrap creates at most one operator by
case-insensitive email and BCrypt-hashes the supplied password. V25 creates the
operator tables, V15–V17 create WorkSession conversation state, and V22 removes
the legacy task tables. The operator console requires an authenticated
operator and reads projects plus their latest WorkSession state. These facts
support the deliberately small fixture below.

### Versioned contract

The inventory requires PostgreSQL major version 16 on a new, session-owned,
empty volume. It permits only the declared session-internal database identity
and requires Flyway to validate and apply V1 through V45 in numeric order. The
separate checksum file pins every migration by path and SHA-256.

The only declared baseline fixture is:

| Table | Count | Synthetic identity |
|---|---:|---|
| `operator_account` | 1 | `operator.ax42.synthetic.v1@invalid.example` / `[SYNTHETIC] AX42 Operator V1` |
| `project` | 1 | ID `8000000000000201` / `[SYNTHETIC] Atenea AX42 V1` |
| `work_session` | 1 | closed ID `8000000000000301`, with no external thread or pull request |
| `session_turn` | 2 | IDs `8000000000000401` and `8000000000000402`, both `[SYNTHETIC]` |

All other 24 domain-table counts are exactly zero at the post-fixture
baseline, including refresh tokens, push devices, AgentRuns, deliverables,
managed hosts, operational actions, database-refresh runs and API usage.
The closed fixture WorkSession supplies persisted browser-visible state without
claiming an active managed WorkSession, allocation, route or AgentRun.

The operator password, browser password, JWT secret and PostgreSQL password
exist only as four named secret references. The contract contains no secret
value. The future fixture loader must BCrypt-hash the operator secret, require
the browser and bootstrap password references to resolve equally, behave
idempotently on an exact match and fail on any conflicting pre-existing row.

Seven non-secret SQL audit checks prove the Flyway ranks and failures, the
synthetic operator/project/session/turn identities, absence of active sessions
or runs, and absence of operational/external records. The inventory also
declares exact per-table baseline counts so task 4.3 can show every created
row, rather than only report that startup succeeded.

### Explicit denials

The contract fails closed before connection, read, copy, startup, network
access or mutation for:

- production dumps, volumes, snapshots, backups, rows, hosts, databases,
  endpoints and production or preview credentials;
- real OpenAI, DeepSeek, FCM, Codex/ChatGPT authentication and any other
  external integration traffic;
- productive GitHub credentials or GitHub API access from the application
  runtime (canonical-source fetch remains outside that runtime);
- managed hosts, services, websites, monitoring, incidents, deployments,
  promotions, rollbacks, backups, restores and other production operations;
- every undeclared host, database, volume, fixture, endpoint or literal
  secret.

OpenAI transcription and costs, DeepSeek intent/briefing/costs and FCM are
required disabled. GitHub runtime access and external Codex authentication are
credentialless and network-denied. No managed-host or integration record is
seeded.

### Isolated validation

Validation ran from `/tmp` and did not use or query production data. A
temporary local container named `codex-atenea-pg16-synthetic-v1` used
PostgreSQL `16.11` and a new volume named
`codex_atenea_pg16_synthetic_v1`. Flyway `11.7.2` reported:

- 45 migrations validated and 45 applied;
- empty starting schema and final version V45;
- 45 successful history rows and zero failures;
- 29 public tables including `flyway_schema_history`;
- zero legacy `task`/`task_execution` tables;
- zero domain rows before fixtures.

The same disposable database then received the four declared representative
fixture groups solely to validate the contract itself. All 28 per-table
baseline counts matched, and the seven audit queries returned respectively
`45/1/45/0`, `1`, `1`, `1`, `2`, `0` and `0`. The placeholder BCrypt-shaped
test field was synthetic and was neither a usable credential nor retained.

The container and volume were removed by the validation trap, and a separate
post-check found neither resource. JSON parsing, all 45 SHA-256 checks,
per-table inventory cardinality and denial/integration cardinality checks also
passed. This task defines the fixture and its proof contract; it does not
implement or apply the future task 4.3 fixture loader.

### Post-change non-impact and differences

At the post-change check, Atenea production health remained `UP`; the same nine
relevant production/preview containers were running for five weeks with zero
unhealthy containers, and seven older stopped development/legacy containers
remained stopped. There were zero AX42/remote-worker routing environment keys
and zero routing matches outside the two declared Atenea contracts. The
runtime manifest hash remained
`3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3`.

AX42 reproduced the strict verifier hash
`fbb8ba4b76540fed5fb1ecb84649587a2ea2ebedc593041a8c4daa956da2326e`,
zero mirrors, absent session/admission roots, four active rootless socket
proxies, rootful Docker/containerd masks, an empty Docker group, three healthy
`[UU]` arrays, zero Playwright/Chromium processes and `No serve config`.
Slots remained `3/3`, `0/4`, `0/0`, `0/0` for containers/images; the slot 2
image-ID digest remained
`f389d24080182c482f962877dfb45eb9975b34a4b4efe7df2a02f20ea927bebe`.
Beautips remained clean and synchronized at
`5044a3b07b3db82895e9c8ff47bc4bc9b0e97130`, with its application,
PostgreSQL and Redis containers `UP`.

The only intended state difference is Atenea's second authorized local commit:
local HEAD moved from `0c1bbd3a8c9ca580ec816d604ad7a84b27cea2af` to
`7dffd34b6e3d49e2fc429ab7537928b1ef644562` and is now two commits ahead of
the unchanged remote. No programme commit or push occurred, and no Atenea
mirror, managed WorkSession, allocation, runtime or route was created on AX42.

## Task 2.1 — allowlisted Atenea worker Compose definition

Accepted on 2026-07-27. Atenea commit
`7cc003dba3b931e5d4769c507d65983d377a3222` adds only
`ops/worker/docker-compose.ax42.yml` above the task 1.3 commit. Its SHA-256 is
`807950e93c383607fb1951abfc610837ccc8e071d63dc0a02b24dac78f4274bb`.
The Atenea worktree and index are clean and the branch is intentionally not
pushed: local Atenea is three commits ahead while
`origin/feature/actualizar-conversacion-en-web` remains at
`a9fe14989544308acc587e3eb71cb985fa637b2d`.

### Reviewed boundary

The review covered the active proposal, design, both capability deltas and all
tasks; evidence for 1.1 through 1.3; the committed runtime manifest and
synthetic data/migration inventories; the control-plane development and edge
Compose definitions; both development Dockerfiles, the application Dockerfile,
build/run/test/web scripts and Spring PostgreSQL/Flyway configuration; the
archived runtime contract, schema and valid/invalid fixtures; and the existing
manager, engine and their tests. Manager, engine, admission, cleanup and `dev`
were read only and were not modified.

The existing `docker-compose.dev.yml` was rejected as an implementation source
because it contains fixed container names, host ports, global volumes,
control-plane repository/context/home mounts and permissive integration
defaults. The new definition does not extend or include it.

### Definition and fail-closed inputs

The Compose definition declares exactly `db`, `codex-app-server` and
`atenea-dev`. It is deliberately not operator-runnable. Eighteen required
`ATENEA_SESSION_*` inputs must be supplied by the future allowlisted adapter:
the Compose project, three reviewed images, synthetic database identity, three
internal ports, WorkSession worktree, four session-owned cache/data paths,
network, new PostgreSQL volume and two named secret-file paths.

There is no `container_name` and no `ports` key. Compose derives container and
secret identities from the injected session project; the network and volume
names are injected session identities. The single network is `internal`, and
only the three manifest-declared internal ports are exposed to that network.
No port binds to a host interface.

The only bind mounts are the injected WorkSession worktree and its injected
Codex, Maven, Node and upload cache/data paths. PostgreSQL uses the injected
new session volume. There is no control-plane repository, global context,
home, host root, daemon socket, device or host namespace mount.

All three services use a read-only container root filesystem, drop every
capability, set `no-new-privileges`, bound PIDs and use restart policy `no`.
The Compose contains no privileged mode, devices, host networking, host
PID/IPC, extra hosts or daemon socket.

PostgreSQL receives its password only through a named Compose secret file.
Spring receives the PostgreSQL and JWT values only through config-tree secret
files. No secret value, production credential or browser credential is in the
definition. Operator bootstrap remains disabled, so this task does not
implement or apply the fixture loader reserved for 4.3.

OpenAI transcription/costs, DeepSeek routing/briefing/costs and FCM are
explicitly disabled. Their endpoints and the GitHub runtime endpoint are
closed to container loopback port 9. Codex external authentication is disabled
and the internal network prevents external egress. No managed-host,
deployment, backup, restore or other production authority is mounted or
credentialed.

### Static validation from `/tmp`

No `docker compose up`, image build, pull or daemon-mutating command ran.
Validation used empty temporary secret files and synthetic `/tmp` paths only;
the cleanup trap removed them.

On the control plane, Docker Compose `5.1.4` resolved the exact file from
`/tmp`. Each of the 18 required inputs was omitted separately and all 18
attempts failed before resolution with an actionable required-input error. A
fully supplied synthetic resolution then proved:

- services are exactly `atenea-dev`, `codex-app-server` and `db`;
- no container name, host port, privileged mode, device, host namespace,
  extra host, daemon socket or undeclared mount resolves;
- the network is internal and project/network/volume identities match the
  injected full synthetic WorkSession runtime identity;
- PostgreSQL is major 16, the database name/user and internal ports match the
  runtime manifest and data inventory;
- every integration state and closed endpoint matches the task 1.3 denials;
- a deliberately present `docker-compose.dev.yml` with a forbidden extra
  service was ignored even when `COMPOSE_FILE` pointed to it, because the AX42
  definition was selected explicitly.

AX42 independently resolved the same Compose from `/tmp` with the declared
Docker Compose `5.3.1`. Missing inputs failed safely, the successful result had
the same three exact services, no host ports or fixed container names, and an
internal network. Static resolution left the rootless slots at their prior
`3/3`, `0/4`, `0/0`, `0/0` container/image counts; no container, image,
network, volume, path, WorkSession, allocation, route or secret was created.

The runtime manifest, data inventory and migration inventory hashes remained,
respectively,
`3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3`,
`ae757a4befde1ba02fca03abc205779d5a8f919d121c8c14bc02a24cc755046d`
and
`360203affcb287b446a106651602affe11f6f60d0523283825d9a403d449a9ff`.

### Post-change non-impact and observed differences

Atenea production remained `UP`. The same nine production/preview containers
were running with zero unhealthy containers and the seven previously
documented stopped development/legacy containers remained stopped. Running
container environments contained zero AX42/remote-worker routing keys, and
application/deploy/docker/script source contained zero worker host, tailnet or
routing endpoint matches.

AX42 reproduced the strict verifier result `true`, all `13/13` checks and the
same verifier JSON SHA-256
`fbb8ba4b76540fed5fb1ecb84649587a2ea2ebedc593041a8c4daa956da2326e`.
All four rootless proxy sockets were enabled and active; rootful Docker,
`docker.socket` and containerd remained inactive and masked; the Docker group
remained empty; all three RAID arrays were `[UU]`; there were zero mirrors,
no session workspace/admission root, zero browser processes and
`No serve config`. Slot 2 retained the same four-image digest
`f389d24080182c482f962877dfb45eb9975b34a4b4efe7df2a02f20ea927bebe`.

Beautips remained clean and synchronized at
`5044a3b07b3db82895e9c8ff47bc4bc9b0e97130`. Its application returned `UP`;
application, PostgreSQL and Redis remained running on worker loopback, with
PostgreSQL and Redis healthy. The administrative `dev` SHA-256 remained
`db58c7ac7e2dc71fab0d7ef6a04591236ec34ff223b0ae52951e3538aa6234d5`.

The only intended durable difference is Atenea's one authorized task 2.1
commit: HEAD moved from `7dffd34b6e3d49e2fc429ab7537928b1ef644562`
to `7cc003dba3b931e5d4769c507d65983d377a3222`, changing only the new Compose
file and increasing the unchanged remote lead from two to three commits.
Operational command discovery also confirmed that the canonical verifier is
not installed globally and was therefore copied unchanged to `/tmp` for this
read-only check, while the actual proxy units are named
`atenea-docker-proxy-slot1.socket` through `slot4.socket`. These are command
location/name clarifications, not baseline drift. No service or host was
restarted, no push occurred and task 2.2 was not started.

## Task 2.2 — exact Atenea manager and engine allowlist

Accepted on 2026-07-27 without activating a runtime. This task changed only
the programme repository:

- `ops/worker/runtime-manager-v1.sh`, SHA-256
  `95636a6212b28abc467644f9aba96b1b510533ec1dd43fa79ee30f17150ec2e3`;
- `ops/worker/runtime-engine-v1.sh`, SHA-256
  `57855699a794c59f6c45124170b2f4e3e38f1084dcb7a912281640b5f273827f`;
- `ops/worker/test-atenea-runtime-adapter-v1.sh`, SHA-256
  `3efdebdf45e70912c9483efa66a397d3d3c25585e788a99dff4515193b5d2d9e`.

No file in the Atenea repository changed. Its worktree remains clean at local
commit `7cc003dba3b931e5d4769c507d65983d377a3222`, exactly three commits above
the unchanged upstream
`a9fe14989544308acc587e3eb71cb985fa637b2d`.

### Manager boundary

The manager retains the existing root/caller checks and the two synthetic
normal-workload fixtures. It adds one separate default-deny branch only when
the persisted project is exactly `atenea`. That branch requires:

- a canonical lowercase WorkSession UUID and full
  `ws-<32-lowercase-hex>` runtime identity;
- the exact manifest path `ops/atenea-runtime.json` and SHA-256
  `3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3`;
- the exact Compose path `ops/worker/docker-compose.ax42.yml` and SHA-256
  `807950e93c383607fb1951abfc610837ccc8e071d63dc0a02b24dac78f4274bb`;
- an `allocated` heavy allocation with one `slot1`–`slot4` and one
  `heavy1`–`heavy2` permit;
- a separate persisted admission record whose held normal slot and heavy
  permit exactly equal the allocation for that WorkSession;
- deterministic worktree, runtime, log, artifact and cache roots, including
  owned Codex, Maven, Node, upload-data and secret-reference paths;
- the exact internal-port set `postgres/tcp/5432`, `codex/tcp/8092` and
  `web/http/8081`, each with a unique loopback allocation bound to
  `127.0.0.1`.

The existing full schema-level semantic validation still runs after those
identity and hash checks. Unsupported manifest fields therefore remain
default-denied. The manager obtains an independent engine inspection, verifies
the complete result and emits a closed plan. Lifecycle and browser `argv`
arrays from the manifest are never copied to that plan or executed by the
manager.

The plan contains only the exact bind-mount source allowlist and the nominal
secret references `ATENEA_DEV_POSTGRES_PASSWORD` and
`ATENEA_DEV_JWT_SECRET`. It never reads, embeds, prints or logs their values.
The two secret-file inputs are accepted only as regular session-owned mode
`0600` paths beneath the deterministic runtime root.

### Engine inspection and closed plan

The engine independently rechecks the exact manifest and Compose hashes,
allocation shape, canonical UUID, heavy slot/permit, paths, resource names and
three loopback allocations. Its Atenea inspection is also the generated
Compose plan and contains exactly:

| Service | Fixed image allowlist | Internal port | Owned mounts |
|---|---|---:|---|
| `db` | PostgreSQL 16 image `sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20` | `5432/tcp` | session database volume only |
| `codex-app-server` | pinned Node 22 base `sha256:048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34` | `8092/tcp` | worktree and session Codex cache |
| `atenea-dev` | pinned Maven 3.9.9/Java 21 image `sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e` | `8081/http` | worktree, Maven cache, Node cache and upload data |

The Node image is only the fixed base identity for the declared Codex service
at this gate. Task 4.1 still must prove the complete Codex executable and
toolchain image before any lifecycle activation; task 2.2 does not claim that
runtime verification.

Compose project, internal network, database volume and all three container
names derive from the full runtime ID. Every service entry has exactly the
five labels `com.atenea.engine`, `com.atenea.session`,
`com.atenea.runtime`, `com.atenea.project` and `com.atenea.service`.
The shared internal network uses service label `runtime`; the database volume
uses service label `db`. No unlabelled, partially labelled, foreign or
ambiguous resource is accepted by the plan.

The plan is bound to the SHA-256 of the exact allocation record as well as the
manifest and Compose hashes. The real `execute` path revalidates the complete
plan but then deliberately returns `OPERATION_FAILED` before creating an
engine root, resolving a socket or issuing a Docker command. Thus the manager
and engine now recognize the reviewed Atenea plan, while lifecycle activation
remains explicitly deferred.

### Isolated validation

Validation used only synthetic directories beneath `/tmp`. Empty mode `0600`
secret-reference files represented the ownership boundary; no secret value
was generated or supplied.

On the Atenea control plane, an isolated `/tmp` copy passed:

- Bash syntax for manager, engine and the new test;
- `test-atenea-runtime-adapter-v1.sh`;
- the existing `test-runtime-manager-v1.sh`;
- the existing `test-runtime-engine-v1.sh`.

AX42 independently passed the new positive adapter test from `/tmp`. The
test's Docker executable is a fail-on-call recorder. It remained unused while
the real engine:

- inspected all three Atenea services;
- produced the exact mounts, images, loopback ports, network, volume, slot,
  full-runtime identities and labels;
- accepted the manager-generated closed plan;
- rejected lifecycle execution before daemon access.

The same focused test proved that a changed manifest hash, changed Compose
hash, path outside the synthetic session and admission/allocation slot
ambiguity fail before the engine or daemon as applicable. These are only the
minimum task 2.2 guards; the dedicated task 2.3 corpus is recorded below.

The captured plan contained no manifest `argv` and no secret value. The test
left no plan, secret fixture, session directory or Docker state behind.

### Non-impact and observed differences

After validation, the four AX42 slots remained `3/3`, `0/4`, `0/0`, `0/0`
for containers/images, with zero `ws-*` networks and volumes. Repository
mirrors remained zero; `/srv/atenea/workspaces/sessions` and
`/srv/atenea/worker/runtime-admission-v1` remained absent. Rootful Docker,
its socket and containerd remained inactive and masked, and Tailscale still
reported `No serve config`.

Beautips application, PostgreSQL and Redis remained running on slot 1;
PostgreSQL and Redis remained healthy and the application health endpoint
returned `UP`. Atenea production and preview health endpoints both returned
`UP`; the same nine production/preview containers remained running, backend
restart counts remained zero and running environments contained zero
AX42/remote-worker routing keys.

The only intended differences are the two modified programme runtime scripts,
the new focused adapter test, this evidence, the task 2.2 checkbox and the
programme resume point. No mirror, real WorkSession, persisted allocation,
runtime path, image, network, volume, container, route, fixture loader or
AgentRun routing was created. No service or host was restarted, no Atenea
commit was added, and no commit or push occurred in either repository.

## Task 2.3 — negative Atenea runtime policy corpus

Accepted on 2026-07-27 without changing manager or engine implementation and
without activating a runtime. This task adds only
`ops/worker/test-atenea-runtime-policy-negative-v1.sh`, SHA-256
`7da8cf17036dde1352be41e62b8f339638776672cb3d6cb54bba8bd865e6a2c5`.

The test constructs one entirely synthetic WorkSession, workspace, heavy
allocation, admission record, owned path set and empty mode `0600` named
secret-reference files beneath `/tmp`. It first captures the exact closed
Atenea plan through the real manager and real engine inspection. A wrapper
then changes one inspected field at a time and proves that the manager rejects
every changed identity before asking the engine to execute:

- `/var/run/docker.sock`;
- privileged mode and host network, PID or IPC namespaces;
- `/dev/kvm`;
- the undeclared host mount `/etc`;
- fixed container, Compose project, network and volume names;
- a foreign runtime resource;
- missing, partial or foreign session labels;
- a duplicate service that makes the resolved resource set ambiguous.

The corpus separately changes the same authority and ownership classes in the
captured closed plan and passes each plan directly to the real engine. The
engine independently rebuilds the expected adapter and rejects every mismatch
with `RUNTIME_OWNERSHIP_CONFLICT` before resolving a slot socket or calling
Docker. Ten semantically unsafe Compose variants cover a daemon-socket mount,
privileged mode, all three host namespaces, a device, an undeclared mount and
fixed container/network/volume identities; the exact reviewed Compose hash
rejects every variant as `MANIFEST_INVALID`.

The fake Docker executable is a fail-on-call recorder. Its log remained empty
through all manager, engine-plan and Compose-input cases. The manager execute
count also remained unchanged for every rejected inspection. Temporary
manager plans were removed, no manifest `argv` ran and no literal secret
assignment was present in the fixture or output.

### Isolated validation

The new corpus and Bash syntax passed from isolated `/tmp` copies on both the
Atenea control plane and AX42. In the local portable copy, the focused positive
Atenea adapter test and the existing manager and engine suites also passed
sequentially. The broader contract, allocation, admission, cleanup and
lifecycle suites were deliberately not run; that complete regression gate
remains task 2.4.

OpenSpec `1.5.0` is available on the operator laptop through the NVM-managed
login-shell path
`/home/jose/.nvm/versions/node/v22.16.0/bin/openspec`. An initial probe of the
unrelated fixed path `/home/jose/.local/bin/openspec` failed and was
incorrectly interpreted as a missing local installation; login-shell command
discovery corrected that diagnosis. Strict validation still ran in the
canonical Atenea repository and passed. A broad command-line browser-process
search on AX42 also matched its own audit shell; an exact executable-name check
confirmed zero Chromium or Chrome processes.

No runtime file was installed globally. No mirror, real WorkSession,
allocation, admission record, runtime directory, image, container, network,
volume, route or browser process was created. Both remote validation
directories were removed after the checks.

## Task 2.4 — complete local runtime regression gate

Accepted on 2026-07-27 from one isolated portable programme copy beneath
`/tmp`. No project or runtime was activated. The gate ran the seven canonical
runtime suites sequentially, avoiding fixed-port overlap:

| Suite | SHA-256 | Result |
|---|---|---|
| `test-project-runtime-contract-v1.sh` | `6da4fa06622a3df3e49c5b48ae2fc8ceb5635d7b0fab84cbbe4fd6d665c4492e` | `8/8`, pass |
| `test-session-runtime-allocation-v1.sh` | `208c73be039110fb80656897bb09acdbb86af25addb4741a97d7cc714d217909` | pass |
| `test-dev-session-v1.sh` | `572ff2c80d466c048f05dc9d304294ffab27d7e8fda8592ab6c96edbef85605c` | pass |
| `test-runtime-manager-v1.sh` | `4e77070cbbbd5ca913297d7beb035d85af34bda22fcb64e64e0955fa8049a9cc` | pass |
| `test-runtime-engine-v1.sh` | `63830dfe9eaa3180cde0743311c1ac9aefcf1c1b71f070a8bb5c61807bccea46` | pass |
| `test-runtime-admission-v1.sh` | `e8b1f12768f646cd79f45c7c7d82adbbee702e174d2a3b2a07ec1f94e3383232` | pass |
| `test-project-runtime-health-browser-cleanup-v1.sh` | `cff656808b5eb2804389d0c2ad1099458cda93fd72243c8f4adb6f6f277c343b` | pass |

The focused positive and negative Atenea adapter suites also passed at hashes
`3efdebdf45e70912c9483efa66a397d3d3c25585e788a99dff4515193b5d2d9e`
and
`7da8cf17036dde1352be41e62b8f339638776672cb3d6cb54bba8bd865e6a2c5`,
respectively. Bash syntax passed for all nine scripts.

### Protected-hash reconciliation

The integrated contract suite initially stopped before its first case because
its protected hash map still named the manager and engine versions archived
before task 2.2. It reported the current engine hash and created no resource.
The test was changed only to expect the exact approved manager SHA-256
`95636a6212b28abc467644f9aba96b1b510533ec1dd43fa79ee30f17150ec2e3`
and engine SHA-256
`57855699a794c59f6c45124170b2f4e3e38f1084dcb7a912281640b5f273827f`.
No other protected input or assertion changed. The corrected integrated suite
then passed schema corpus, workspace boundary, allocation, `dev`, manager,
engine, admission and integrated-capacity cases (`8/8`).

### Synthetic lifecycle and cleanup evidence

The health/browser/retention/cleanup regression used the laptop's
NVM-managed Playwright `1.60.0` through the required temporary safe runner.
It generated exactly four temporary screenshots for the two fixed fixtures at
desktop and mobile sizes, registered them deterministically with registry
SHA-256
`020f059834eb0a9e8aac3fee60c21b7dce849a415e3e6cce506363d23a5bd01e`,
and proved idempotent cleanup plus foreign/partial-label denial. These are
synthetic contract artifacts, not Atenea UI acceptance evidence; task 5.2 and
5.3 remain pending.

All test roots were beneath `/tmp`. The suites used fake Docker or fixed
synthetic adapters, loopback listeners and synthetic records only. They left
zero suite test roots, Playwright children or controlled resource state.
The Chrome processes already running in the operator's normal desktop profile
were unrelated and were not touched.

No mirror, real WorkSession, persisted allocation, admission record, runtime
path, image, container, network, volume, secret, route or service was created
on AX42. No production service was restarted or reconfigured, no manifest
`argv` was executed directly and no literal secret value was retained.

## Task 3.1 — canonical GitHub mirror and ancestry gate

Accepted on 2026-07-27 after a direct GitHub fetch on AX42. This task created
only the canonical Atenea bare mirror. It did not create a WorkSession,
worktree, allocation, admission record, runtime root, log, artifact, cache,
container, image, network, volume, fixture or route, and it did not start,
restart or reconfigure any service.

### Contract and source selection

The archived workspace contract and its versioned implementation define:

- mirror root `/srv/atenea/repositories`;
- project mirror `/srv/atenea/repositories/atenea.git`;
- service identity `atenea-worker:atenea` and setgid mode `2770`;
- a bare repository with canonical branches fetched only through
  `+refs/heads/*:refs/remotes/origin/*`;
- one credential-free HTTPS GitHub `origin`, with GitHub remaining canonical.

`session-workspace-v1.sh` was reviewed in full and its staged AX42 hashes match
the programme copy, but its only production operation also creates a
WorkSession record and worktree. Because task 3.1 expressly forbids both, the
mirror-only portion of its sequence was applied directly as `atenea-worker`:
initialize a temporary bare repository beneath the canonical mirror root, add
the approved origin, configure the exact fetch mapping, fetch from GitHub,
validate the selected ref and ancestry, and atomically move the validated
repository into its canonical path. No source path on the control plane was
an input.

Before creation, `/srv/atenea/repositories` was an empty
`atenea-worker:atenea` mode `2770` directory. The service identity could query
the approved GitHub branch directly with `GIT_TERMINAL_PROMPT=0`; no
credential file, helper change or token-bearing URL was required or created.

### Mirror identity and ancestry result

| Check | Result |
|---|---|
| Capture time | `2026-07-27T18:38:36Z` |
| Canonical path | `/srv/atenea/repositories/atenea.git` |
| Owner/group/mode | `atenea-worker:atenea`, `2770` |
| Git repository type | bare |
| Configured remotes | exactly one, `origin` |
| Normalized origin identity | `github.com/jlnieto/atenea.git` |
| Origin URL SHA-256 | `67198bc7c082ef62555dfa69e4ebb7644feacebb1ca17f6f0f1b3848dd12dbf3` |
| Fetch mapping | `+refs/heads/*:refs/remotes/origin/*` |
| `remote.origin.mirror` | `false` |
| Selected branch | `feature/actualizar-conversacion-en-web` |
| Selected remote tip | `a9fe14989544308acc587e3eb71cb985fa637b2d` |
| Accepted entry commit | `a9fe14989544308acc587e3eb71cb985fa637b2d` |
| Accepted entry is ancestor | yes |
| Tip relation to accepted entry | exactly equal |

A second `git fetch --prune origin` reproduced the same selected tip and the
ancestry check passed. The mirror has no alternates file, so its object store
does not refer to a local or control-plane repository. Its only remote URL is
the exact approved HTTPS identity, contains no userinfo, query or fragment,
and a non-secret scan found zero token/password-shaped configured values and
zero credential-named files.

The three authorized but unpublished control-plane commits are absent from the
mirror object database:

- `0c1bbd3a8c9ca580ec816d604ad7a84b27cea2af`;
- `7dffd34b6e3d49e2fc429ab7537928b1ef644562`;
- `7cc003dba3b931e5d4769c507d65983d377a3222`.

This proves the mirror was reconstructed from GitHub rather than copied from
the Atenea control-plane worktree. The control-plane repository remained
clean at `7cc003dba3b931e5d4769c507d65983d377a3222`, exactly three commits ahead
of its unchanged upstream
`a9fe14989544308acc587e3eb71cb985fa637b2d`; no commit was published.

### Isolation and non-impact validation

After mirror creation:

- `/srv/atenea/workspaces/sessions` and
  `/srv/atenea/worker/runtime-admission-v1` remained absent;
- workspace and allocation record counts remained zero;
- there were zero runtime paths and rootless networks or volumes named
  `ws-*`;
- slot container/image counts remained `3/3`, `0/4`, `0/0`, `0/0`;
- rootful Docker service, socket and containerd remained inactive and masked;
- Tailscale still reported `No serve config`;
- Chromium/Chrome, Git fetch/clone and temporary mirror process/path counts
  returned to zero;
- Beautips application health remained `UP`; application, PostgreSQL and
  Redis remained running, with PostgreSQL and Redis healthy;
- Atenea production and preview health remained `UP`, all nine relevant
  containers remained running, and running container environments contained
  zero AX42/remote-worker routing keys.

The only intended AX42 state difference is one canonical GitHub-backed Atenea
mirror. Task 3.2 remains unchecked and no lifecycle, manager, engine, client,
allocation or admission operation was invoked.

## Task 3.2 — heavy administrative WorkSession and owned allocation

Accepted on 2026-07-27 after explicit operator authorization to publish the
three reviewed Atenea implementation commits and proceed with the heavy
WorkSession. This task created workspace, admission, allocation and declared
empty path state only. It did not invoke manager, engine, client or lifecycle;
start a process, listener, service or container; create an image, network or
volume; resolve a secret value; or enable a route.

### Canonical source publication

Immediately before publication, a fresh fetch proved that GitHub still pointed
to accepted entry commit
`a9fe14989544308acc587e3eb71cb985fa637b2d`, Atenea was clean at
`7cc003dba3b931e5d4769c507d65983d377a3222`, and the range contained exactly
the three previously reviewed task commits. The explicit push advanced only
`feature/actualizar-conversacion-en-web` from the accepted entry commit to
`7cc003dba3b931e5d4769c507d65983d377a3222`.

The control-plane Atenea worktree is now clean and synchronized at that commit.
The AX42 mirror fetched the same GitHub tip, and the accepted entry remains its
ancestor. No session branch was published.

### Versioned allocation corrections

Real preflight exposed three gaps that were corrected in the existing
versioned tools instead of bypassing them:

1. `session-runtime-allocation-v1.sh` accepted only `normal` manifests even
   though the v1 schema already defines `heavyPermit` and the Atenea manager
   requires an admitted heavy allocation. It now accepts `heavy` only for
   project `atenea`, validates the exact worker-owned admission record, binds
   its held slot and permit, and persists `heavyPermit`.
2. Admission would have selected `slot1` because its records did not represent
   the existing administrative Beautips containers. `acquire-normal` now
   accepts a backward-compatible optional requested slot, allowing the
   preflight-proven empty `slot2` to be granted without touching Beautips.
3. Intermediate session, cache, artifact, runtime-data and workspace
   collection directories inherited mode `2755` before their children were
   set to `2770`. The workspace and allocator helpers now materialize and
   normalize those worker-owned parents explicitly to mode `2770`.

Current programme and staged AX42 hashes are:

| File | SHA-256 |
|---|---|
| `session-workspace-v1.sh` | `4ae5f87637cdbd7b8779350acc9bb5ad562a653dc9b54af9fa0ee71faaa9144b` |
| `test-session-workspace-v1.sh` | `ebd8f3b04264d0bc8acf8b0692b64288d0d4d0c313f347150a47c7f71de6eba5` |
| `runtime-admission-v1.sh` | `a81366d3495bb2a7bf4702e9ea934a74e9b3edb30f728926e655a5c0a6a9f7ce` |
| `test-runtime-admission-v1.sh` | `aaa1b37d2dfc9d5eefecd7f9128b724bd8db69e138a719b977acec2d21bcaa86` |
| `session-runtime-allocation-v1.sh` | `2efceeaaba78b349f1d6aa79bfba5d908d397a9e3a480cfa3b100bde52fb99d7` |
| `test-session-runtime-allocation-v1.sh` | `d57cd5593e5a90ee54b0d26125e6f20dbd11b1bab76b0fce5918fea1e5bc963b` |
| `test-project-runtime-contract-v1.sh` | `fc9c84c0e9fd2fae0603e5dca8ff72164404a3eff113809b3980646adb56d78f` |

The focused workspace, allocation and admission suites passed locally and as
`atenea-worker` on AX42. The integrated contract suite passed `8/8` after
each final correction. Both Atenea adapter suites also passed against a
temporary checkout cloned directly from GitHub at `7cc003d...`; their
temporary source and test roots were removed.

### Persisted WorkSession identity

| Field | Value |
|---|---|
| Administrative WorkSession | `41c0ff95-e555-4773-b7b4-60903a3af1ad` |
| Runtime identity | `ws-41c0ff95e5554773b7b460903a3af1ad` |
| Normal slot | `slot2`, held |
| Heavy permit | `heavy1`, held |
| Project | `atenea` |
| Base branch | `feature/actualizar-conversacion-en-web` |
| Session branch | `atenea/session-41c0ff95-e555-4773-b7b4-60903a3af1ad` |
| Worktree HEAD | `7cc003dba3b931e5d4769c507d65983d377a3222` |
| Worktree | `/srv/atenea/workspaces/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/atenea` |
| Manifest | `ops/atenea-runtime.json` |
| Codex loopback allocation | `127.0.0.1:22667` → `8092/tcp` |
| PostgreSQL loopback allocation | `127.0.0.1:28541` → `5432/tcp` |
| Web loopback allocation | `127.0.0.1:22359` → `8081/http` |

The worktree is clean, its Git common directory is the canonical AX42 mirror,
and its HEAD equals the fetched GitHub branch tip. The runtime manifest,
synthetic data inventory, migration inventory and Compose hashes reproduce,
respectively:

- `3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3`;
- `ae757a4befde1ba02fca03abc205779d5a8f919d121c8c14bc02a24cc755046d`;
- `360203affcb287b446a106651602affe11f6f60d0523283825d9a403d449a9ff`;
- `807950e93c383607fb1951abfc610837ccc8e071d63dc0a02b24dac78f4274bb`.

The workspace and allocation records are mode `0640`; the named PostgreSQL and
JWT reference files are mode `0600`, empty and contain no credential. All
session-owned directory roots are `atenea-worker:atenea`, mode `2770`.
Repeating workspace and allocation returned byte-identical records and did not
change the clean worktree.

### Non-impact and remaining boundary

Admission reports ready capacity at `1/4` normal and `1/2` heavy. Slot
container/image counts remain `3/3`, `0/4`, `0/0`, `0/0`; all rootless slots
have zero `ws-*` networks and volumes. The three allocated loopback ports have
zero listeners, and there is no session service unit or manager, engine or
client process. Rootful Docker, its socket and containerd remain inactive and
masked; Tailscale reports `No serve config`; Beautips remains `UP`.

Atenea production and preview remain `UP`, their running environments contain
zero AX42/remote-worker routing keys, and no production service was restarted
or changed. Task 3.3 remains unchecked: this task records the GitHub-derived
worktree relationship but does not claim the separate dependency/real-
WorkSession proof required by 3.3.

## Task 3.3 — accepted source and administrative-session isolation

Accepted on 2026-07-27 after the default-deny proof first exposed and then,
with explicit commit/push authorization, eliminated one undeclared effective
upload path. Task 4.1 was not started.

### Narrow correction and publication

The first read-only pass found that
`ops/worker/docker-compose.ax42.yml` mounted the owned upload data at
`/workspace/data/uploads` but did not set `ATENEA_MOBILE_UPLOAD_ROOT`; the
application would therefore use its legacy internal default instead of the
declared bind.

Atenea commit `b6dc854d94ba5b1976926656c9a6aba330f671e2` adds only:

```yaml
ATENEA_MOBILE_UPLOAD_ROOT: /workspace/data/uploads
```

The commit was published on `feature/actualizar-conversacion-en-web`.
A credential-disabled direct GitHub query returned that exact tip. The
administrative session branch remains unpublished.

The corrected Compose SHA-256 is
`2133646b9fe6227ca417d6d62c92a74306caaa46a2957cdee810d5d7b0e5bb9f`.
Manager and engine now pin that exact hash; their new SHA-256 values are
`902f25ad76c08ebbc2235cc4b06e2fe0fd94938a5ac2000a2151a27808bbcbd5`
and
`617327df412fdee18b879295359bc000431f0bc686623b98e06c41b5ec93e6e3`.
Compose static resolution passed with all 18 required non-secret inputs. Both
the positive adapter and negative policy corpus passed against a fresh
GitHub checkout beneath `/tmp`; they used only synthetic records, a fake
Docker client and rejection before daemon/lifecycle access. Their temporary
roots were removed.

### Administrative worktree reconciliation

The AX42 mirror fetched the published branch and the existing clean session
branch was fast-forwarded from `7cc003d...` to `b6dc854...`. The workspace
helper then persisted only the new `headCommit`; the original
`expectedBaseCommit` remains `7cc003d...`, preserving the session's accepted
creation base.

The allocation and admission record hashes remained byte-identical:

- allocation:
  `c4e45ac8ff834d68cddd385ec95699702b1df0ec7d574154b6dc8654fd592f13`;
- admission:
  `20c7a409e741eb73b4847a177c98b69128da7b07b6a9ac5ff7b7e1af1413a29d`.

No new WorkSession, worktree, allocation, admission permit or route was
created. No real production WorkSession or PostgreSQL row was queried or used
for authority.

### Accepted read-only proof

The final proof ran as `atenea-worker` on `codex-worker-01` and established:

- Git common directory is exactly `/srv/atenea/repositories/atenea.git`;
- worktree HEAD is `b6dc854d94ba5b1976926656c9a6aba330f671e2`,
  tree is `f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`, the index/worktree are
  clean and there are no submodule gitlinks;
- the bare mirror has only the approved credential-free GitHub `origin`,
  canonical fetch mapping and no push URL, alternates, grafts, shallow state,
  replacement refs, promisor/partial-clone configuration, credential material
  or Git environment redirection;
- `git fsck --full --strict --no-dangling` passes, proving the object database
  is self-contained;
- GitHub publishes the exact selected commit and does not publish
  `atenea/session-41c0ff95-e555-4773-b7b4-60903a3af1ad`;
- workspace, allocation and admission records contain only the fixed
  administrative session identity, AX42 paths, `slot2` and `heavy1`; none
  contains `/srv/atenea/workspace/repos/internal/atenea`;
- manifest, Compose, synthetic-data and migration-inventory hashes are,
  respectively,
  `3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3`,
  `2133646b9fe6227ca417d6d62c92a74306caaa46a2957cdee810d5d7b0e5bb9f`,
  `ae757a4befde1ba02fca03abc205779d5a8f919d121c8c14bc02a24cc755046d`
  and
  `360203affcb287b446a106651602affe11f6f60d0523283825d9a403d449a9ff`;
- all 45 migration entries are repository-relative, resolve inside this
  worktree and match their declared hashes;
- the effective upload root is exactly the target of the owned
  `${ATENEA_SESSION_UPLOAD_DATA}` bind;
- manifest, Compose, data inventory, migration inventory, application config
  and `MobileUploadService` contain no control-plane worktree path;
- the mirror/session tree has no symlink, alternate or bind mount, and neither
  the control-plane path nor the AX42 worktree appears in the mount table.

The proof did not resolve a secret, query production, invoke a real
WorkSession, enable AgentRun routing, or execute lifecycle.

### Byte stability and non-impact

Complete content and metadata fingerprints for the mirror, worktree, entire
administrative session tree, admission record, artifact root and cache root
were identical before and after the accepted proof. The two mode-`0600` named
secret reference files remained empty. Mirror/worktree roots remain
`atenea-worker:atenea` mode `2770`; all three records remain
`atenea-worker:atenea` mode `0640`.

Admission is still `1/4` normal and `1/2` heavy in `slot2/heavy1`. Slot
container/image counts remain `3/3`, `0/4`, `0/0`, `0/0`; all slots have zero
`ws-*` containers, networks and volumes. The allocated ports have zero
listeners, and manager, engine, client, session-unit and AX42 routing process
counts are zero. Rootful Docker, its socket and containerd remain inactive and
masked. Tailscale reports `No serve config`; Beautips application,
PostgreSQL and Redis remain running, with PostgreSQL and Redis healthy.

Atenea is clean and synchronized locally/remotely at `b6dc854...`. All nine
production/preview containers remain running and the sanitized environment-key
scan contains zero AX42/remote-worker routing keys. No temporary process or
fixture remains.

Task 3.3 is complete. OpenSpec progress is `10/27`; task 4.1 is the first
pending task and requires a new explicit continuation.

## Task 4.1 — committed manifest and pinned toolchains

Accepted on 2026-07-27 from a read-only inspection on `codex-worker-01`.
No manifest lifecycle command, Maven, Node, Java, Playwright, Chromium or
Compose workload was executed. No package, wrapper, browser or image was
downloaded, installed, updated or built; no container, network, volume,
listener, runtime process, route, cache entry, log or artifact was created.

### Selected source and manifest identity

The administrative WorkSession remains
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad`, with its unpublished local session
branch. A credential-disabled direct GitHub reference query from AX42 returned
the selected branch at exactly
`b6dc854d94ba5b1976926656c9a6aba330f671e2`.

The WorkSession worktree and mirror prove:

| Identity | Value |
|---|---|
| Worktree HEAD | `b6dc854d94ba5b1976926656c9a6aba330f671e2` |
| Worktree tree | `f8c0dff5c7acf3d82d73885b09f9b1d142b562d2` |
| Manifest Git blob | `f4087e7557c9a45c7f64b14ac2fe8b61c991f89e`, mode `100644` |
| Manifest worktree SHA-256 | `3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3` |
| Manifest Git-blob SHA-256 | `3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3` |
| Git common directory | `/srv/atenea/repositories/atenea.git` |

The byte equality between `git cat-file` at the selected commit and the
WorkSession path proves that the inspected manifest is exactly the published
commit's manifest, not a control-plane copy or an uncommitted replacement.
The mirror's selected remote ref, the direct GitHub ref and the WorkSession
HEAD are identical.

### Versioned schema and lock inputs

The validation used only files already staged on AX42:

| Input | SHA-256 |
|---|---|
| `runtime-contract/project-runtime-v1.schema.json` | `a109476666ea7fd5f0f43ac563766f6ea97c668a921753404fd4544f8a174b2f` |
| `ops/worker/toolchain-lock-v1.sh` | `01885642ebdbc06a3c00985b084eb83aca1c58a968fb847881f8ca0bbb6d5fbe` |
| `ops/worker/install-toolchain-prerequisites.sh` | `bb30c04e0c3ccf14be28d1cb1126c23963ac39fd389e44270d326a99121b7612` |

Those three hashes exactly equal the versioned programme copies. AX42 has no
host-global Python `jsonschema` module, so no missing dependency was installed.
Instead, a dependency-free Python validator read the complete staged Draft
2020-12 schema, rejected unknown active keywords and evaluated every keyword
used by that schema: local references, types, required and additional
properties, constants, enums, patterns, string and numeric bounds, array
bounds and uniqueness, items and `oneOf`. It returned
`draft_2020_12_dependency_free_validation=PASS` for manifest SHA-256
`3b26e189...`.

This validation ran on AX42 against the WorkSession manifest. It did not read
the Atenea control-plane repository, use a control-plane binary or cache, or
copy the manifest from the control plane.

### Exact versions and declared sources

The manifest contains exactly nine unique toolchains and no other source:

| Toolchain | Manifest version | Source | Read-only proof |
|---|---|---|---|
| Git | `2.43.0` | `worker-package` | installed Debian version `1:2.43.0-1ubuntu7.3`, exactly the lock |
| Java build | `21.0.7+6` | `container-image` | Maven image config `JAVA_VERSION=jdk-21.0.7+6` and immutable build history |
| Java runtime | `21.0.7+6` | `container-image` | same accepted Maven/Temurin image identity |
| Maven | `3.9.9` | `container-image` | OCI annotation `3.9.9-eclipse-temurin-21` and immutable history `ARG MAVEN_VERSION=3.9.9` |
| Node | `22.16.0` | `container-image` | image config `NODE_VERSION=22.16.0` |
| Docker | `29.6.2` | `worker-package` | installed held package `5:29.6.2-1~ubuntu.24.04~noble` |
| Compose | `5.3.1` | `worker-package` | installed held plugin `5.3.1-1~ubuntu.24.04~noble` |
| Chromium | `148.0.7778.96` | `container-image` | exact version string in the stored Chromium 1223 binary, read without execution |
| Playwright | `1.60.0` | `container-image` | stored `.docker-info` has driver `1.60.0` and image `v1.60.0-noble` |

There are zero `project-wrapper` toolchains. Every `worker-package` entry is
proved by the existing AX42 dpkg database and every `container-image` entry by
the existing slot 2 OCI content store. No tag was used as authority.

### Immutable image identities

The required existing slot 2 toolchain images match the accepted lock:

| Purpose | OCI index digest | Linux/amd64 manifest | Config digest |
|---|---|---|---|
| Node 22.16.0 | `sha256:048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34` | `sha256:1471ea646673136b8308550ac14b36d847ffb21c24bc31828279e443c924e488` | `sha256:63f353576d0e76c02b332bae8d4ed04b7a99bd6645904072043c117986ef5e7c` |
| Maven 3.9.9 / Java 21.0.7+6 | `sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e` | `sha256:4f991cd094285b7e67afeaab05d21bae63bfc81ac94a7c2527dff13851d3188c` | `sha256:4efe8769cc3208b00c72c06813da9e4f65da0007a474645e1e9ff3f8e59fbf5b` |
| Playwright 1.60.0 / Chromium 148.0.7778.96 | `sha256:9bd26ad900bb5e0f4dee75839e957a89ae89c2b7ab1e76050e559790e946b948` | `sha256:83192064c7510f7ee73dd63dc5f22a5e01a92c81a2e6a9c715d9e3fe55471fd9` | `sha256:ab853f51b03036cd52e74771c2dd07ee1471c408645048e928af67db6beae6d7` |

The fourth pre-existing slot 2 image is the unrelated accepted Java 8/Tomcat
contract image at immutable OCI index
`sha256:e3ca75a4b11560bfb30894c3fa5d066ff0105e2e8e1ad183711df97606321e51`;
it was inventoried but is not an Atenea 4.1 toolchain. Slot 1 also retains the
approved PostgreSQL 16 digest
`sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20`.
It was not copied into slot 2 or used to initialize task 4.3.

### Administrative boundary and non-impact

The records remain `atenea-worker:atenea`, with workspace, allocation and
admission files mode `0640`; the two named secret-reference files remain mode
`0600` and empty. Admission remains held in `slot2/heavy1`, capacity remains
`1/4` normal and `1/2` heavy, and the allocated ports remain without
listeners.

The pre-proof authoritative fingerprints were:

| Root | Content SHA-256 | Metadata SHA-256 |
|---|---|---|
| mirror | `984637300f4b8cd12820dd12dc3522abac3232edb6a127b0d5055ce68c7c5950` | `bcbfcff181ba97f6bc0e35b816ca4c9cbe2c88b7bf6c550b583df61470627ba5` |
| worktree | `95b07c2d13a2648c8b025de3fa1d7473b85982331e3eb949ac664f38e689bfef` | `c83b69638eef60d0c41d2cc5c05ec9c98a28ac6e3f6a3f816995412e51528050` |
| complete session root | `0d68d78f658c35814271d6da286098907ac729c2f12b2abc9030d0277ce0fe70` | `242803aef86144f34974486ad49638298dd060c43553e29780191b1882ada2a2` |
| admission record | `ae0bdfeab7f885a30aa6953efc0f6bb56a8833491dd55e49968c1fb6a0d315e6` | `48b120313fac39418196f3526b9d341ce330ef058944dd8d343a6f5af6e9389e` |
| artifact root | `67d3dac5e148e38319f1f93d8cc9d309ce95f332b50b809755a4c9d07cf5329a` | `8cd316b75d8bac2faf8ed10372852d8c7c4d075a36d0cb618422db3716d3ee37` |
| cache root | `5640f0d6792f271100be2a7ee473fecfd6c6af79c242b38546f51bc387a8e115` | `833a9f45fe2ad90b55391daa344532800633b79fecb83d2b6f00f2794d479262` |

The final comparison reproduced every content and metadata fingerprint
exactly. Slot container/image counts remain `3/3`, `0/4`, `0/0`, `0/0`, with
zero `ws-*` containers, networks and volumes. There are zero manager, engine,
client, lifecycle, session-runtime, Playwright or Chromium processes and zero
allocated-port listeners. Rootful Docker, its socket and containerd remain
inactive and masked; Tailscale still has no Serve configuration.

Atenea production and preview health remain `UP`, Beautips remains `UP`, and
AX42 AgentRun routing remains zero. The Atenea branch remains clean and
synchronized locally/remotely at `b6dc854...`; the programme branch remains at
local/remote `b6f4991...` with a clean index and all pre-existing uncommitted
changes preserved. No commit or push occurred.

Task 4.1 is complete. OpenSpec progress is `11/27`; task 4.2 is the first
pending task and was not started.

## Task 4.2 — reproducible web install, audit and canonical build

Accepted on 2026-07-28 after running the complete web gate in the admitted
AX42 `slot2/heavy1`. Task 4.3 was not started: no PostgreSQL container,
database, migration, fixture, volume or secret was created or resolved.

### Source and execution boundary

The build remained pinned to administrative WorkSession
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad`, commit
`b6dc854d94ba5b1976926656c9a6aba330f671e2` and tree
`f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`. A fresh
`git archive --format=tar HEAD` had SHA-256
`a6f52b2d267750dfb4f8bc9f31d3c0d2434876ddf6517920cb882f19112b5dea`.

The exact build inputs were:

| Input | SHA-256 |
|---|---|
| `web/package.json` | `801cd3c77fc6ba5efef9e232c7c7bca93ce29ea1501e51204e3d30ef0c7bd08a` |
| `web/package-lock.json` | `9650c9fcbb89363e357d7382fecc42dc38fc86349397ce228c8093a8c45d0458` |
| `scripts/web-build.sh` | `afaa847d2171e7ba5a7258384e2501d63945138a21e186fa755c835215ba8f7b` |

The initial read-only mount preflight exposed a lifecycle gate: the rootless
slot daemon, running as `atenea-slot2`, cannot traverse the
`atenea-worker:atenea` mode `2770` worktree ancestors and rejected the bind
before container creation. No ACL, group, mode, owner or allocation was
changed to bypass that boundary.

For this build-only task, the exact Git archive was expanded into a
WorkSession-named scratch beneath `/tmp`, owned only by `atenea-slot2`.
Critical inputs were byte-compared with the worktree before execution. The
scratch was mounted into short-lived, fully labelled slot 2 containers with a
read-only root filesystem, all capabilities dropped, no-new-privileges,
bounded PIDs/CPU/memory and no daemon socket in the container. The build itself
ran with `--network none`; only `npm ci` and `npm audit` used rootless package
registry egress.

All scratch files and containers were removed after evidence retention.
There are no residual task 4.2 paths, containers, networks, volumes or
processes. The direct-bind issue remains an explicit gate before task 5.1; it
does not make the byte-exact build result depend on a control-plane repository
or toolchain.

### Install and zero-vulnerability audit

The accepted image was the immutable Node lock:

`sha256:048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34`.

The container reported Node `v22.16.0` and npm `10.9.2`. `npm ci` installed 75
packages and audited 76 packages. The separate `npm audit --json` report
version 2 returned:

| Severity | Count |
|---|---:|
| info | 0 |
| low | 0 |
| moderate | 0 |
| high | 0 |
| critical | 0 |
| total | 0 |

The audit metadata reported 124 dependency entries in total. Neither
`package.json` nor `package-lock.json` changed.

### Canonical build and current-asset proof

The exact committed `scripts/web-build.sh` ran after the clean install. It
removed the prior `static/assets` directory and invoked the package build
`tsc -b && vite build`. Vite `7.3.6` transformed 1,583 modules and completed
successfully with networking disabled.

The generated index references, files present beneath `static/assets` and
asset paths printed by Vite are the same exact set:

- `assets/index-CBDgt9AB.css`;
- `assets/index-CJVFdsF0.js`.

There are exactly two generated asset files and no stale hash or unreferenced
asset. Their retained identities are:

| Output | Bytes | SHA-256 |
|---|---:|---|
| `index.html` | 449 | `b39db175916d0c758a208b35ebab64788fe48242b7c4133cdea5f1b0aca2309a` |
| `assets/index-CBDgt9AB.css` | 14,236 | `da7736c854c85f6c66f08f23f94f815284b2571565234449819a502aeee67cdd` |
| `assets/index-CJVFdsF0.js` | 309,378 | `d73ba0d0cf83b9112fac6909e3febe1e7cc3ea7a95693d2cc024f175282fe8df` |

The complete generated static tree is byte-identical to the selected commit,
so no copy-back or worktree modification was necessary. The WorkSession
worktree remains clean at the selected commit and tree.

### Retained evidence and non-impact

Evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-4.2-web-build`

The directory is `atenea-worker:atenea` mode `2770`; its ten evidence files
are mode `0640`. `SHA256SUMS` validates every retained input hash, timing,
install log, audit report, build log, asset verification and result record.
Its own SHA-256 is
`c0e9db65f5bb6f512a8acf9c4e5ee9532ae0c9040fe4627b5e9c11062ecda6e9`.

Mirror, worktree, complete session root, admission record and reconstructible
cache content/metadata fingerprints are identical before and after. The
artifact fingerprint changed only by adding the declared task 4.2 evidence.
Admission remains `slot2/heavy1`; capacity remains `1/4` normal and `1/2`
heavy.

Slot container/image counts returned to `3/3`, `0/4`, `0/0`, `0/0`, with
zero `ws-*` containers, networks and volumes. Allocated ports have zero
listeners; there are no browser or build processes. Rootful Docker, its socket
and containerd remain inactive and masked. Beautips, Atenea production and
preview remain `UP`, and AX42 AgentRun routing remains zero.

No Atenea source commit or push occurred. Task 4.2 is complete; OpenSpec
progress is `12/27`, task 4.3 is the first pending task and was not started.

## Task 4.3 — empty PostgreSQL initialization and synthetic fixtures

Completed on 2026-07-28 only for administrative WorkSession
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad` and `slot2/heavy1`. Programme progress
is `13/27`; task 4.4 was not started.

### Exact source, contracts and image

The AX42 worktree remained clean at commit
`b6dc854d94ba5b1976926656c9a6aba330f671e2` and tree
`f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`. A fresh byte-exact Git archive
reproduced SHA-256
`a6f52b2d267750dfb4f8bc9f31d3c0d2434876ddf6517920cb882f19112b5dea`.
The committed contract hashes were:

| Input | SHA-256 |
|---|---|
| manifest | `3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3` |
| Compose | `2133646b9fe6227ca417d6d62c92a74306caaa46a2957cdee810d5d7b0e5bb9f` |
| data inventory | `ae757a4befde1ba02fca03abc205779d5a8f919d121c8c14bc02a24cc755046d` |
| migration inventory | `360203affcb287b446a106651602affe11f6f60d0523283825d9a403d449a9ff` |
| allocation helper | `2efceeaaba78b349f1d6aa79bfba5d908d397a9e3a480cfa3b100bde52fb99d7` |
| manager | `902f25ad76c08ebbc2235cc4b06e2fe0fd94938a5ac2000a2151a27808bbcbd5` |
| engine | `617327df412fdee18b879295359bc000431f0bc686623b98e06c41b5ec93e6e3` |

All 45 committed migration paths and SHA-256 values matched the inventory.
The approved PostgreSQL image was initially absent from slot2 but is fixed
identically by the manager and engine contracts. Slot2 materialized only
`postgres:16@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20`;
the immutable repo digest and Linux/amd64 image identity matched. No image was
built, substituted or accepted by mutable tag.

### New volume and least-authority initialization

The successful run created a previously absent volume named
`ws-41c0ff95e5554773b7b460903a3af1ad-volume-db-data`. Its exact five labels
were engine `atenea-runtime-engine-v1`, session
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad`, project `atenea` and service `db`.

The first fail-closed attempt proved that the root entrypoint cannot initialize
an empty named volume with `cap_drop: ALL`. A second probe also proved that
changing only `PGDATA` does not resolve the required ownership operations.
Both probes removed their containers, networks, volumes and scratch.

The accepted run used the already versioned least-authority pattern documented
by the runtime-contract evidence: a one-shot container from the exact approved
PostgreSQL digest, network `none`, no published ports, read-only rootfs,
`no-new-privileges`, `cap_drop: ALL` and only `CAP_CHOWN`. It changed only the
empty volume root to UID/GID `999:999`, exited zero and was removed.
PostgreSQL then ran as `999:999` with `cap_drop: ALL`, no added capabilities,
the session-internal network and no published host port. The three allocated
host ports had zero listeners throughout.

The rootless UID-mapped PostgreSQL reference was a byte-exact ephemeral
mode-`0600` copy owned only by the mapped `999:999` identity. The worktree gate
was handled by a commit-exact Git archive and an ephemeral WorkSession-scoped
scratch; no ancestor ACL, owner, group or mode changed.

### Flyway and fixture acceptance

PostgreSQL began with zero public tables. Flyway 11.7.2 validated and applied
exactly the committed V1–V45 files in inventory order with
`baselineOnMigrate=false`, `validateOnMigrate=true`, `outOfOrder=false`,
`cleanDisabled=true` and target 45. The sanitized history contains 45
successful rows, ranks 1–45, zero failures and final integer version 45. No
baseline, repair or additional migration was used.

After migrations and before fixtures, all 28 declared domain-table counts were
zero. The deterministic loader created exactly:

- one `operator_account`;
- one `project`;
- one closed `work_session`;
- two `session_turn` rows;
- zero `agent_run` rows;
- every other declared table count at zero.

A second exact application was a no-op. A transactionally mutated project
caused `ATENEA_FIXTURE_CONFLICT project`; the proof then recorded `ROLLBACK`.
Final counts still matched
`ops/atenea-development-data-v1.json` exactly. Non-secret audits also prove no
open/closing session, running AgentRun, managed host/service/website authority,
push record, API usage or database-refresh authority was created.

### Secret boundary, cleanup and non-impact

Only the four development references declared by the manifest and data
inventory were resolved beneath the owned session secret directory:
`ATENEA_DEV_POSTGRES_PASSWORD`, `ATENEA_DEV_JWT_SECRET`,
`ATENEA_DEV_OPERATOR_PASSWORD` and
`ATENEA_DEV_OPERATOR_BROWSER_PASSWORD`. Each is
`atenea-worker:atenea` mode `0600`; the operator and browser references were
verified equal without printing them. No value or secret-derived hash is in
commands, logs, retained evidence or OpenSpec.

The successful trap removed the PostgreSQL, Flyway and helper containers, the
internal network, Git-archive and UID-mapped scratch, dependency scratch and
every temporary process. Slot2 ends with zero session-owned containers and
networks, zero allocated-port listeners and five images. It retains exactly
one session-owned volume, the accepted database volume required by task 4.4.
Rootful Docker, its socket and containerd remain inactive and masked.

Mirror, worktree, allocation, admission and cache fingerprints are unchanged.
Atenea remains clean and synchronized at `b6dc854...`; production and preview
remain `UP`, Beautips remains `UP`, and the sanitized routing-key count remains
zero. No production or preview endpoint, database, row, credential, volume,
backup or snapshot was queried or changed.

Final evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-4.3-database`

It includes exact source and contract identities, the complete migration
inventory, immutable image identity, retained volume identity and labels,
one-shot initializer and persistent-container isolation, sanitized Flyway
history and logs, pre/post counts, SQL audits, idempotence and conflict proof,
before/after fingerprints, cleanup proof, non-secret reproducibility inputs,
a passing result and `SHA256SUMS`. The previous fail-closed attempt is retained
separately at `runs/task-4.3-database-attempt-1-blocked`.

Task 4.3 is complete. Task 4.4 is now the first pending task and was not
started.

## Task 4.4 — complete backend suite

Completed on 2026-07-28 only for administrative WorkSession
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad` and `slot2/heavy1`. Programme progress
is `14/27`; task 5.1 was not started.

The AX42 worktree remained clean at commit
`b6dc854d94ba5b1976926656c9a6aba330f671e2` and tree
`f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`. A commit-exact Git archive
again reproduced SHA-256
`a6f52b2d267750dfb4f8bc9f31d3c0d2434876ddf6517920cb882f19112b5dea`.
The canonical committed `scripts/test.sh` was the suite entry point.

Its local Compose definition is not safe to pass directly to AX42: it uses
mutable images, fixed container names, control-plane mounts, a host-published
port and a Codex service with development defaults. Task 4.4 therefore used
an ephemeral exact-invocation adapter. It accepted only the two Compose calls
made by `scripts/test.sh`, mapped them to a task-owned PostgreSQL test
container and the pinned Maven/JDK 21 image, and rejected every other
invocation. No `docker compose up`, application runtime or Codex App Server
was started.

The test database used a new task-only volume and internal network, the exact
approved PostgreSQL 16 digest, no published port and no production data. Its
empty-volume owner was initialized with the same one-shot least-authority
`CAP_CHOWN` pattern accepted in task 4.3. The backend test container used:

- `maven:3.9.9-eclipse-temurin-21` at digest
  `sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e`;
- a read-only rootfs, `cap_drop: ALL`, no added capabilities and
  `no-new-privileges`;
- the task-internal network, no port bindings, 2 CPUs, 3 GiB memory and a
  2,048 PID limit;
- explicit non-production database values and disabled OpenAI, DeepSeek, FCM,
  GitHub, briefing, intent-router, bootstrap and Codex connectivity.

Dependencies were prefetched without tests, then the complete suite ran once
offline. Four earlier attempts failed closed before any test because Maven's
offline resolution omitted dynamic Surefire dependencies; each removed its
test database, volume, network and containers and is retained separately.
The accepted prefetch explicitly materialized the exact Surefire provider and
JUnit Platform launcher before the offline suite.

Surefire retained 48 XML reports and 48 text reports. Their aggregate is:

| Tests | Failures | Errors | Skipped |
|---:|---:|---:|---:|
| 327 | 0 | 0 | 0 |

The accepted test container exited zero without OOM after 26 seconds.
Thirteen resource samples recorded peak CPU `203.50%`, peak memory
`654 MiB / 3 GiB` (`21.29%`) and peak PID count `71`. Maven recorded
`BUILD SUCCESS`.

Cleanup removed the test container, PostgreSQL container, ownership helper,
internal network, test volume, source archive, dependency cache and scratch.
Slot2 again has zero session-owned containers and networks, zero allocated
host listeners and exactly one session-owned volume: the accepted task 4.3
database volume. The retained database volume was not mounted or modified.
AgentRun routing remains zero. No production or preview endpoint, credential,
row, volume, backup or snapshot was used, and retained evidence contains zero
matches for the four development secret values.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-4.4-backend-tests`

It includes exact source and image identities, the canonical-script adapter
and runner, dependency and backend logs, container isolation records, timing,
raw resource samples, a resource summary, all Surefire reports, aggregated
test result, before/after fingerprints, cleanup proof and `SHA256SUMS`.

Task 4.4 is complete. Task 5.1 is now the first pending task and was not
started.

## Task 5.1 — private runtime activation blocked before daemon access

Attempted on 2026-07-28 only for administrative WorkSession
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad` and `slot2/heavy1`. Task 5.1 remains
unchecked and programme progress remains `14/27`; task 5.2 was not started.

The preflight reproduced the selected Atenea commit
`b6dc854d94ba5b1976926656c9a6aba330f671e2`, tree
`f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`, clean worktree and clean index.
Workspace, allocation and admission hashes remained respectively
`8ee6237d0eb4c3bba0b3ccdc8ec9c0fb49550180bfebf921dc1ad70d75e4928e`,
`c4e45ac8ff834d68cddd385ec95699702b1df0ec7d574154b6dc8654fd592f13`
and `20c7a409e741eb73b4847a177c98b69128da7b07b6a9ac5ff7b7e1af1413a29d`.
The manifest and Compose hashes remained
`3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3`
and `2133646b9fe6227ca417d6d62c92a74306caaa46a2957cdee810d5d7b0e5bb9f`.

Activation stopped before invoking a daemon for four related contract gates:

1. The fixed `/usr/libexec/atenea-runtime-client-v1`,
   `/usr/libexec/atenea-runtime-manager-v1` and
   `/usr/libexec/atenea-runtime-engine-v1` paths are absent on AX42. No
   corresponding sudoers entry or service unit is installed.
2. The exact versioned engine, SHA-256
   `617327df412fdee18b879295359bc000431f0bc686623b98e06c41b5ec93e6e3`,
   explicitly rejects Atenea after validating its closed plan with
   `Atenea lifecycle activation remains disabled after plan validation`.
3. `atenea-slot2` cannot traverse the canonical worktree ancestors, which are
   intentionally `atenea-worker:atenea` mode `2770`.
4. The exact manager/engine adapter authorizes the canonical worktree itself as
   the bind source for `codex-app-server` and `atenea-dev`; it has no reviewed
   field for a byte-exact, WorkSession-scoped `git archive` delivery root.

Installing or extending the mediator, changing the bind allowlist, or changing
the committed Compose would be a new reviewed contract implementation.
Changing ACLs, modes, ownership or groups would violate the explicit gate.
Consequently no direct `docker compose up`, arbitrary Compose adapter,
rootful daemon, alternative database volume, capability workaround or image
substitution was attempted.

Slot2 remained at zero session containers, zero session networks and zero
listeners on `127.0.0.1:22667`, `127.0.0.1:28541` and
`127.0.0.1:22359`. The exact retained task 4.3 volume and its five ownership
labels remained unchanged. Rootful Docker, its socket and containerd remained
inactive and masked. Production and preview retained all nine expected running
containers, Beautips retained its three running containers, and routing
environment/record counts remained zero. Fingerprints before and after were
identical after excluding capture time and the task 5.1 evidence directory.

Blocked evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-5.1-private-runtime-attempt-1-blocked`

It includes source and contract hashes, sanitized workspace/allocation/admission
records, before/after fingerprints, worktree traversal evidence, installed
boundary state, container/network/volume/image/listener inventories, the exact
retained-volume labels, Beautips and control-plane status, routing absence, a
structured blocker and `SHA256SUMS`. The SHA-256 of `SHA256SUMS` is
`4098564cff3eccda9002fa85fd6d9c1e593997ea0f5ea7fd694b7b3962f240b4`.

The next action remains task 5.1. Before retrying, review and version the
minimum mediated lifecycle plus commit-exact delivery contract, install the
fixed client/manager/engine boundary and rerun the full negative regression
gate. Do not start task 5.2.

## Task 5.1 — private runtime accepted

Completed on 2026-07-28 only for administrative WorkSession
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad` and `slot2/heavy1`. Programme progress
is `15/27`; task 5.2 was not started.

The accepted source remained the clean WorkSession worktree at commit
`b6dc854d94ba5b1976926656c9a6aba330f671e2` and tree
`f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`. Workspace, allocation and
admission hashes remained respectively
`8ee6237d0eb4c3bba0b3ccdc8ec9c0fb49550180bfebf921dc1ad70d75e4928e`,
`c4e45ac8ff834d68cddd385ec95699702b1df0ec7d574154b6dc8654fd592f13`
and `20c7a409e741eb73b4847a177c98b69128da7b07b6a9ac5ff7b7e1af1413a29d`.
The fixed archive SHA-256 remained
`a6f52b2d267750dfb4f8bc9f31d3c0d2434876ddf6517920cb882f19112b5dea`.

### Mediated contract and source delivery

The versioned programme contract at
`d1e71d68f0634d59f3aec5fd5695b0196594decc` installs exact root-owned
client, manager, engine and Atenea adapter boundaries beneath `/usr/libexec`
plus the exact sudoers rule for `atenea-worker`. All local, negative-policy
and AX42 regression suites passed, including the integrated runtime contract
gate `8/8`; strict OpenSpec validation passes.

The adapter accepts only this administrative WorkSession, runtime, source
commit/tree, manifest and Compose hashes. It creates a byte-exact delivery at
`/tmp/atenea-runtime-delivery/ws-41c0ff95e5554773b7b460903a3af1ad`
owned for `atenea-slot2`. No ACL, owner, group or mode beneath the canonical
worktree changed. Named development secret references are copied into the
private delivery boundary without values in plans, commands, inspect output,
logs or evidence.

The application JAR was produced by a bounded build container from the pinned
Maven/JDK 21 digest. The build container had a read-only rootfs,
`cap_drop: ALL`, `no-new-privileges`, finite CPU, memory and PID limits, no
published port and no daemon socket. The running application consumes only
that commit-exact JAR and delivery.

### Runtime identity, network and health

The mediated lifecycle reports:

`{"state":"ready","healthState":"healthy"}`

Exactly three running containers exist:

- `ws-41c0ff95e5554773b7b460903a3af1ad-db`;
- `ws-41c0ff95e5554773b7b460903a3af1ad-codex-app-server`;
- `ws-41c0ff95e5554773b7b460903a3af1ad-atenea-dev`.

Each has the exact session, runtime, project, service and engine ownership
labels. All use a read-only root filesystem, `cap_drop: ALL`,
`no-new-privileges`, private PID/IPC namespaces, finite PID limits, no added
capability, device, privilege, host networking or daemon-socket mount. They
share only the exact labelled internal bridge
`ws-41c0ff95e5554773b7b460903a3af1ad-network`.

An internal rootless bridge has no Docker gateway endpoint, so normal Compose
port publication is deliberately absent from container HostConfig. The
root-owned adapter instead registers and retains exactly three RootlessKit
3.0.2 `tcp4` mappings:

- `127.0.0.1:22667` to Codex `8092`;
- `127.0.0.1:28541` to PostgreSQL `5432`;
- `127.0.0.1:22359` to Atenea `8081`.

The live RootlessKit IDs exactly match the root-owned retained state file.
`ss` reports exactly these three allocated listeners and none on
`0.0.0.0`.

PostgreSQL is healthy. Atenea returns `{"status":"UP"}` through its private
health endpoint. The reviewed Codex image is built from the pinned Node
22.16.0 digest with Codex `0.145.0` and fixed at
`sha256:c081aaa9d40afa4d8b57297000fe9aff5635e52a94b2b87abf8626b128c55e2d`.
Codex refuses unauthenticated non-loopback listeners, so the image keeps the
authentication-disabled App Server on `127.0.0.1:18092`; a fixed
credential-free same-container TCP proxy exposes only internal port `8092`.
Both the private `/readyz` check and the allocated host TCP check pass.

### Accepted PostgreSQL persistence

The runtime reuses only
`ws-41c0ff95e5554773b7b460903a3af1ad-volume-db-data`, with the exact task 4.3
labels. No other session volume exists. The data root remains
`/var/lib/postgresql/data`; no nested `PGDATA`, Flyway initialization,
fixture application, repair or baseline is present.

An early fail-closed iteration selected a nested `PGDATA` and created a second
empty cluster inside the accepted volume. The runtime was stopped immediately.
After proving all containers stopped and the accepted root cluster remained
intact, only the task-5.1-created nested directory was fingerprinted and
deleted. The final contract removes that `PGDATA` setting and selects the
task 4.3 database and role `atenea_ax42_synthetic_v1`.

Final Flyway history is byte-identical to task 4.3: 45 successful rows, ranks
1–45, zero failures and final version 45. All 28 declared table counts are
also byte-identical: one operator account, one project, one closed
WorkSession, two SessionTurns, zero AgentRuns and zero rows in every other
declared table. The accepted task 4.3 and 4.4 `SHA256SUMS` hashes remain
`80dc617182d0466a8a5c7a770de7dfcaaa60b513ebabc7eb3672440ac02b8688`
and `e284239926aae141cff5253f0a81fcc13525ec4bade67464c4238a6c5eaa80a8`.

### External authority, resources and non-impact

The final application environment disables OpenAI, OpenAI costs, the core
intent router, briefings, DeepSeek costs, FCM/mobile push and auth bootstrap.
OpenAI, DeepSeek, FCM and operational GitHub base URLs are local fail-safe
`127.0.0.1:9` destinations. The internal network provides no external path.
There are zero AgentRun rows and zero routing records.

Retained evidence contains sanitized service logs, container/network/volume
and image inspections, exact ownership labels, loopback mappings and
listeners, health output, full Flyway history, all 28 counts, CPU/memory/PID
measurements, process inventories, installed-boundary hashes, before/after
fingerprints and secret scanning. No development secret value matched.

Rootful Docker, its socket and containerd remain inactive and masked.
Production and preview retain all nine expected running containers. Beautips
retains its three expected running containers, with PostgreSQL and Redis
healthy. The Atenea source repository remains clean and synchronized at
`b6dc854d...`; the programme repository was clean and synchronized at
`d1e71d6...` when runtime evidence was closed.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-5.1-private-runtime`

It contains 62 regular files. `SHA256SUMS` verifies all other 61 files; its
SHA-256 is
`23010f74668e1f962a056b67505bb8c9816e47a953409fd2a53c0056f87ea856`.
The result is `pass`. Task 5.2 is the first pending task and was not started.

## Task 5.2 — authenticated operator DOM accepted

Completed on 2026-07-28 only for administrative WorkSession
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad`, `slot2/heavy1`, Atenea commit
`b6dc854d94ba5b1976926656c9a6aba330f671e2` and tree
`f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`. Programme progress is
`16/27`; task 5.3 remains pending and was not started.

### Reviewed Playwright module boundary

The pinned image
`mcr.microsoft.com/playwright:v1.60.0-noble@sha256:9bd26ad900bb5e0f4dee75839e957a89ae89c2b7ab1e76050e559790e946b948`
contains Chromium but did not contain the Node `playwright` package. The first
probe failed before launching a browser, authentication or DOM work.

Programme commit `837d998a04279acbba738711a6ec60ab9758ac3d`
adds a reviewed `package-lock.json` for exact `playwright` and
`playwright-core` version `1.60.0`. The preparation-only installation used
the pinned Node image through slot2, enforced package integrity, and published
the resulting 171-file bundle at
`/var/lib/atenea-slots/slot2/toolchain/playwright-module-v1`. Its canonical
content-tree SHA-256 is
`1ca49077563d996a21591e41f5a71296747d81ed9f1936e4887924fcb574b2ee`.
The browser container mounts the bundle read-only. Offline verification with
`--network none` returned Playwright `1.60.0` and Chromium
`148.0.7778.96`; no toolchain or browser process remained.

Toolchain evidence is retained beneath
`runs/task-5.2-toolchain-remediation`. It contains 12 regular files; the
SHA-256 of its `SHA256SUMS` is
`2b3e44f634a47d6715c016031a344cd59f81c4fbf8c0578d53e07f3ab9044fc4`.

### Route and synthetic operator projection

The selected commit serves the React console from commit-exact
`src/main/resources/static/index.html`, Git object
`ac4ea34f6dabcb4e200188afad801928bcb79d0d`. The manifest preview path
`/admin/login` returned HTTP 404 while `/` returned HTTP 200. The passing
check used `/` and retains this discrepancy explicitly; Atenea was not
changed, rebuilt, restarted or redeployed.

The fixture contains one closed WorkSession, but the committed
`MobileProjectOverviewService` exposes only `OPEN` or `CLOSING` sessions as
the mobile operator's active project session. The accepted DOM expectation is
therefore the synthetic project with `Sin sesión` and the non-mutating
presence of `Crear sesión`, not the closed session title as an active item.
PostgreSQL evidence independently proves the closed WorkSession and its two
SessionTurns remain intact.

Three fail-closed attempts are retained separately:

- missing Playwright Node module:
  `runs/task-5.2-playwright-dom-attempt-1-blocked`, `SHA256SUMS` SHA-256
  `a03fb6ce76671789686e16f3f1b2885a498a6a7f8456e536b51f6ed38832b568`;
- manifest path returning HTTP 404:
  `runs/task-5.2-playwright-dom-attempt-2-blocked-manifest-route`,
  `SHA256SUMS` SHA-256
  `d2f3b1f3df6417739442bae820e571345a4793d37cf538082903be353326f88b`;
- closed fixture incorrectly expected as an active mobile session:
  `runs/task-5.2-playwright-dom-attempt-3-blocked-closed-session-projection`,
  `SHA256SUMS` SHA-256
  `f9a5f3554a730d4b3c8544e1909c149d2eb05d532802248cddb532d9501c88e4`.

Each attempt closed its browser container, removed its temporary named secret
copy and restored synthetic refresh-token count to zero.

### Finite-timeout DOM acceptance

The passing Playwright process ran on AX42 inside the admitted session network,
without host networking. The complete process timeout was 600 seconds;
browser launch was 30 seconds, navigation 15 seconds, locators/assertions 10
seconds, authentication 20 seconds, network waits 15 seconds, `page.evaluate`
5 seconds and each viewport 90 seconds. Page, context and browser close in
`finally`.

At both `1440x900` and `390x844`, semantic locators proved:

- initial HTTP success and visible `Backend disponible`;
- HTTP 200 synthetic operator login;
- absence of `Acceso de operador` and `Entrar` after authentication;
- visible `[SYNTHETIC] AX42 Operator V1`;
- visible `Atenea Core`, `[SYNTHETIC] Atenea AX42 V1` and `Sin sesión`;
- visible and enabled `Salir`, `Crear sesión`, `Rescate` and `Actualizar`;
- visible but disabled empty-input `Ejecutar`;
- HTTP 200 project overview;
- a complete, non-empty DOM with no login, permanent loading or inline error;
- zero external browser requests and zero failed local requests.

The two authenticated viewports created exactly two synthetic refresh tokens;
logout revoked them and the bounded cleanup deleted exactly two, returning the
count to zero. No AgentRun, routing record or other domain row was created.

### Final invariants and retained evidence

Flyway remains at 45 successful V1–V45 rows with zero failures. All 28
declared table counts are byte-identical before and after: one operator, one
project, one closed WorkSession, two SessionTurns, zero AgentRuns and zero
rows elsewhere. Workspace, allocation and admission hashes are unchanged.

The same three labelled runtime containers, one internal network, one retained
PostgreSQL volume and three loopback listeners remain. Runtime status is
`ready/healthy`; rootful Docker, its socket and containerd remain inactive and
masked. Production and preview retain nine running containers and health
`UP`; Beautips retains three running containers and health `UP`.

No browser, Node/Playwright, proxy or tunnel process from the test remains.
Secret scanning found zero value matches. The passing run captured no
screenshot or trace, and no image was inspected; visual acceptance remains
exclusively task 5.3.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-5.2-playwright-dom`

It contains 29 regular files. `SHA256SUMS` verifies all other 28 files; its
SHA-256 is
`351dca13a8e356bf0eac6e8018f672250de5a4006887ff711d4505af445b7418`.
The result is `pass`. Task 5.3 is the first pending task and was not started.

## Task 5.3 — inspected desktop and mobile screenshots accepted

Completed on 2026-07-28 only for administrative WorkSession
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad`, `slot2/heavy1`, Atenea commit
`b6dc854d94ba5b1976926656c9a6aba330f671e2` and tree
`f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`. Programme progress is
`17/27`; task 5.4 remains pending and was not started.

### Exact route, authentication and browser boundary

The acceptance reused the 5.2 route authority and opened `/`, backed by
commit-exact `src/main/resources/static/index.html` Git object
`ac4ea34f6dabcb4e200188afad801928bcb79d0d`. It did not change or redeploy
Atenea to address the manifest's `/admin/login` discrepancy.

The retained runner used Playwright `1.60.0`, Chromium `148.0.7778.96`, image
digest
`sha256:9bd26ad900bb5e0f4dee75839e957a89ae89c2b7ab1e76050e559790e946b948`
and the read-only module bundle whose canonical content-tree SHA-256 is
`1ca49077563d996a21591e41f5a71296747d81ed9f1936e4887924fcb574b2ee`.
It ran only in a labelled ephemeral slot2 container on the existing internal
WorkSession network. The development browser password came from its named
secret file; no value, cookie, token, storage state, authorization header,
trace or HAR was retained.

The complete process timeout was 600 seconds; browser launch was 30 seconds,
navigation 15 seconds, locators 10 seconds, authentication 20 seconds, network
responses 15 seconds, `page.evaluate()` 5 seconds, screenshots 15 seconds,
page/context/browser closure 10 seconds and each viewport 90 seconds. Page,
context and browser closed in `finally`.

### Pre-capture DOM assertions and measurements

Before each screenshot, semantic locators proved:

- login heading and action absent after HTTP 200 authentication;
- `[SYNTHETIC] AX42 Operator V1` visible;
- `Proyectos`, `[SYNTHETIC] Atenea AX42 V1` and `Sin sesión` visible;
- `Crear sesión`, `Rescate` and `Actualizar` visible and enabled;
- empty-input `Ejecutar` disabled on the authenticated home state;
- no login, permanent loading, backend error or inline error;
- zero external browser requests and zero failed local requests.

At desktop, document/body `scrollWidth` and `clientWidth` were all `1440`.
At mobile they were all `390`. Neither viewport had unintended horizontal
overflow. Critical titles, state and actions had positive bounding boxes,
intersection ratio `1` and full viewport containment. Stable pairwise checks
found zero visible overlaps.

### Direct screenshot inspection

The retained original-resolution images are:

- `desktop-1440x900.png`, SHA-256
  `3f5b88b3f28577c5e10d8eb88b3336d513799267b8f2f1dd8e95ebd801612892`;
- `mobile-390x844.png`, SHA-256
  `a95a11378c16c9a5aa8a76572860a5f60a97cc53ecd87707e80b024c08f93d49`.

Both PNGs were inspected, not merely existence-checked. Desktop presents a
clear `Proyectos` → filter → project → `Sin sesión` → `Crear sesión`
hierarchy. Text, facts and actions are readable; the primary action is above
the fold and visually stronger than `Rescate`. No clipping, overlap, control
outside the viewport or unreadable wrapping is visible. The intentional
ellipsis of the long desktop base-branch fact preserves its label and does not
obscure the operational state.

Mobile preserves the same hierarchy and meaning. Filters stack cleanly,
project facts become full-width rows, `Sin sesión` remains explicit and both
`Crear sesión` and `Rescate` are fully visible within `390x844`. No horizontal
overflow, clipping, overlap or illegible wrapping is visible. The populated
project card and explicit state cannot be mistaken for an empty list.

The header dash in both images is the committed no-managed-hosts state returned
by `healthSnapshot` after a successful read, not a loading or error marker.
Desktop and mobile are visually consistent.

### Cleanup, non-impact and retained evidence

The two authenticated viewport contexts created exactly two synthetic refresh
tokens. Logout revoked them and bounded cleanup returned the count to zero.
Flyway remains at 45 successful V1–V45 migrations. All 28 declared table
counts remain byte-identical: one operator, one project, one closed
WorkSession, two SessionTurns, zero AgentRuns and zero rows elsewhere.

Workspace, allocation and admission hashes are unchanged. Runtime status
remains `ready/healthy` with the same three labelled containers, one internal
network, one retained PostgreSQL volume and three loopback listeners. No
Chromium, Playwright, browser Node process, proxy, tunnel or test container
remains. Routing is zero; no WorkSession, allocation, admission, network,
volume or database was created.

Rootful Docker, its socket and containerd remain inactive and masked.
Production and preview remain `UP` with their nine containers. Beautips remains
`UP` with its three containers. The Atenea source repository remains clean and
synchronized at `b6dc854d...`; the programme repository was clean and
synchronized at `5906f6b...` before this evidence-only documentation update.

Final sanitization checked all retained files and both PNGs against the four
development secret values and found zero matches. It also found zero storage
states, traces, HAR files, cookie dumps or authorization-header dumps.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-5.3-playwright-visual`

It contains 36 regular files. `SHA256SUMS` verifies the other 35 files; its
SHA-256 is
`8d6cc8093107126b2d07b517d0ef5177462c609fea996d285cc8d7743cedf37f`.
The result is `pass`. Task 5.4 is the first pending task and was not started.

## Task 5.4 — unavailable external integrations fail safely

Completed on 2026-07-28 only for administrative WorkSession
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad`, admitted to `slot2/heavy1`.
The accepted source remained the clean WorkSession worktree at commit
`b6dc854d94ba5b1976926656c9a6aba330f671e2`, tree
`f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`. No source, UI, CSS, runtime
configuration or deployment was changed.

### Declared integration boundary

The before/after container inspection retained only allowlisted non-secret
configuration and proved:

- OpenAI runtime, OpenAI costs, DeepSeek costs, briefing, FCM and LLM intent
  routing are disabled;
- OpenAI, DeepSeek, FCM and GitHub base URLs use the explicit loopback failure
  sentinel `http://127.0.0.1:9`;
- the WorkSession network is still Docker `Internal=true`;
- external OpenAI, DeepSeek, FCM and GitHub credential environment variables
  are absent;
- only named runtime secret filenames and mount destinations were retained,
  never their values.

Hashes bind the evidence to the proposal, design, both change specifications,
tasks, acceptance contract, private-preview contract, manifest and the exact
source guard implementations. The runtime source guards establish that FCM
returns before token or message HTTP when disabled, DeepSeek briefing rejects
before provider HTTP when disabled, and GitHub rejects before HTTP when its
token is absent. Those operational integrations were deliberately not invoked.

### Finite local probes and fail-safe outcomes

The reproducible `task-5.4-run.sh` used only AX42 loopback ports and finite
timeouts: 650 seconds for the outer command, 600 seconds for the declared
process budget, 20 seconds for Docker/runtime calls, 5 seconds per PostgreSQL
statement, and 3-second connect/10-second complete/20-second process limits for
HTTP. It authenticated only the declared synthetic operator with the named
development browser-password file. Login request, response, curl authorization
configuration and logout request existed only in a private temporary directory
and were shredded or deleted by the exit trap.

The accepted run proved:

- costs HTTP 200 reported OpenAI and DeepSeek `configured=false`,
  `status=disabled`, zero totals and no model or cost lines;
- speech synthesis, realtime voice and voice transcription each returned
  sanitized HTTP 503 with the exact committed disabled message before provider
  transport;
- push-device and managed-host reads returned HTTP 200 with zero records;
- the existing local Codex `/readyz` returned HTTP 200 while required external
  Codex authentication remained disabled;
- no GitHub operational action, DeepSeek briefing, FCM send, managed-host
  action, AgentRun or routing action was invoked;
- runtime logs after the start timestamp contained zero OpenAI, DeepSeek, FCM
  or GitHub attempt signatures.

The launcher first returned 127 before starting the runner because of remote
shell quoting. The first real probe then completed all safe calls and cleanup
but exposed an incorrect postcondition assumption: logout revokes a refresh
token row rather than deleting it. The corrected accepted assertion proves one
row created, zero active tokens after logout, one revoked row deleted by the
bounded cleanup and zero rows afterward. Both preliminary attempts and their
effects are retained structurally; neither caused a provider call or left
authentication data.

### Data, isolation, cleanup and non-impact

Before/after Flyway evidence is byte-identical at 45 successful V1–V45
migrations. All 28 table counts are byte-identical after cleanup: one operator,
one project, one closed WorkSession, two SessionTurns and zero AgentRuns, API
usage rows, push devices/logs, managed hosts/services/websites, core commands,
routing-related records, refresh tokens and all other declared synthetic
tables.

Workspace, allocation and admission hashes are unchanged. Runtime status
remains `ready/healthy` with exactly the same three labelled containers, one
internal network, one retained PostgreSQL volume and three loopback listeners.
The worktree and index remain clean. No Chromium, Node/Playwright, proxy,
tunnel or test process remains, and no test container, WorkSession, allocation,
admission, route, network, volume or database was created.

Rootful Docker, its socket and containerd remain inactive and masked. Atenea
production and preview remain `UP` with the same nine containers. Beautips
remains `UP` with its three containers. The source repository remains clean
and synchronized at `b6dc854d...`; the programme repository was clean and
synchronized at `d5eafc0...` before this documentation-only update.

The final scan checked all retained files against four named development secret
values and found zero matches. It also found no retained login response,
logout request, curl auth file, storage state, trace, authorization bearer,
access token, refresh token or password field outside the reproducible runner.
No raw provider response, cookie, token, password or authorization header is
retained.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-5.4-external-integrations-fail-safe`

It contains 33 regular files. `SHA256SUMS` verifies the other 32 files; its
SHA-256 is
`bc750f5c958867f69b6f8b23d562ed7a13c96e990fb5f64b2d463ca0e10d0a70`.
The result is `pass`. Programme progress is `18/27`; task 6.1 is the first
pending task and was not started.

## Task 6.1 — named administrative SSH/tmux session

Completed on 2026-07-28 for administrative WorkSession
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad`, without creating or claiming a managed
execution. The session is named `codex-atenea-41c0ff95`, owned by administrator
`jose`, and has one window named `administrative`.

### Administrative boundary and context

The initiating SSH path resolved `codex-worker-01` to its Tailscale address
`100.81.98.93` and authenticated `jose` by public key. Password authentication
was not used. The session started in the exact admitted worktree:

`/srv/atenea/workspaces/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/atenea`

Codex is the independently installed official `0.145.0` CLI. Its sanitized
login guard reported ChatGPT authentication without reading or retaining
authentication material. The promoted context manifest is
`remote-codex-admin-v1`, aggregate SHA-256
`afa03516a02362c216876b930145b9ab03c3561e138f9da10be8b26509a21b35`,
with seven allowlisted configuration, instruction and skill files. The
manifest explicitly excludes authentication, history, sessions, logs, caches,
state databases, SSH keys and project secrets.

Tmux options persist the following non-secret contract on the named session:

- mode `administrative`;
- the exact WorkSession, runtime and workspace identities;
- context marker `ATENEA-41C0FF95-20260728`;
- AgentRun `none`;
- worker lease `none`;
- routing `none`.

The Codex child process runs as `jose`, in the exact worktree, with no
`DOCKER_HOST` environment. This is intentionally a sudo-administrator bridge:
it is not evidence of the managed sandbox, dispatch, idempotency or lease
contracts and Atenea MUST NOT use it as an AgentRun executor.

### Conversation initialization

The first launch encountered Codex's interactive repository-trust guard. The
initial bounded runner therefore stopped without retaining a session or
changing Git/runtime state. The accepted session was then created with a
keepalive diagnostic parent, the trust choice was made explicitly, and the
Codex child remained active. The first marker submission needed one additional
Enter after the pasted TUI input settled; the finite retry returned:

`CONTEXT-READY ATENEA-41C0FF95-20260728`

The corrected retained runner now handles the trust guard and waits one second
between literal input and submit. Both preliminary outcomes are recorded
structurally. The accepted session was created at
`2026-07-28T18:17:49Z`, remains alive with one window and zero attached
clients, and preserves the marker in its current pane.

Task 6.1 deliberately created the session detached and did not perform the
task 6.2 attach/disconnect/reconnect sequence. No resume claim is made here.

### Non-impact and sanitization

Before/after fingerprints prove the unchanged source commit
`b6dc854d94ba5b1976926656c9a6aba330f671e2`, tree
`f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`, clean index,
workspace/allocation/admission hashes and runtime `ready/healthy` status. The
same three containers, one internal network, one retained volume and three
loopback listeners remain.

Flyway remains byte-identical at 45 successful V1–V45 migrations. The checked
synthetic subset is unchanged: one operator, one project, one closed
WorkSession, two SessionTurns, zero AgentRuns, refresh tokens, API usage,
push records, managed hosts and core commands. Routing records remain zero.
No WorkSession, allocation, admission, runtime resource, database or listener
was created.

Production and preview remain `UP` with the same nine containers. Beautips
remains `UP` with its three containers. Rootful Docker, its socket and
containerd remain inactive and masked. The source and programme repositories
were clean and synchronized at `b6dc854d...` and `f9c9cca...` before this
documentation-only update.

The installed `codex-work` SHA-256 is
`f5a6c4accfc56101b2f26bc7f9838b9a4c306daffece2195ae76fa8935bbee85`;
the current programme template is
`6f0ce8f61847851e64a1c29d1c7f04c299f56b97f153d5d12c77e64ac4f2325a`.
The only reviewed difference is an added `export COLORTERM=truecolor`, which
does not change workspace validation, daemon access or authority. Task 6.1
used an explicit detached tmux command instead of invoking the helper.

Final sanitization checked all retained files against the four development
secret values and found zero matches. No Codex authentication, history,
session or state-database file, auth header, token, password, environment dump
or unsanitized credential material is retained.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-6.1-administrative-tmux-session`

It contains 32 regular files. `SHA256SUMS` verifies the other 31 files; its
SHA-256 is
`c914c4d4234701dd5d2d01ecabcd841f6c7fd72fca09bc982f4bef5045498ecf`.
The result is `pass`. Programme progress is `19/27`; task 6.2 is the first
pending task and was not started.

## Task 6.2 — administrative disconnect and conversation resume

Completed on 2026-07-28 against the exact task 6.1 session
`codex-atenea-41c0ff95`. The test used two new, independent, finite private SSH
connections and real tmux clients. It did not create or replace a tmux session,
AgentRun, dispatch, lease, routing record, WorkSession, allocation or runtime
resource.

### Stable administrative identity

Before the first attach, with the first client attached, during the detached
gap, with the second client attached before and after the response, and after
the final detach, fingerprints retained the same:

- `session_created=1785262669`;
- window `administrative` and pane `%0`;
- pane PID `1170290` and live Codex child;
- worktree
  `/srv/atenea/workspaces/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/atenea`;
- commit `b6dc854d94ba5b1976926656c9a6aba330f671e2`;
- tree `f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`;
- clean worktree and index;
- WorkSession `41c0ff95-e555-4773-b7b4-60903a3af1ad`;
- runtime `ws-41c0ff95e5554773b7b460903a3af1ad`;
- allocation `slot2`, workload `heavy`, permit `heavy1`.

The attached-client sequence was exactly `0→1→0→1→0`. The first client
detached with tmux `Ctrl-b d`; an independent connection then proved that the
same session and Codex process remained live with zero clients. The second
connection attached to that exact session and also detached with `Ctrl-b d`.
Both SSH client commands exited zero under finite outer timeouts.

All `@atenea_*` labels remained byte-identical. They classify the session as
`administrative`, bind the same WorkSession/runtime/workspace and record
AgentRun `none`, worker lease `none` and routing `none`. `DOCKER_HOST` remains
absent from the tmux session environment. This test is not managed execution
evidence.

### Conversation continuity

The second client saw the existing task 6.1 marker in pane `%0`:

`CONTEXT-READY ATENEA-41C0FF95-20260728`

The client then asked the existing conversation, explicitly without tools or
file changes, for one exact response. It appeared after one bounded polling
interval in the same pane:

`CONTINUITY-RESUMED ATENEA-41C0FF95-20260728`

The retained marker extract contains the task 6.1 prompt/response followed by
the task 6.2 prompt/response. Raw interactive terminal output was not retained.

### Runtime, data and control-plane non-impact

Before/after fingerprints are equal for Git, workspace/allocation/admission
hashes, runtime `ready/healthy`, the three session containers, one internal
network, one retained PostgreSQL volume and three loopback listeners. Rootful
Docker, its socket and containerd remain inactive. The final tmux session is
alive with zero attached clients.

Flyway evidence is byte-identical at 45 successful V1–V45 migrations. Counts
remain one synthetic operator, one project, one closed WorkSession, two
SessionTurns, zero AgentRuns and zero refresh tokens; the other checked
synthetic tables remain zero. Worker routing records remain absent.

Atenea production and preview remain `UP` with the same nine containers.
Beautips remains `UP` with the same three containers. The Atenea source
repository remains clean and synchronized at `b6dc854d...`. The programme
repository was clean and synchronized at `e2a1ce1...` before this
documentation-only update.

The first evidence-runner invocation returned 1 after completing the pre-test
fingerprint because one local `jq` assertion was over-escaped. At that point no
client had attached and no operational state had changed. The expression was
corrected and the already captured fingerprint was validated; the accepted
attach/disconnect/resume sequence then completed without recreating or
replacing the session. Exit codes, timeouts and observed durations are retained
in `operations.json`, and the exact reproducible procedure is in `commands.md`.

Final scanning retained no raw terminal, Codex authentication, history,
internal session file, token, cookie, environment dump, private key or
credential-pattern match. Secret-value files were not read. The task 6.1
evidence checksum was reverified, and contract hashes are retained.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-6.2-administrative-continuity`

It contains 44 regular files. `SHA256SUMS` verifies the other 43 files; its
SHA-256 is
`1216ed3162348b6d3f4f2e465bffd071ed8ec468b792bf1b5ff517b176bb54ed`.
The result is `pass`. Programme progress is `20/27`; task 7.1 is the first
pending task. No runtime stop, rollback or other section 7 action was executed.

## Task 7.1 — exact owned runtime rollback

Completed on 2026-07-28 only for administrative WorkSession
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad` and its persisted
`slot2/heavy1` admission. Task 7.2 was not executed.

### Mediated stop and exact resource removal

The installed manager validated the exact session, allocation, manifest,
source commit/tree, runtime plan, slot and admission before invoking the fixed
Atenea adapter. The bounded stop exited zero after 1,631 ms and returned:

`{"state":"stopped","healthState":"stopped"}`

The adapter retained current logs, stopped all three exact containers and
removed the three retained RootlessKit listener identities. The containers
were stopped with their exact service labels and no allocated listener
remained.

A task-scoped rollback wrapper then validated the complete five-label
ownership tuple—engine, project, runtime, service and WorkSession—for each
stopped container and the internal network. It recorded immutable IDs and
removed exactly:

- `ws-41c0ff95e5554773b7b460903a3af1ad-db`;
- `ws-41c0ff95e5554773b7b460903a3af1ad-codex-app-server`;
- `ws-41c0ff95e5554773b7b460903a3af1ad-atenea-dev`;
- `ws-41c0ff95e5554773b7b460903a3af1ad-network`.

No volume or image was removed. The accepted PostgreSQL volume
`ws-41c0ff95e5554773b7b460903a3af1ad-volume-db-data` retains the exact five
ownership labels. The complete slot2 image inventory is byte-identical before
and after.

The exact versioned admission tool, SHA-256
`a81366d3495bb2a7bf4702e9ea934a74e9b3edb30f728926e655a5c0a6a9f7ce`,
released `heavy1` before `slot2`. Both operations exited zero in a combined
166 ms. The persisted record now reports heavy and normal states as
`released`.

### Bounded corrective continuation

The first rollback wrapper invocation returned 1 only after the successful
mediated stop. Its post-stop network assertion expected the pre-stop endpoint
count of three, while Compose had correctly disconnected all stopped
containers and the actual count was zero.

Read-only inspection proved the manager result was already
`stopped/stopped`, all three containers were stopped, all listeners were
absent, the exact network and volume remained, admission was still
`held/held`, no resource had been removed and tmux/Codex remained alive. Only
that expected endpoint count was corrected. The stop was not repeated and the
runtime was not recreated. `execution-attempts.json` retains this boundary.

### Retention and non-impact

The mirror refs, workspace record, allocation record, worktree commit
`b6dc854d94ba5b1976926656c9a6aba330f671e2`, tree
`f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`, index hash and clean Git status
are unchanged. A complete manifest proves every prior retained artifact is
byte-identical. Post-stop logs are byte-identical before and after exact
container/network cleanup.

The administrative session remains
`codex-atenea-41c0ff95`, with the same `session_created=1785262669`, window
`administrative`, pane `%0`, pane PID `1170290`, live Codex child and zero
attached clients. Its administrative/no-AgentRun/no-lease/no-routing labels
remain unchanged.

Immediately before stop, Flyway remained at 45 successful V1–V45 migrations.
The synthetic counts remained one operator, one project, one closed
WorkSession, two SessionTurns, zero AgentRuns and zero refresh tokens; all
other checked tables remained zero. After rollback the database was not
restarted merely to re-query it; persistence is proven by the unchanged exact
retained volume identity and labels.

Atenea production and preview remain `UP` with the same nine containers.
Beautips remains `UP` with the same three containers. The Atenea source and
programme repositories remained clean and synchronized at `b6dc854d...` and
`83f3180...` during the exercise. Routing records remain zero. Rootful Docker,
its socket and containerd remain inactive.

Final scanning retained no Codex authentication, history, internal session
file, token, cookie, environment dump, private key or credential-pattern
match. Secret-value files were not read. Contract hashes and the verified 6.2
evidence checksum are retained.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-7.1-atenea-runtime-rollback`

It contains 46 regular files. `SHA256SUMS` verifies the other 45 files; its
SHA-256 is
`25c6a03f43c727652020161116011a82d3a881e2b8b74ba94dd59b6b3bd2bf70`.
The result is `pass`. Programme progress is `21/27`; task 7.2 is the first
pending task and was not started. Task 7.3 remains separately gated by
explicit restart authorization.

## Task 7.2 — rollback idempotence and ownership rejection

Completed on 2026-07-28 only for administrative WorkSession
`41c0ff95-e555-4773-b7b4-60903a3af1ad`, runtime
`ws-41c0ff95e5554773b7b460903a3af1ad` and the already released
`slot2/heavy1` admission. The runtime was not recreated and task 7.3 was not
executed.

### Complete pre-fingerprint and second rollback

The accepted run first verified the complete task 7.1 package and its
`SHA256SUMS` SHA-256
`25c6a03f43c727652020161116011a82d3a881e2b8b74ba94dd59b6b3bd2bf70`.
It fingerprinted:

- worktree commit `b6dc854d94ba5b1976926656c9a6aba330f671e2`,
  tree `f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`, clean status and index;
- workspace, allocation and `released/released` admission records;
- mirror refs, delivery source/owner and persisted engine state;
- runtime logs and every prior retained artifact;
- container, network, volume and image identity in all four rootless slots;
- owned resources, loopback listeners and browser/broker processes;
- administrative tmux identity/labels, routing records and rootful services;
- independent control-plane Git/index, production/preview health and container
  sentinels, plus Beautips health and containers.

The repeat rollback found all three target containers, the internal network
and all three allocated listener identities already absent. The manager
correctly refused another mediated stop after admission release with exit 65
and `RUNTIME_OWNERSHIP_CONFLICT`; it did not reach engine execution. Repeating
the exact versioned heavy and normal release operations exited zero and left
both records `released`. The structured second-pass result is
`stopped/stopped`, removed `0/0/0/0` containers, networks, images and
listeners, changed no retained state and did not recreate runtime.

### Literal unlabelled, partial, foreign and ambiguous denial

The accepted corpus created four stopped, internal network fixtures in slot2.
It started no process and created no image:

- `unlabelled`: no labels at all;
- `partial`: only the expected engine, session and runtime labels;
- `foreign`: all five labels, but with a foreign WorkSession and runtime;
- `ambiguous`: all five expected labels plus conflicting
  `com.atenea.owner=foreign`.

Before invoking the gate, evidence recorded each immutable network ID, exact
name, creation time, driver and complete labels. Every ownership check exited
65 with `RUNTIME_OWNERSHIP_CONFLICT`. The resource remained addressable by the
same immutable ID and its complete inspect SHA-256 was byte-identical before
and after denial.

Cleanup re-inspected the current resource and required equality with the
recorded immutable ID, name, creation time, driver and labels. It then removed
only that exact ID. Final all-slot inventories are byte-identical to the
pre-fixture inventories.

### Residual, retention and non-impact result

The accepted WorkSession has zero containers, networks, owned images,
allocated listeners, brokers and Playwright/Chromium processes. Its exact
labelled PostgreSQL volume remains. The pre-existing slot2 anonymous volume
also remains; no global image or unrelated slot resource changed.

Mirror refs, worktree Git/index, workspace and allocation records, delivery,
engine state, logs and every prior artifact are unchanged. The admission
record remains `released/released`. The same administrative tmux session,
`session_created=1785262669`, `administrative` window and pane `%0` remains
alive with zero clients and explicit AgentRun/lease/routing `none` labels.
The synthetic AgentRun count remains zero by the unchanged retained database
identity and the accepted pre-stop 7.1 data evidence; the database was not
restarted merely to re-query it. Worker routing records remain zero.

Production and preview remained `UP` with the same nine containers and clean,
synchronized source/programme Git sentinels. Beautips remained `UP` with the
same three containers. Rootful Docker, its socket and containerd remained
inactive.

### Transparent bounded corrections

Four preliminary evidence roots are retained:

- attempt 1 corrected the inherited cwd used for rootless inspection;
- attempt 2 bounded ownership denial in a subshell and removed two
  attempt-owned anonymous volumes only after exact creation-time/ID proof;
- attempt 3 treated expected absence after exact deletion as success;
- attempt 4 removed the non-ownership task label from the literally
  unlabelled fixture after strict evidence validation rejected it.

Every attempt records zero rollback-target removal, zero residual fixtures,
unchanged admission, no runtime recreation and no foreign-resource change.
Their own `SHA256SUMS` files verify. The accepted run uses network-only
fixtures and created no anonymous volume.

Final sanitization retained no Codex authentication, history, internal session
file, token, cookie, environment dump, private key or credential-pattern
match. Secret-value files were not read.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-7.2-rollback-idempotence`

It contains 84 regular files. `SHA256SUMS` verifies the other 83 files; its
SHA-256 is
`f65acffc596e333ac3a3428c784756eeee8b73729d6046c5e810e051b84745c0`.
The result is `pass`. Programme progress is `22/27`; task 7.3 is the first
pending task. Task 8.1 was not started.

## Task 7.3 — authorized restart and persisted reconciliation

Completed on 2026-07-28 after the instruction supplied the separate explicit
authorization for one AX42 restart. The exercise remained limited to
administrative WorkSession `41c0ff95-e555-4773-b7b4-60903a3af1ad` and runtime
`ws-41c0ff95e5554773b7b460903a3af1ad`. Task 8.1 was not executed.

### Complete pre-reboot boundary

Before restart, evidence captured:

- programme/source Git branches, commits, upstreams, worktrees and indexes;
- mirror refs, workspace, allocation and released admission records;
- the exact retained PostgreSQL volume, delivery source and engine state;
- runtime logs and every prior retained artifact;
- containers, networks, volumes, images and daemon state for all four slots;
- boot ID, uptime, RAID, storage, mounts, services, firewall and SSH policy;
- Tailscale, strict worker health, rootful Docker and proxy states;
- administrative tmux identity/labels and all residual/routing sentinels;
- independent production, preview and Beautips health/container identities.

Task 7.2 verified at SHA-256
`f65acffc596e333ac3a3428c784756eeee8b73729d6046c5e810e051b84745c0`.
There were zero session containers, networks, owned images, allocated
listeners, fixtures and browser/broker processes. The exact session volume
remained. RAID had three `[UU]` arrays; production, preview and Beautips were
`UP`.

The administrative tmux session was still
`codex-atenea-41c0ff95`, `session_created=1785262669`, window
`administrative`, pane `%0`, zero clients and explicit AgentRun/lease/routing
`none`.

Two read-only preflight attempts are retained. The first outer invocation
returned 1 after writing a complete snapshot; replay of every recorded gate
passed. An xtrace-only second capture localized the actual assertion defect:
the fixed string `[UU]` was escaped twice. Neither attempt requested a reboot
or changed a resource. Fixed-string matching passed in the accepted preflight.

### Single reboot and finite recovery

The reboot request persisted the authorization, timestamp and old boot ID
before invoking exactly one `systemctl reboot`. SSH exited zero. Finite probes
with three-second connection timeouts observed AX42 unreachable and then
reconnected on attempt 10.

The boot ID changed:

- before: `0886b4d0-485c-4035-b8bb-1b0ab910e85c`;
- after: `5cc2a4e3-020d-4d19-8a55-6ecae77f22ce`.

No second reboot was requested. All three arrays returned `[UU]`; storage,
key-only SSH, firewall, Tailscale and the strict host health suite pass. All
four rootless user managers, Docker daemons and daemon sockets returned.
Their stable proxy sockets were available; one read-only `docker info` per
socket activated and proved the four proxy paths. Rootful Docker,
`docker.socket` and containerd remain inactive and masked.

### Persisted ownership reconciliation

Reconciliation used only these persisted sources:

- `workspace-v1.json`;
- `runtime-allocation-v1.json`;
- the runtime admission record;
- the exact `engine-v1` ownership marker;
- rootless Docker immutable resource metadata.

They select the same WorkSession/runtime and persisted `slot2/heavy1`.
Admission remains `released/released`; the three session containers, internal
network and three allocated listeners remain absent. The structured accepted
outcome is `stopped/stopped`, action `report-only`.

No runtime was recreated or started. No resource was removed, no volume was
reattached, no slot was reassigned and no ownership was invented. AgentRuns
remain zero by the unchanged retained database identity and accepted pre-stop
data evidence; the development database was not started merely to re-query it.
Worker lease remains `none` and routing records remain zero.

The volume identity/labels, mirror refs, worktree commit/tree/index, workspace,
allocation and admission records, engine state, runtime logs and all prior
artifacts are byte-identical. The reconstructible delivery under `/tmp` was
cleared by reboot, exactly as decision D-017 predicts.

Rootless Docker regenerated only the default `bridge` network ID in each
daemon. The network name/driver shape is identical across all four slots.
`host`, `none` and the persistent Beautips network kept exact immutable IDs;
no session or foreign network was removed or replaced. The first postflight
assertion intentionally blocked on raw network-ID equality, and the second
blocked until normalized shape was sorted deterministically. Both were
read-only continuations after the same reboot.

### Administrative session and non-impact

The tmux/Codex administrative session did not survive the host reboot. This is
the expected effect for the non-persisted administrative bridge. Evidence
records it as `absent-expected-not-recreated`; no tmux session or Codex process
was recreated or replaced.

Production and preview remained `UP`, with the same nine immutable container
IDs and clean, synchronized programme/source Git sentinels. Beautips returned
`UP` with the same three immutable container IDs and persistent network.
There are zero session containers, networks, owned images, listeners,
Playwright/Chromium processes, AgentRuns, leases and routing records.

Final sanitization retained no Codex authentication, history, internal session
file, token, cookie, environment dump, private key or credential-pattern
match. Secret-value files were not read.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-7.3-restart-reconciliation`

It contains 123 regular files. `SHA256SUMS` verifies the other 122 files; its
SHA-256 is
`57c702382e7d9551224d19121a310adb337b6aba554fe5434bc57e553f0819ba`.
The result is `pass`. Programme progress is `23/27`; task 8.1 is the first
pending task and was not started.

## Task 8.1 — final production non-impact comparison

Completed on 2026-07-28 as a read-only final comparison. The capture fetched
only Git refs, called the two local health endpoints, selected non-secret
Docker identity fields and scanned source plus persisted routing-record paths.
It read zero environment values and zero database rows.

The programme was clean and synchronized at
`bb14726b06ad07c8cb804fd76b3747beb37fa474` before handoff documentation.
Atenea remains clean and synchronized on
`feature/actualizar-conversacion-en-web` at
`b6dc854d94ba5b1976926656c9a6aba330f671e2`, tree
`f8c0dff5c7acf3d82d73885b09f9b1d142b562d2`. The source intentionally advanced
from the task 1.1 entry commit only through the accepted relocation commits;
branch authority, remote equality and clean index are preserved.

Production and preview remain `UP`. All nine immutable container ID/name pairs
match the independently accepted task 7.3 post-reboot capture, including the
production PostgreSQL container. The task 1.1 container/database sentinels were
reported unchanged through every intermediate non-impact gate; exact task 7.3
identity followed by exact final identity completes the transitive comparison.
This also proves that running container environments did not change without
reading their values.

Non-documentation source routing matches, control-plane routing records and
worker routing records are all zero. AX42 has zero session containers,
networks and owned images, one retained labelled volume, no lease and no
routing. AgentRuns remain zero by unchanged retained database identity.
Beautips remains `UP`.

One first, read-only finalizer attempt expected the fresh summary itself to
contain the detailed container array instead of comparing its separate
immutable ID/name manifest. The assertion stopped before a result was issued,
changed no resource and is retained transparently beneath
`task-8.1-final-non-impact-attempt-1-blocked`.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-8.1-final-non-impact`

It contains 30 regular files. `SHA256SUMS` verifies the other 29 files; its
SHA-256 is
`21ef3351db436d2cec0223a692c92ca6c303e08683553eeafe37744f942692d7`.
The result is `pass`. Programme progress is `24/27`.

## Task 8.2 — final AX42 safety and capacity audit

Completed on 2026-07-28 with only read-only system, service, rootless Docker,
Git and health inspection plus evidence writes. The strict installed worker
verifier passes. All three RAID arrays are `[UU]` with no recovery action;
root and `/srv/atenea` filesystems are each at 4% use.

UFW is active. Effective SSH policy is key-only with interactive password and
X11 forwarding disabled. Tailscale is online and healthy with `No serve
config`. All four rootless user managers, Docker daemons, daemon sockets and
stable proxies are active. Each slot retains CPU quota `4s`,
`MemoryHigh=10737418240`, `MemoryMax=12884901888` and `TasksMax=4096`, and
reports rootless security mode.

Rootful Docker, `docker.socket` and containerd remain inactive and masked; the
Docker group has no members. Every slot container, image, volume,
name/driver-network and persistent-network inventory equals task 7.3. The
Atenea WorkSession has zero containers, networks, owned images, listeners and
browser/broker processes, plus exactly one retained labelled PostgreSQL
volume. AgentRuns, lease and routing remain zero/none.

Beautips is `UP`, clean and synchronized at
`5044a3b07b3db82895e9c8ff47bc4bc9b0e97130`, with the same three immutable
container IDs as task 7.3. No environment value, database row, secret or Codex
state file was read.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-8.2-final-worker-audit`

It contains 62 regular files. `SHA256SUMS` verifies the other 61 files; its
SHA-256 is
`00de504f1a1381c5945701d08dc3ebcdba88703c98d1655200994b731a538a00`.
The result is `pass`. Programme progress is `25/27`.

## Task 8.3 — operator handoff, rollback and resume

Completed on 2026-07-28. The mobile/server operator document now records the
accepted administrative workflow, exact fail-closed rollback boundary,
retained state, post-reboot `report-only` behavior and explicit administrative
resume requirement. It states that this pilot is not AgentRun routing and
keeps production/deploy/database authority on Atenea.

The handoff references only non-secret artifact roots and their verified
manifests. It explicitly excludes Codex authentication, history and internal
session files, tokens, cookies, credentials, environment dumps and production
data. No runtime, route, production resource, unrelated slot or Beautips
resource changed.

Passing evidence is retained at:

`/srv/atenea/artifacts/sessions/41c0ff95-e555-4773-b7b4-60903a3af1ad/runs/task-8.3-operator-handoff`

It contains 8 regular files. `SHA256SUMS` verifies the other 7 files; its
SHA-256 is
`0068a4f8428e6d8a2d2c1bb8896bb8c68b8f90e544b21cbd0f9e6676743338f7`.
The result is `pass`. Programme progress is `26/27`; task 8.4 is the first
pending task.
