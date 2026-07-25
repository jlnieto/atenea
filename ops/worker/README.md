# Atenea Codex worker bootstrap

This directory contains the versioned, staged bootstrap for the Ubuntu 24.04
AX42 worker. It intentionally does not install Docker, Codex, project
repositories, application secrets, or Tailscale enrollment credentials.

## Safety model

- Run one stage at a time from an existing root recovery connection.
- Never close that connection until a fresh `jose` login has succeeded.
- The `ssh` stage keeps root public-key access as a break-glass path.
- The `firewall` stage exposes only rate-limited TCP/22 publicly.
- `tailscale-package` installs the signed package but does not enroll a tailnet.
- Every mutating stage writes rollback evidence beneath
  `/var/backups/atenea-worker-bootstrap/`.

## Usage

Copy this directory to the worker, then run:

```bash
sudo ./bootstrap.sh preflight
sudo ATENEA_WORKER_ADMIN_PUBKEY_FILE=/root/atenea-admin.pub ./bootstrap.sh prepare
ssh jose@WORKER_IP 'sudo -n true'
sudo ./bootstrap.sh ssh
ssh jose@WORKER_IP 'sudo -n sshd -T | head'
sudo ./bootstrap.sh firewall
ssh jose@WORKER_IP 'sudo -n ufw status verbose'
sudo ./bootstrap.sh tailscale-package
sudo ./bootstrap.sh monitoring
sudo ./verify.sh
sudo ./verify.sh --json
```

The approved administrator key is input, not repository content. The default
hostname is `codex-worker-01`; override it with
`ATENEA_WORKER_HOSTNAME` if the programme contract changes.

## Tailscale gate

Do not run `tailscale up` until the tailnet owner, a second recovery
administrator, device tags, and ACL policy have been agreed. Enrollment is a
separate operator action and no reusable personal auth key belongs on disk.

## Rollback

List snapshots first:

```bash
sudo ./rollback.sh list
sudo ./rollback.sh dry-run /var/backups/atenea-worker-bootstrap/TIMESTAMP-STAGE
```

Restore only from an open recovery shell. The rollback helper requires the
exact snapshot directory and validates sshd before reloading it. UFW is not
blindly disabled: its saved files are restored and reloaded.

## Evidence

```bash
sudo ./verify.sh
sudo ./verify.sh --json | jq .
sudo journalctl -u atenea-worker-health.service --since today
sudo systemctl status atenea-worker-health.timer --no-pager
sudo cat /proc/mdstat
sudo smartctl -H /dev/nvme0n1
sudo smartctl -H /dev/nvme1n1
```

## Administrative Codex bridge

After the secure baseline has passed and the runtime-contract change is active,
install the pinned, non-authoritative bridge:

```bash
sudo ./install-codex-bridge.sh
sudo -u jose -H codex login --device-auth
sudo -u jose -H codex doctor --summary
```

The script installs the Ubuntu 24.04 `bubblewrap` AppArmor profile recommended
for the Codex Linux sandbox, promotes only the versioned non-secret
configuration/instructions, and installs `codex-work`. It never copies a laptop
Codex home or authentication file.

The promoted administrative context is controlled by
`codex-context-allowlist-v1.txt`; `codex-context-lock-v1.txt` pins every source
hash and the reviewed Git revision. Review the deterministic effective
manifest without changing the worker:

```bash
./promote-codex-context.sh plan | jq .
```

Apply the reviewed context separately when its versioned sources change:

```bash
sudo ./promote-codex-context.sh apply
```

The apply action fails closed on unversioned or modified sources,
credential-like material, symlinks and unexpected custom skill files. It
installs only the allowlisted configuration, global instructions and OpenSpec
skills, writes `~/.codex/context-manifest.json` with per-file and aggregate
SHA-256 hashes, and does not read or copy authentication, histories, sessions,
logs, caches, state databases, SSH keys or project secrets.

Start or reattach a private persistent session:

```bash
ssh -t codex-worker \
  /home/jose/.local/bin/codex-work beautips \
  /srv/atenea/workspaces/manual/beautips
```

Detach with `Ctrl-b`, then `d`. This bridge runs as the named administrator and
MUST NOT be used as Atenea's managed AgentRun executor.

## Rootless runtime slots

Install one of the four pinned rootless Docker slots:

```bash
sudo ./install-rootless-runtime-slot.sh 1
```

The script masks the rootful Docker/containerd units, verifies Docker's signing
key, pins the Engine/CLI/Compose/Buildx/containerd packages, creates a
non-administrative slot user and applies a systemd CPU, memory and task limit.
It publishes a group-restricted proxy to that slot's rootless socket beneath
`/run/atenea-runtime/`; it never publishes `/var/run/docker.sock`.

The current `dev` template is an administrative Beautips pilot. The generic
manifest resolver and default-deny managed broker remain part of the active
runtime-contract change and are required before Atenea can schedule a project.

## Version-pinned toolchains

`toolchain-lock-v1.sh` pins the supported Ubuntu host packages, rootless Docker
packages and OCI manifest-list digests for Node 22, Maven/Java 21, Tomcat/Java
8 and Playwright/Chromium. Java, Node and browser tools run in the selected
rootless slot instead of mutating a host-global toolchain.

Review the lock without changing the worker:

```bash
./install-toolchain-prerequisites.sh plan
sudo ./install-toolchain-prerequisites.sh verify-host
```

Install and verify the immutable images in one prepared slot:

```bash
sudo ./install-toolchain-prerequisites.sh install-images 2
sudo ./install-toolchain-prerequisites.sh verify-slot 2
```

The image action is idempotent, uses only the selected slot's rootless socket,
runs version probes with networking disabled and never enables the rootful
Docker daemon. Repeat it explicitly for another slot when that slot needs the
toolchain cache.

## Session mirrors and worktrees

`session-workspace-v1.sh` implements task 3.1's Git boundary. Run it as the
`atenea-worker` service identity with the persisted WorkSession UUID, registered
project, credential-free canonical GitHub remote, base branch and session-owned
branch:

```bash
sudo -u atenea-worker ./session-workspace-v1.sh ensure \
  018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d9e \
  dummy-compose \
  https://github.com/example/dummy-compose.git \
  main \
  atenea/session-018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d9e
```

The helper keeps fetched canonical branches under `refs/remotes/origin/*` so a
fetch cannot overwrite a session branch. It serializes changes per project,
persists a worker-owned `workspace-v1.json` beside the worktree and fails closed
when the session identity, remote, branch, ownership record or Git registration
does not match. It never resets, cleans or switches an existing worktree.

Run the synthetic lifecycle and conflict suite without GitHub or real project
state:

```bash
./test-session-workspace-v1.sh
```

## Session runtime allocation

`session-runtime-allocation-v1.sh` implements task 3.2 without starting a
runtime or changing Git state. It requires the ready task 3.1 ownership record,
derives every runtime name from the complete WorkSession UUID, serializes
slot/port ownership, persists a byte-stable allocation and creates only the
declared runtime, log, artifact and rebuildable cache roots:

```bash
sudo -u atenea-worker ./session-runtime-allocation-v1.sh ensure \
  018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d9e \
  slot2 \
  /srv/atenea/workspaces/sessions/018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d9e/dummy-compose/runtime.json
```

Task 3.2 accepts normal-workload manifests only. Heavy admission and resource
pressure remain task 4.4; lifecycle commands and JSON rendering remain tasks
3.3 and 3.4. Run the collision, ownership, preservation and concurrency suite
with synthetic state beneath `/tmp`:

```bash
./test-session-runtime-allocation-v1.sh
```
