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
