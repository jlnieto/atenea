# isolated-project-runtime Specification

## Purpose
TBD - created by archiving change establish-remote-codex-platform-program. Update Purpose after archive.
## Requirements
### Requirement: Session-owned repository workspace
Every remotely executed WorkSession SHALL operate in its own Git worktree created from its validated base branch and owned by its persisted workspace branch.

#### Scenario: First run for a session
- **WHEN** a session is assigned to the worker with a clean and valid base branch
- **THEN** the worker creates or recovers the session worktree without modifying another session or the canonical mirror

#### Scenario: Repository state is unsafe
- **WHEN** the expected branch, commit or worktree ownership cannot be proven
- **THEN** execution is blocked with the conflicting state and a recovery action rather than resetting or overwriting files

### Requirement: Versioned project runtime manifest
Each onboarded project MUST provide a schema-valid, versioned runtime manifest
defining canonical repository identity, runtime kind, pinned toolchains,
build/start/stop/health/preview/browser commands, internal ports, artifacts,
named secrets and workload class. Literal secrets, host-specific home paths,
privileged execution, host namespaces, arbitrary host mounts and daemon socket
mounts MUST be rejected.

#### Scenario: Project is registered for execution
- **WHEN** Atenea attempts to activate a project on the worker
- **THEN** the manifest is validated and unsupported, unsafe or missing requirements block activation before a run is accepted

#### Scenario: Manifest contains a literal credential
- **WHEN** a manifest value matches a forbidden literal-secret field instead of a named secret reference
- **THEN** validation fails without persisting or echoing the credential value

### Requirement: Compatible dev command surface
The remote environment SHALL provide `dev` commands for list, status, build, up,
stop, restart, redeploy, logs, url and doctor while requiring a resolvable
session/workspace identity for mutating operations. `dev --json` SHALL return a
versioned structured envelope containing the session, project, operation, state,
allocation, health, URL and actionable error without relying on human prose.

#### Scenario: Operator uses familiar command
- **WHEN** the operator runs `dev up recambios` inside a session workspace
- **THEN** `dev` resolves the active session manifest and starts only that session's runtime

#### Scenario: Atenea invokes dev programmatically
- **WHEN** Atenea or the worker invokes `dev --json` with an explicit session identity
- **THEN** the command returns stable structured state and does not rely on parsing human prose

#### Scenario: Session identity is ambiguous
- **WHEN** a mutating `dev` command cannot prove one active session and workspace
- **THEN** it fails closed with the required selection or recovery action and starts no runtime

### Requirement: Runtime namespace isolation
The worker SHALL allocate every runtime namespace from the immutable WorkSession
identity.

Ports, container names, networks, volumes, process identifiers, mutable runtime
data, Tomcat bases and logs MUST derive from an immutable WorkSession identity
and MUST NOT collide with another active session. Allocation MUST support four
normal sessions and no more than two concurrent declared heavy operations by
default.

#### Scenario: Two projects use the same internal port
- **WHEN** two sessions start applications that both listen internally on port 8080
- **THEN** their private runtime namespaces and allocated preview routes remain independent

#### Scenario: Heavy-operation capacity is exhausted
- **WHEN** two heavy operations are active and another session requests one
- **THEN** the request remains queued with its capacity reason and no extra heavy process starts

### Requirement: Host and cross-session boundary
Codex execution MUST NOT receive the host container-daemon socket, host root
filesystem, unrelated workspaces or unrelated session secrets. Runtime
operations SHALL pass through a default-deny mediator that verifies the caller,
session ownership, manifest and generated resource scope before using privileged
host facilities.

#### Scenario: Agent attempts unrelated workspace access
- **WHEN** a command attempts to read or modify a different session workspace or host-only path
- **THEN** the runtime boundary denies the operation and records a security-relevant event

#### Scenario: Manifest requests daemon authority
- **WHEN** a runtime requests the Docker socket, privileged mode, host namespaces, a device or an undeclared host mount
- **THEN** the mediator rejects the operation before creating any resource and reports the unsafe field

### Requirement: Controlled shared caches
Shared package and build caches SHALL remain non-authoritative, secret-free and protected from changing another session's source or runtime state.

#### Scenario: Corrupted cache entry is removed
- **WHEN** a shared cache is cleared or rebuilt
- **THEN** project source, persisted session state and delivery branches remain intact

### Requirement: Deterministic lifecycle and cleanup
Runtime start, health, stop and cleanup operations SHALL be idempotent and SHALL preserve declared artifacts and Git evidence before removing ephemeral resources.

#### Scenario: Worker restarts during preview
- **WHEN** the worker service restarts
- **THEN** it can recover or safely recreate the declared session runtime and report its resulting health

#### Scenario: Session is closed
- **WHEN** a reconciled WorkSession reaches its cleanup policy
- **THEN** ephemeral containers, ports and temporary data are removed without deleting retained artifacts, branches or audit records

### Requirement: Project onboarding gate
Yvateve, Beautips, ISC, Recambios, Fomasys and Checkpol SHALL become schedulable only after their own build, runtime, health, preview, browser and cleanup evidence passes.

#### Scenario: One project fails compatibility validation
- **WHEN** a project's representative runtime or browser check fails
- **THEN** that project remains disabled on the worker while already accepted projects remain available
