## 1. Versioned bootstrap automation

- [ ] 1.1 Add `ops/worker/README.md` with supported host, staged execution, anti-lockout procedure, rollback and evidence commands.
- [ ] 1.2 Add an idempotent `ops/worker/bootstrap.sh` with explicit preflight, prepare, ssh, firewall, tailscale-package and monitoring stages.
- [ ] 1.3 Add managed sshd, systemd health service/timer and worker health-script templates without embedding host secrets or enrollment credentials.
- [ ] 1.4 Add `ops/worker/verify.sh` with human and JSON output for accounts, paths, sshd, firewall, RAID, SMART, capacity, time and service state.
- [ ] 1.5 Validate scripts with syntax checks and a non-mutating preflight against the AX42 baseline.

## 2. Host identity, accounts and packages

- [ ] 2.1 Capture timestamped pre-change evidence and rollback copies of sshd, UFW and managed systemd configuration.
- [ ] 2.2 Set the canonical hostname and verify local resolution without changing public DNS.
- [ ] 2.3 Create the named `jose` sudo administrator, install the approved ED25519 key and prove a fresh independent login plus non-interactive sudo.
- [ ] 2.4 Create the `atenea` group, no-login `atenea-worker` system account and least-privilege `/srv/atenea` and `/etc/atenea-worker` skeleton.
- [ ] 2.5 Apply supported security updates and install baseline administration, RAID/NVMe monitoring and Tailscale-package prerequisites with automatic reboot disabled.

## 3. SSH and firewall anti-lockout transition

- [ ] 3.1 Render the sshd drop-in, validate with `sshd -t` and record the effective policy before reload.
- [ ] 3.2 Reload sshd while the recovery connection remains open and prove new named-admin and root key-only connections.
- [ ] 3.3 Stage a deny-by-default IPv4/IPv6 UFW policy with rate-limited public SSH and no worker/runtime ports.
- [ ] 3.4 Enable UFW and prove a new SSH connection before closing either recovery session.
- [ ] 3.5 Verify password, keyboard-interactive and X11 access are disabled and the public listener set matches the phase contract.

## 4. Private-network gate

- [ ] 4.1 Install Tailscale from its signed supported Ubuntu package source without storing or using a reusable personal auth key.
- [ ] 4.2 Record the unresolved tailnet owner/recovery administrator as an explicit gate if operator identity is not yet available.
- [ ] 4.3 After approval, enroll AX42 with the worker tag, enroll/verify Atenea and operator devices, and apply least-privilege ACL plus UFW private-interface rules.
- [ ] 4.4 Prove private SSH/name connectivity and retain the documented public key-only break-glass path.

## 5. Health monitoring and reboot acceptance

- [ ] 5.1 Install and enable the worker health script, systemd service and timer.
- [ ] 5.2 Verify healthy RAID `[UU]`, no resync, NVMe SMART, disk thresholds, time sync, sshd syntax, firewall and required identities in human and JSON output.
- [ ] 5.3 Trigger and record safe negative-path checks without degrading the live RAID or access path.
- [ ] 5.4 Perform a controlled reboot and re-verify host key, named admin, root break-glass, private connectivity when enrolled, firewall, RAID/SMART and timer state.
- [ ] 5.5 Record resulting configuration evidence and any deferred gates in the programme ledger.

## 6. Validation and handoff

- [ ] 6.1 Run strict OpenSpec validation and repository diff checks.
- [ ] 6.2 Confirm current Atenea AgentRun routing, production containers and project repositories were not changed by this phase.
- [ ] 6.3 Execute the documented configuration rollback test or an equivalent isolated dry run and record its result.
- [ ] 6.4 Observe the baseline for 24 hours without authentication, RAID, network or service-start regression before archiving the phase.
