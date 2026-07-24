# Project runtime contract evidence

## Status

- Change: `establish-project-runtime-contract`
- Worker: `codex-worker-01`
- Started: 2026-07-24
- Atenea production routing: unchanged
- Real projects schedulable through Atenea: none

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
its own device authorization. Only a minimal versioned configuration and worker
instruction file were promoted.

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
