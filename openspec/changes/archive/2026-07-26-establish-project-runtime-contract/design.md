## Context

The accepted AX42 has 8 cores/16 threads, 61 GiB RAM, 30 GiB swap and about
412 GiB free on RAID 1. It has Git and tmux but no Codex, container engine,
browser, JDK or project build tools. Atenea and the operator reach it privately
through Tailscale; public SSH remains key-only break-glass.

The laptop `dev` command supports Docker Compose and legacy Tomcat projects, but
its configuration contains absolute home paths, fixed ports, shared user
services and, in at least one case, credentials embedded in JVM options.

## Goals / Non-Goals

**Goals:**

- Preserve the useful `dev` command surface without preserving laptop coupling.
- Make four normal sessions and two heavy operations schedulable without port,
  process, worktree or secret collisions.
- Prove a Docker-compatible boundary without granting Codex daemon authority.
- Support modern Compose and JDK 17 build/JDK 8 plus Tomcat 8 legacy fixtures.
- Establish independent, auditable Codex installation, authentication and
  non-secret context on the AX42.
- Let the operator begin an explicitly administrative persistent Codex session
  before Atenea routing is implemented.

**Non-Goals:**

- Route a production WorkSession or change Atenea persistence.
- Onboard a real project or import uncommitted laptop state.
- Publish previews or Codex endpoints to the Internet.
- Complete the final backup, alerting or seven-day capacity phase.

## Decisions

### 1. Separate the immediate bridge from the managed executor

The operator may run the official Codex CLI as `jose` inside named tmux sessions
over private SSH. The process and its local Codex conversation survive laptop
disconnects. This is an administrative bridge: `jose` is a sudo administrator,
so it cannot prove the managed sandbox boundary and will not be used by Atenea.

The managed executor will later start Codex inside a session-scoped sandbox from
versioned configuration and will persist the external thread identity through
the worker protocol.

### 2. Use declarative manifests, not copied laptop configuration

Each project manifest has a schema version and declares repository identity,
runtime kind, pinned toolchains, lifecycle commands, internal service ports,
health, preview, browser checks, artifacts, named secrets and workload class.
Host paths, literal secret values, privileged containers, host networking and
arbitrary host mounts are invalid.

Existing laptop `.conf` files are discovery input only. They are not copied to
the worker or treated as the target contract.

### 3. Preserve `dev` as a resolver and client

`dev` discovers the active workspace manifest and session identity, then asks
the local runtime manager to perform validated operations. Human output remains
concise; `--json` returns a versioned envelope with session, project, operation,
state, allocation, health, URL and actionable error fields. An ambiguous or
missing session fails closed.

### 4. Mediate Docker instead of exposing its socket

Docker compatibility is retained for existing Compose projects, but neither the
Codex sandbox nor a project container receives the host daemon socket. A small
root-owned manager accepts only schema-valid operations for a proven session,
generates restrictive Compose overrides and owns daemon calls.

The manager rejects privileged mode, host PID/IPC/network, devices, Docker
socket mounts, undeclared bind mounts, unsafe capabilities and cross-session
resource names. Agent access to the manager is scoped to its session identity.
The prototype must pass boundary tests before Docker is considered accepted.

### 5. Use session-scoped source and runtime identities

Canonical repositories are mirrors under `/srv/atenea/repositories`. Each
WorkSession receives a persistent Git worktree, branch and runtime identity
under `/srv/atenea/workspaces`. Ports, Compose project/network/volume names,
Tomcat bases, logs and artifacts derive from the immutable session identity,
not only from the project name.

Shared caches contain no secrets or authoritative source and can be rebuilt.
Four normal execution permits and two heavy-operation permits are the initial
limits.

### 6. Keep authentication and context promotion independent

Codex is installed from an official pinned release and authenticated on the
worker using the supported device flow. No laptop `auth.json`, session history,
SSH key or whole `~/.codex` tree is copied.

Global instructions, reviewed skills and configuration are promoted through an
allowlist. Authentication status, CLI version, required tools and effective
context hashes are checked without printing token material.

### 7. Treat browser tooling as runtime capability, not desktop state

Chromium and Playwright run on the worker with finite timeouts and
session-scoped artifacts. Private preview publication belongs to the next
preview phase; during this phase health/browser fixtures are reached locally on
the worker and evidence is retrieved over SSH.

## Migration Plan

1. Add schema, examples and negative validation tests.
2. Add idempotent package/Codex installation and sanitized parity checks.
3. Enable the administrative Codex/tmux bridge and validate disconnect/resume
   without using a real project secret.
4. Install the container/browser/Java prerequisites required by dummy fixtures.
5. Implement session allocation, worktree handling and the `dev` client.
6. Implement the mediated runtime manager and two fixture projects.
7. Run lifecycle, isolation, reboot reconciliation and cleanup evidence.
8. Archive only while Atenea production routing remains unchanged.

Rollback stops and disables the runtime manager, removes only proven-owned dummy
resources, preserves worktrees/artifacts for inspection and uninstalls or
disables the administrative bridge configuration. Atenea continues on its
existing executor throughout.

## Risks / Trade-offs

- A mediator can become an indirect Docker escape if validation is incomplete:
  default-deny every Compose feature and test malicious manifests.
- The administrative bridge has broader host authority than the target executor:
  label it visibly, keep it private and never use it for Atenea dispatch.
- Legacy JDK 8/Tomcat dependencies may require archived binaries: verify
  provenance and checksums before installation.
- Shared Codex credentials could become readable by project commands: the
  managed executor must isolate credential access from project containers and
  redact diagnostics.
- Fixed laptop assumptions may be hidden in application callbacks/cookies:
  defer project acceptance until its preview/browser gate proves the declared
  origin.

## Open Questions

- Exact per-project CPU/RAM/runtime limits after representative measurements.
- Whether the accepted managed sandbox is a session container plus mediator or
  a systemd/namespace equivalent; the boundary tests, not implementation
  preference, decide.
- Final external secret backend and rotation workflow before ISC onboarding.
