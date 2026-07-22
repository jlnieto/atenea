## Why

The AX42 is a fresh Internet-reachable Ubuntu host whose only routine account is `root`, whose firewall is inactive and whose effective SSH policy still enables password authentication and X11 forwarding. Before it receives repositories, secrets, Docker or Codex workloads, Atenea needs a reproducible hardened baseline with a proven non-lockout path, private connectivity and storage/host health evidence.

## What Changes

- Add versioned, idempotent worker bootstrap and verification automation under Atenea operations source.
- Establish the canonical hostname, named administrative account, key-only SSH policy and retained key-based break-glass path.
- Enable a deny-by-default host firewall only after a second administrative login succeeds.
- Apply operating-system security updates and install the minimum host administration, RAID/NVMe monitoring and private-network prerequisites.
- Install Tailscale packages and define least-privilege enrollment/ACL expectations; joining the production tailnet remains gated on operator-owned identity and recovery administration.
- Add systemd health checks and actionable local evidence for RAID membership, NVMe health, disk capacity, time synchronization and required services.
- Create the canonical `/srv/atenea` filesystem ownership skeleton without cloning projects, copying secrets or enabling Codex execution.
- Define atomic backup/rollback for SSH, firewall and service configuration and verify recovery after reboot.
- Keep current Atenea execution routing unchanged throughout this phase.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `worker-operational-safety`: Specify the concrete bootstrap, anti-lockout, network exposure, host identity, storage monitoring and reproducibility requirements for the first AX42 worker.

## Impact

- Adds worker bootstrap, configuration templates and verification scripts under `ops/worker/` in the Atenea repository.
- Changes the fresh AX42 operating-system configuration, accounts, SSH policy, firewall, packages, monitoring and filesystem layout.
- Adds Tailscale as the approved private-network client but does not expose worker services or route AgentRuns.
- Does not modify Atenea APIs, database schema, production containers, project repositories or current Codex App Server routing.
