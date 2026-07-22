# Remote Codex Platform Baseline

Evidence date: 2026-07-22. Values are operational snapshots, not secrets or permanent capacity promises.

## Operator laptop

| Item | Observed baseline |
|---|---|
| CPU | AMD Ryzen 7 5800H, 8 cores / 16 threads |
| Memory | approximately 13 GiB RAM plus 18 GiB swap |
| Disk | approximately 14 GB free at the initial audit |
| Codex | `codex-cli 0.145.0` at latest evidence |
| Java/Maven | OpenJDK 21.0.11, Maven 3.6.3; project-specific JDKs also installed |
| Node/browser | Node 22.16.0, Playwright 1.60.0, Chromium cache installed |
| Docker | 29.1.3 |
| Workflow | global/project AGENTS, custom skills, Git, `dev`, Playwright, screenshots in `/home/jose/Imágenes` |

The laptop remains the current configuration reference, not a directory tree to copy. Parity is assessed by outcomes and versioned context.

## Current Atenea control plane

| Item | Observed baseline |
|---|---|
| Host | DigitalOcean Premium Intel |
| Capacity | 4 vCPU, 7.8 GiB RAM, no swap |
| Disk | 232 GB total, 172 GB available |
| Runtime | Ubuntu 24.04, Docker prod/preview backend, PostgreSQL, Caddy and four Codex App Server/rescue containers |
| Codex image | `codex-cli 0.130.0` in prod and preview |
| Persistence | PostgreSQL WorkSession/SessionTurn/AgentRun, Codex homes and repositories on host mounts |
| Exposure | ports 80/443 and SSH expected; host also listens publicly on 8081, 8082 and 8083 and requires a later exposure review |

Current application invariants:

- one open WorkSession per project;
- one running AgentRun per session;
- session-owned branch and persisted Codex thread;
- App Server completion is followed by an in-process future;
- backend startup currently marks all still-running AgentRuns failed;
- `Project.repoPath` is validated as an absolute path beneath one configured workspace root;
- Codex App Server containers mount the full repository root and Codex home;
- current Codex containers do not expose port 8092 publicly, but production backend ports bind all interfaces;
- current container definitions have no global four-job scheduler and no CPU/PID limits.

## AX42 worker baseline

| Item | Verified value |
|---|---|
| Host | Hetzner AX42-1, Falkenstein |
| OS | Ubuntu 24.04.4 LTS, kernel 6.8.0-134 |
| CPU | Ryzen 7 PRO 8700GE, 8 cores / 16 threads |
| Memory | 61 GiB available to Linux |
| Swap | 30 GiB mirrored swap |
| Storage | 2 x 512 GB NVMe, approximately 437 GB root filesystem and 413 GB free |
| RAID | `md0`, `md1`, `md2` RAID 1, both members `[UU]`, initial resync complete |
| Listening services | public SSH on IPv4/IPv6 only; local resolver |
| Firewall | UFW installed but inactive |
| SSH effective state | root key login allowed, password authentication globally enabled, X11 forwarding enabled |
| Installed development tools | only Git from the planned set; Docker, Node, Java, Maven, Codex, OpenSpec, Tailscale, smartctl and `rg` absent |
| Local users | only root for routine administration at baseline |

This state is acceptable only as a short bootstrap baseline. It is not an accepted production worker configuration.

## Repository readiness inventory

| Project | Laptop state | Current Atenea copy | Runtime/data | Migration risk |
|---|---|---|---|---|
| Yvateve | `agent/geographic-demand-phase1` at `26c2f69`; three untracked paths | missing | Docker app + PostgreSQL, preview on 8080 | import canonical remote; classify spreadsheet/test/tmp artifacts before sync |
| Beautips | clean `main` at `a6d2f28` | clean `main` at `bd15a16` | Docker app + PostgreSQL + Redis; persistent assets/imports; optional WhatsApp/bootstrap secrets | reconcile different commits and prepare deterministic non-production fixtures |
| ISC | clean `master` at `48dff803` | clean `master` at `8b270ef4` | Maven multi-module; JDK 17 build, JDK 8/Tomcat 8 runtime; EN/DE/FR/IT/ES variants; file storage | large divergence, legacy toolchain and per-language runtime/origin testing |
| Recambios | clean feature branch `feature/integrar-nacex-recoger-en-eshop` at `fbdd2a39` | clean `master` at `c5605fc3` | Maven/Tomcat 8; JDK 17 build and JDK 8 runtime; port 8095 locally | branch selection and legacy runtime/data dependencies |
| Fomasys | `master` at `0c08da5`, modified `AGENTS.md`; origin `yudries/fomasys` | dirty `atenea/session-3` at `5b98799`; origin `jlnieto/fomasys` | Docker Compose locally; app on 8090; existing browser tests | conflicting origin identity plus uncommitted work on both environments |
| Checkpol | feature branch at `92821d5`, 14 changed/untracked paths | clean `main` at `90b76b4` | Docker app + PostgreSQL; Stripe/SES/truststore integration; app on 8082 | active local feature must be committed/reconciled; secrets and external services must be stubbed |

No project is activated from a raw laptop copy. The onboarding authority is an explicitly reviewed remote, branch and commit, with uncommitted work resolved separately.

## Current dev contract and target mapping

Current command hash: `554616b186727c7757b6358e2054cf3b786b4671906b7af2f6f61a7865f0e5fa`.

| Current behaviour | Laptop implementation | Target authority |
|---|---|---|
| target resolution | hard-coded Bash case list | versioned project registry/manifests |
| repository location | `/home/jose/IdeaProjects/...` | worker workspace identity supplied by Atenea |
| Tomcat bases | shared paths under `~/.local/share/dev-tomcats` | session runtime namespace |
| ports | fixed 8090-8097 and related control ports | dynamically allocated private route; internal port remains project-defined |
| process manager | `systemd --user` per Tomcat | worker/runtime controller with durable ownership |
| Docker projects | Compose project per application | Compose/runtime namespace includes WorkSession identity |
| resource guard | max three Tomcats plus laptop memory/swap thresholds | global four-slot scheduler, heavy permits and enforceable cgroups |
| secrets | environment, `.bashrc` and local env files | named secret provider; never sourced implicitly from an interactive shell |
| health | port or project health URL | manifest health contract and structured state |
| logs | journal or Compose follow | session log stream and retained diagnostic artifact |
| URL | `localhost:<fixed-port>` | private preview URL plus generated localhost tunnel |
| output | human text | human text and stable `--json` schema |
| alerts | optional Telegram side effect | Atenea mobile/web events and notification policy |

The compatible command set remains `list`, `status`, `build`, `up`, `stop`, `restart`, `redeploy`, `logs`, `url` and `doctor`.

## Initial threat and exposure inventory

| Surface | Baseline threat | Required control |
|---|---|---|
| AX42 public SSH | root is the only admin; firewall inactive; password auth enabled globally | create named admin, prove key access, key-only policy, reduce root routine access, firewall and break-glass test |
| Worker API | forged dispatch or cancellation | private bind, mutual authenticated identity, replay/idempotency protection and least-privilege endpoints |
| Codex App Server | danger-full-access execution and thread control | never public; worker-mediated access and sanitized auth state |
| Docker control | socket grants effective host root | do not mount host socket into Codex; prove mediated sandbox |
| Repositories | cross-session modification or unsafe reset | session worktrees, path boundary, ownership checks and no automatic dirty-state overwrite |
| Previews | unauthenticated application/data exposure | tailnet/private proxy by default; explicit time-bounded public sharing only |
| Secrets | leakage through Git, prompts, logs, mounts or artifacts | named secret boundary, redaction and session-scoped exposure |
| Shared caches | dependency poisoning or cross-session mutation | non-authoritative caches, controlled ownership and rebuildability |
| Artifacts | screenshot/report cross-project disclosure | session/run metadata, authenticated access and retention policy |
| Backups | same-host loss, unencrypted credentials or untested restore | external encrypted target, least privilege and restore exercise |
| Control-plane ports | 8081-8083 bind public interfaces | confirm provider/firewall/reverse-proxy need and restrict direct access in a dedicated operations change |

## Pre-existing documentation/runtime drift

- Atenea had no OpenSpec stable capabilities before this programme.
- Current docs describe a same-VPS App Server model and host-local repository paths; remote worker behaviour is not implemented.
- Startup reconciliation is correct for current in-process execution but incompatible with durable remote execution until migrated behind an execution-target distinction.
- The active Atenea feature worktree contains unrelated uncommitted web changes; this programme uses a clean worktree from `origin/main`.
- The current control-plane Codex version is behind the laptop version; parity must be versioned rather than assumed.
- Repository copies and branches differ materially across laptop and Atenea, especially Fomasys, ISC and Recambios.
