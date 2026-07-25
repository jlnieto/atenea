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
