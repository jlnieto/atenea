## Context

Atenea currently combines a durable Spring Boot/PostgreSQL control plane with Codex App Server containers and repositories mounted on the same 4-vCPU/8-GB VPS. `Project.repoPath` identifies one host-local repository, `WorkSession` owns its branch and conversation, `SessionTurn` is the visible history, and `AgentRun` traces one Codex turn. The backend permits one open session per project and one running run per session, but there is no global scheduler, worker identity, queue, resource isolation or remote workspace contract. Startup reconciliation currently marks every running run failed because execution and control-plane lifetime are assumed to be the same.

The operator's richer environment is on a Ryzen 7 5800H laptop with 13 GB RAM, established `AGENTS.md` rules, custom skills, a `dev` command, several Java/Tomcat generations, Docker projects, Playwright and Chromium. That workflow is comfortable but disappears when the laptop is closed. The new Hetzner AX42 provides 8 cores/16 threads, 64 GB RAM, mirrored NVMe storage and a persistent host suitable for execution. The existing Atenea VPS already provides web, Android, authentication, push events, conversation persistence, uploads and delivery flows and must remain the control plane.

The initial project set is Yvateve, Beautips, ISC, Recambios, Fomasys and Checkpol. They are heterogeneous: some use Docker Compose, ISC and Recambios depend on legacy Tomcat/JDK combinations, and their local and Atenea copies are not all on identical branches. Several laptop repositories also contain uncommitted work, so migration cannot be a blind directory copy.

This is a programme foundation. It defines the target contracts and phase boundaries; runtime changes are delivered by separate, short-lived OpenSpec changes.

## Goals / Non-Goals

**Goals:**

- Keep Atenea authoritative for projects, sessions, turns, runs, delivery state and operator access while moving repository execution to the AX42.
- Continue an accepted run independently of the initiating laptop or mobile connection and allow another authenticated surface to observe and continue the session.
- Support four concurrent project sessions without unbounded competition, while preserving one-running-run-per-session and current Git delivery invariants.
- Give every session an isolated worktree, runtime namespace, logs, artifacts and private preview.
- Preserve the familiar `dev` vocabulary as a human and Codex interface while replacing laptop-only implementation assumptions.
- Reproduce the useful Codex rules, skills, tools and screenshot workflow through versioned configuration and explicit authentication.
- Make browser verification possible both manually from the laptop/mobile and autonomously with worker-side Playwright.
- Establish measurable security, storage, backup, recovery, cleanup and capacity gates before cutover.
- Migrate project by project with a bounded fallback and without moving an active session between executors.

**Non-Goals:**

- Move PostgreSQL, Atenea web, Android APIs or the public control plane to the AX42 in this programme.
- Run the language model locally; Codex inference remains an OpenAI service.
- Introduce Kubernetes, a general-purpose CI platform or an unrestricted public development hosting service.
- Permit multiple open WorkSessions for the same project; the existing project-level invariant remains until a separate product change modifies it.
- Copy the laptop home directory, browser profile, `auth.json` or shell history wholesale.
- Make RAID a substitute for external backup.
- Reconcile or overwrite uncommitted laptop work automatically.

## Decisions

### 1. Atenea remains the control plane and the AX42 becomes a registered worker

The existing VPS owns durable product state and public/mobile APIs. A worker service on the AX42 owns execution leases, workspaces, runtime processes and artifacts. Atenea addresses projects and sessions logically; it does not rely on a remote absolute path being meaningful on the control-plane host.

The worker protocol will expose authenticated health/capacity, idempotent dispatch, progress, cancellation, reconciliation and artifact metadata. Codex App Server remains a private worker implementation detail rather than a public endpoint.

**Alternative considered:** point the current backend WebSocket directly at one remote Codex App Server. Rejected because it does not solve admission control, workspace lifecycle, project manifests, cancellation ownership, previews or restart reconciliation.

### 2. Runs use durable dispatch records and renewable worker leases

An accepted `AgentRun` receives an immutable dispatch identifier, selected worker, execution workspace identity and lease state. Dispatch is idempotent. The worker heartbeats while it owns the run; Atenea records progress and terminal results. A control-plane restart or temporary network loss moves a run to reconciliation, not immediately to failed. The worker is queried before a terminal decision is made.

One running run per WorkSession remains mandatory. Sessions are pinned to an execution target while open so a Codex thread and workspace are not silently split across workers.

**Alternative considered:** reuse the current in-memory completion future as the authority. Rejected because its lifetime is the backend process and it cannot survive a disconnected control plane.

### 3. Scheduling is bounded by slots and workload class

The first worker advertises four normal execution slots. Runtime manifests classify operations such as compilation, image build and Playwright as heavy. At most two heavy operations run concurrently by default, and all limits are configurable from versioned worker policy. The host retains explicit CPU, memory and disk headroom for SSH, the worker daemon, monitoring and recovery.

Admission queues excess work with a visible reason and position; it does not start unbounded containers. The acceptance test includes four simultaneous representative sessions and two simultaneous heavy builds.

**Alternative considered:** rely on Linux swapping and Docker defaults. Rejected because the current Atenea containers have no global CPU, memory or PID policy and saturation would make cancellation and recovery unreliable.

### 4. A WorkSession owns an isolated Git worktree and runtime namespace

The worker maintains canonical repository mirrors and creates a worktree for each WorkSession under a stable session identity. The existing `workspaceBranch`, base-branch, publish, merge and close invariants remain authoritative. Runtime names, networks, ports, temporary data and logs include the session identity and cannot collide with another session.

Codex receives only the session workspace plus approved shared context and caches. It does not receive the host root filesystem or the host Docker socket. Runtime operations are mediated by the worker/runtime controller.

**Alternative considered:** one mutable checkout and fixed service per project. Rejected because it reproduces the laptop model and prevents safe concurrency, recovery and cleanup.

### 5. `dev` is retained as a compatibility CLI over versioned runtime manifests

Each project defines a versioned manifest containing runtime type, build, start, stop, health, browser verification, artifacts, workload class, required toolchains and named secrets. The worker consumes the same contract as `dev`.

The CLI keeps familiar commands (`up`, `stop`, `status`, `logs`, `url`, `doctor`) and adds explicit session/workspace selection and structured `--json` output. Interactive use can infer the current session, but Atenea always supplies an explicit identity. Laptop-specific paths, secrets sourced from `.bashrc`, fixed shared Tomcat bases and a hard-coded maximum of three Tomcats are not carried forward.

Legacy Tomcat applications are initially packaged behind the same manifest contract; exact container images and compatibility details are proven per project rather than forcing an all-at-once rewrite.

**Alternative considered:** discard `dev` and let every agent improvise project commands. Rejected because the current command captures valuable project knowledge and provides a stable operator vocabulary.

### 6. Tailscale is the initial private network and public exposure is deny-by-default

The AX42, Atenea VPS, operator laptop and operator mobile join one tailnet with tagged devices and least-privilege access rules. Worker API, Codex App Server and previews bind to loopback or private interfaces. Public SSH is retained only for bootstrap/break-glass under key-only policy and may be restricted further after private access is proven.

Tailscale Serve or a private reverse proxy provides HTTPS preview access inside the tailnet. Funnel or another public route requires an explicit, time-bounded operator action and is never the default.

**Alternative considered:** raw WireGuard configuration. Rejected for the initial deployment because mobile enrollment, NAT traversal, device identity, DNS and access policy would require additional custom operations. A later migration to self-managed coordination remains possible because the data plane is WireGuard-based.

### 7. Preview and evidence are first-class session resources

Each running preview has a session-scoped status and URL. The normal manual path is a private tailnet URL from the laptop or mobile. An SSH local-forward command is generated for applications that require `localhost`, fixed cookies or callback origins.

Worker-side Playwright performs DOM and visual checks at the required desktop and mobile viewports. Screenshots, traces and reports are registered as session artifacts. “Latest screenshot” resolves within the current session and source, with an ordered history for “last N”; it does not depend on a global desktop folder on the worker.

### 8. Environment parity is declarative and authentication is independent

Global operating rules, project `AGENTS.md`, custom skills, plugin declarations, model policy and tool versions are assembled into a versioned worker context. Project manifests declare JDK, Maven, Node, browser and runtime requirements. Shared Maven/npm/Docker caches improve performance but are not sources of truth.

The worker authenticates Codex interactively using the operator's intended ChatGPT mode and retains the existing sanitized auth-status guard. Tokens, private keys and connector credentials are provisioned through a secret boundary and are not copied from the laptop as ordinary repository files. Updating Codex or OpenSpec is a reviewed manifest/image change with compatibility evidence.

### 9. Recovery authority is reconciled, not inferred from process lifetime

Worker and control-plane state are compared after restart. A worker that still owns a live execution may resume reporting it; a finished execution may deliver its terminal result idempotently; a lost lease is failed only after a bounded reconciliation window. Cancellation is an explicit state transition with acknowledgement and forced cleanup fallback.

The current startup rule that fails all `RUNNING` AgentRuns must be replaced only when the worker protocol and migrations are ready. Existing locally executed sessions continue using the old rule until their routing mode changes.

### 10. The programme uses a foundation plus short-lived implementation changes

This change creates stable capabilities and a programme ledger. Implementation is split into:

1. `bootstrap-secure-codex-worker`
2. `establish-project-runtime-contract`
3. `route-agent-runs-to-remote-worker`
4. `add-private-session-previews`
5. project onboarding changes, starting with one pilot and separating modern Docker from legacy Tomcat risks
6. `harden-worker-operations`

Each phase has tests, deployment evidence, rollback and archival before the next phase becomes authoritative. Findings are assigned to the current phase, a future phase or an independent change; they do not silently expand an active change.

## Risks / Trade-offs

- **A worker service adds protocol and persistence complexity** → Start with one worker, explicit leases and a small state machine; do not design multi-region scheduling prematurely.
- **Tailscale becomes an operational dependency** → Keep public key-only break-glass access and export/document ACL and enrollment configuration; evaluate self-managed coordination only after the primary path works.
- **Legacy projects may resist container isolation** → Prove ISC and Recambios in dedicated onboarding changes with representative data and retain SSH tunnel compatibility.
- **A compromised danger-full-access agent could attack neighbouring work** → Never mount the host Docker socket or unrelated workspaces; mediate runtime actions and enforce OS/container resource and filesystem boundaries.
- **Shared caches can leak or corrupt state** → Share only content-addressed/package caches with controlled ownership; keep workspaces, runtime data and secrets session-scoped.
- **Four nominal slots may still saturate eight physical cores** → Admit only two heavy workloads by default, retain host headroom and use measured capacity gates before raising limits.
- **Control-plane/worker partitions create ambiguous status** → Show `RECONCILING`/`UNREACHABLE` as distinct actionable states and use idempotent terminal delivery.
- **Existing dirty or divergent repositories can lose work** → Import from reviewed Git commits/branches only; inventory and reconcile laptop changes before project activation.
- **Preview hostnames can break cookies, OAuth or callbacks** → Provide per-project origin configuration and SSH localhost forwarding as a tested fallback.
- **RAID can create false confidence** → Monitor both members and require external encrypted backups plus restore exercises.
- **The current Atenea branch contains unrelated uncommitted UI work** → Develop the programme in its own worktree from `origin/main` and merge through a reviewed branch.

## Migration Plan

1. Validate and archive this programme foundation into stable OpenSpec capabilities without changing runtime.
2. Record a reproducible baseline of laptop workflow, current Atenea execution, project repository state and AX42 hardware/security.
3. Apply `bootstrap-secure-codex-worker`; verify private access, break-glass access, RAID/SMART, updates, monitoring and an infrastructure rollback.
4. Apply `establish-project-runtime-contract`; prove isolated dummy sessions and the compatibility `dev` interface without routing production AgentRuns.
5. Apply `route-agent-runs-to-remote-worker` behind an execution-target switch. Pin each new WorkSession to one executor and keep the existing executor available for bounded fallback.
6. Apply private previews and Playwright artifact delivery; validate laptop and Android access without public development ports.
7. Onboard one low-risk pilot project end to end. Validate prompt, continued execution after client disconnect, preview, tests, screenshot, publish, merge reconciliation and cleanup.
8. Onboard modern Docker projects, then ISC and Recambios through separate compatibility evidence.
9. Run four-session capacity, restart, network-partition, cancellation, disk-pressure and restore exercises.
10. Make the worker the default only for newly opened sessions after all gates pass. Existing active sessions finish on their pinned executor.
11. Retire the old execution path only after the fallback window and operational evidence are complete.

Rollback never moves an active workspace implicitly. New-session routing returns to the previous executor, affected worker jobs are reconciled or cancelled, and Git branches/worktrees remain available for manual recovery.

## Open Questions

- Which identity provider and ownership model will administer the production tailnet and recovery credentials?
- Which external encrypted backup target will be used and what retention/restoration objectives are required?
- Whether Checkpol or Beautips is the first pilot after comparing authentication, data and browser-test complexity.
- Which legacy project origins, cookies and callbacks require localhost tunnels rather than tailnet URLs.
- Whether the first isolated runtime uses one mediated rootless engine per slot or another sandbox that proves equivalent host-socket and cross-session protection.
- What bounded reconciliation and preview-retention durations best fit real long-running Codex work; these must be measured before becoming production defaults.
