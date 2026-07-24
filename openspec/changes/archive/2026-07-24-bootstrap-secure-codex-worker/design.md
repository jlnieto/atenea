## Context

The first worker is a newly installed Hetzner AX42 running Ubuntu 24.04.4. It has healthy RAID 1 arrays and only SSH exposed, but the baseline is not ready for repositories or agent execution: `root` is the only administrative login, UFW is inactive, effective sshd policy enables global password authentication and X11 forwarding, and monitoring/development/private-network packages are absent.

The worker is reachable through a verified ED25519 host key and the operator's ED25519 public key. Any access change must preserve a tested recovery path. This phase changes only the host baseline; Atenea continues executing on its current VPS.

## Goals / Non-Goals

**Goals:**

- Make the AX42 baseline reproducible from versioned Atenea operations code.
- Establish named administration, key-only SSH, deny-by-default firewall and a tested break-glass path without locking out the operator.
- Prepare private Tailscale connectivity with explicit ownership and recovery gates.
- Monitor RAID, NVMe, disk and required host state before workloads arrive.
- Create least-privilege filesystem identities and paths for later worker/runtime phases.
- Prove the host returns to the declared state after reboot.

**Non-Goals:**

- Install or run Codex, Docker project workloads or project repositories.
- Route AgentRuns to the AX42.
- Configure previews, runtime manifests or the final worker protocol.
- Store project/API/Codex secrets.
- Complete external backup; this phase keeps only local configuration rollback copies.

## Decisions

### 1. Use repository-owned idempotent Bash automation

Add `ops/worker/bootstrap.sh`, `ops/worker/verify.sh`, configuration templates and a runbook. This matches Atenea's existing scripts-first operations model and avoids adding Ansible before one host justifies it. The scripts fail closed, support repeated verification and emit a structured summary.

**Alternative considered:** configure the server interactively. Rejected because the resulting security state would not be reviewable, repeatable or restorable.

### 2. Apply access changes in anti-lockout stages

The sequence is fixed:

1. capture preflight and configuration backups;
2. create `jose`, install the existing ED25519 public key and grant sudo;
3. prove a new independent `jose` SSH connection and non-interactive sudo;
4. write an sshd drop-in, run `sshd -t`, reload rather than restart, and prove both named admin and root break-glass sessions;
5. configure UFW rules, verify them while disabled, enable UFW, and prove both sessions again.

The accepted sshd policy is password authentication off, keyboard-interactive off, X11 forwarding off, public keys on, root restricted to public-key break-glass, and a lower authentication-attempt limit. Root is not fully disabled until a later phase has proven private recovery and Hetzner console procedures.

### 3. Keep one public key-only SSH break-glass service

Port 22 remains reachable initially over public IPv4/IPv6, limited by UFW and sshd key policy. After Tailscale is proven, normal administration uses the private name/IP. Removing public SSH is a later explicit decision because this host has no separate management network.

**Alternative considered:** immediately allow SSH only through Tailscale. Rejected because enrollment or coordination failure would create an avoidable lockout during bootstrap.

### 4. Install Tailscale but gate production enrollment

The official signed Ubuntu package source is installed by versioned automation. `tailscale up` is not automated with a reusable personal key. The operator must choose the production tailnet identity provider, owner and recovery administrator; the device is then enrolled with a worker tag and least-privilege ACL. Standard OpenSSH remains the administration service; Tailscale SSH is not enabled initially.

### 5. Defer Docker to the runtime-contract phase

This phase verifies cgroup v2 and host prerequisites but does not install Docker. Docker changes firewall forwarding and creates a powerful control surface; its rootless/mediated design belongs to `establish-project-runtime-contract`.

### 6. Create service identity and filesystem skeleton without data

Create system group `atenea`, no-login system user `atenea-worker`, and paths beneath `/srv/atenea` for worker state, repository mirrors, workspaces, caches, artifacts and backup staging. The administrative user belongs to the group. Secrets will later live under `/etc/atenea-worker` with root/service access, but this phase stores no secret values.

### 7. Use systemd verification rather than premature remote alert integration

Install `smartmontools`, retain `mdadm`, and add a read-only health script plus systemd service/timer. It validates `[UU]`, absence of resync/degraded state, SMART overall health, disk thresholds, time sync, sshd syntax, firewall and required accounts/paths. Results go to journald and structured local output. Atenea alert ingestion is added in a later operations phase.

### 8. Reboot is an explicit acceptance step

Package/kernel updates and baseline services are accepted only after a controlled reboot followed by host-key verification, named admin login, root break-glass login, RAID/SMART check, firewall check and timer/service check.

## Risks / Trade-offs

- **SSH/firewall change can lock out the host** → require two proven sessions, syntax checks, reloads and configuration backups before each activation.
- **Public SSH remains visible** → key-only policy, UFW rate limiting and root public-key restriction reduce exposure; private-only access is evaluated later.
- **Tailscale identity is not yet selected** → install packages only and block enrollment task until ownership/recovery is explicit.
- **Unattended upgrades can reboot unexpectedly** → enable security updates but keep automatic reboot disabled; reboots are operator-controlled.
- **SMART behaviour differs by NVMe/device** → verification records unsupported attributes separately and treats explicit failed health as critical.
- **A local rollback copy does not survive host loss** → it is only for bootstrap rollback; external backup remains a programme gate.
- **Bash can grow into configuration-management debt** → keep this phase small, idempotent and tested; reconsider Ansible only if multiple workers appear.

## Migration Plan

1. Add and validate operations automation without executing it.
2. Capture AX42 preflight, host keys, network listeners, RAID and current sshd state.
3. Run the prepare stage to create accounts, paths, packages and backups.
4. Prove named admin access in a new connection.
5. Apply/reload SSH hardening and prove named/root key sessions.
6. Apply/enable UFW and prove access again from the operator laptop.
7. Install Tailscale package; pause before enrollment if tailnet ownership is unresolved.
8. Enroll the approved devices and add private-interface rules when the gate is resolved.
9. Enable health timer, run verification and record results.
10. Reboot under observation and repeat the full verification.

Rollback restores timestamped sshd/UFW configuration through the still-open root session or Hetzner rescue console, disables newly added timers/services, and leaves current Atenea execution unchanged.

## Open Questions

- Which company-controlled identity provider account owns the tailnet, and who holds a second recovery administrator role?
- Whether public SSH remains permanently rate-limited or is restricted to known source ranges after mobile/private access is proven.
- Which alert transport will receive worker-health failures before the remote worker protocol exists.
