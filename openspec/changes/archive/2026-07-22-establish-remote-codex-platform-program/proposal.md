## Why

Atenea currently executes Codex beside its control plane on a resource-constrained VPS, while the operator's complete and trusted development environment remains tied to a laptop that must stay powered on. The new dedicated AX42 makes it possible to separate orchestration from execution and provide durable, mobile-operable Codex work, but that migration needs one versioned contract so security, repository safety, browser verification and four-job concurrency are not implemented as unrelated server tweaks.

## What Changes

- Establish a stable programme contract for moving Codex execution from the current Atenea host and the operator laptop to one dedicated remote worker while retaining Atenea as the control plane.
- Define independently releasable implementation phases for worker hardening, project runtime isolation, remote execution routing, private previews, project onboarding and operational resilience.
- Require unattended runs to continue when the laptop disconnects and remain observable and steerable from Atenea web and Android surfaces.
- Require up to four concurrent project sessions with bounded CPU, memory, process and heavy-build concurrency instead of unbounded container execution.
- Preserve the useful `dev` operator contract while replacing laptop-specific paths, fixed shared runtimes and host assumptions with session-aware project manifests and isolated workspaces.
- Define private browser previews, SSH compatibility tunnels and worker-side Playwright evidence without exposing development ports or Codex App Server publicly.
- Define configuration parity for Codex instructions, skills and toolchains without copying authentication or other secrets as ordinary files.
- Define RAID health, backups, monitoring, recovery, cleanup and capacity evidence required before the worker becomes authoritative.
- Onboard Yvateve, Beautips, ISC, Recambios, Fomasys and Checkpol through explicit per-project verification gates.
- This programme change establishes requirements and delivery governance only; it does not switch production execution or alter existing APIs by itself.

## Capabilities

### New Capabilities

- `remote-worker-control`: Worker identity, health, capacity, dispatch, cancellation, recovery and bounded scheduling under the Atenea control plane.
- `isolated-project-runtime`: Session-owned workspaces, project runtime manifests, safe `dev` compatibility, resource isolation and lifecycle management.
- `private-development-preview`: Private manual previews, compatibility tunnels and Playwright evidence accessible from approved laptop and mobile devices.
- `codex-environment-parity`: Versioned Codex instructions, skills, toolchains and authentication boundaries needed to reproduce the trusted laptop workflow remotely.
- `remote-work-continuity`: Durable conversation, execution and artifact state that continues without the initiating client and can be resumed from another operator surface.
- `worker-operational-safety`: Host hardening, private networking, storage health, backup, observability, cleanup, rollback and capacity acceptance requirements.

### Modified Capabilities

None. Atenea has no existing stable OpenSpec capabilities; this programme creates the initial contracts that later implementation changes will refine.

## Impact

- Atenea backend execution orchestration, `AgentRun` persistence/reconciliation, project repository resolution and operator read models.
- Atenea web and Android surfaces for worker state, previews, artifacts, actionable failures and reconnection.
- Codex App Server packaging, authentication guard, runtime versions and shared operational context.
- New AX42 worker, existing Atenea VPS, private network policy, Docker workloads, Git worktrees and project caches.
- Project runtime definitions for Yvateve, Beautips, ISC, Recambios, Fomasys and Checkpol.
- Operational documentation, deployment automation, secrets handling, backup targets and monitoring.
- No runtime migration occurs until a later phase passes compatibility, security, project and rollback gates.
