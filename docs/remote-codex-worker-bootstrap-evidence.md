# Secure Codex worker bootstrap evidence

Evidence date: 2026-07-23 CEST. Change:
`bootstrap-secure-codex-worker`.

## Result

The fresh AX42 is now a hardened worker host baseline. It is not yet a Codex
runtime and no Atenea AgentRun is routed to it.

| Control | Accepted evidence |
|---|---|
| Identity | hostname `codex-worker-01`; `jose` named administrator; `atenea-worker` no-login service account; `atenea` service group |
| Administration | fresh `jose` ED25519 login and `sudo -n` passed; root ED25519 break-glass login retained |
| SSH | password and keyboard-interactive attempts rejected; X11 disabled; `MaxAuthTries 3`; sshd syntax/effective policy passed |
| Firewall | UFW active on boot; incoming deny/outgoing allow; only rate-limited TCP/22 authorized for IPv4 and IPv6 |
| Filesystem | `/srv/atenea/{worker,repositories,workspaces,caches,artifacts,backups-staging}` owned by service identity with setgid group isolation; `/etc/atenea-worker` restricted |
| Packages | supported Ubuntu updates applied; automatic security updates active; automatic reboot disabled |
| Storage | `md0`, `md1`, `md2` all `[UU]`; no resync/recovery; both NVMe SMART health checks passed; root usage 1% |
| Time | NTP synchronized after boot |
| Tailscale | signed Ubuntu Noble package source; Tailscale 1.98.9; daemon enabled; `NeedsLogin`; enrollment gate stored under `/etc/atenea-worker/gates` |
| Monitoring | `atenea-worker-health.timer` enabled every 15 minutes; isolated service returns success; human and JSON output pass 13 checks |

## Reboot acceptance

The worker was rebooted after hardening. Both named-admin and root key-only
connections returned. The host key fingerprints remained the Hetzner-provided
values:

- ED25519: `SHA256:iKfOw8e3r87Cysbd7lYs3HvakeySu0ueqxU6OETsxSI`
- RSA: `SHA256:BgiVTGw+8ztdWxHro1Wk96jYsQAviBW0Ekxp6h1Y0q8`
- ECDSA: `SHA256:wpJVY3iHpHZkLNqmbvgpYXpPUhf7izn+yu0kn5axDTs`

After the normal short NTP convergence period, strict JSON verification
returned `ok: true` and the systemd health service completed with result
`success` and exit status 0.

## Safety and rollback evidence

- Timestamped snapshots exist under
  `/var/backups/atenea-worker-bootstrap/` for prepare, SSH, firewall,
  Tailscale-package and monitoring stages.
- A safe negative check supplied an intentionally incorrect expected hostname;
  strict verification returned exit status 1 and identified only `hostname`.
- `rollback.sh dry-run` accepted an exact snapshot path and enumerated the SSH,
  UFW and systemd files it would restore without changing the host.
- No Docker runtime, Codex runtime, repositories, project secrets or Atenea
  production routing were introduced in this phase.

## Remaining gates

1. Choose the production tailnet owner and a second recovery administrator.
2. Agree worker tags and least-privilege ACLs, then enroll AX42, Atenea, laptop
   and mobile without storing a reusable personal auth key on the worker.
3. Prove private SSH/name connectivity while retaining public key-only
   break-glass access.
4. Observe the host for 24 hours after the 2026-07-23 01:35 CEST reboot. The
   archive gate cannot close before 2026-07-24 01:35 CEST.

## Versioned implementation

- `597dd41 ops: add staged AX42 worker bootstrap`
- `ab640e6 fix: verify UFW inside health sandbox`

The programme branch remains local to the Atenea clean worktree until it is
explicitly reviewed and pushed.
