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
named secrets and workload class. A project that declares development data
MUST also declare a versioned database engine, development-only
classification, pinned image, fixed migration/seed inputs, named credential
reference, health query, snapshot format and retention. Literal secrets,
host-specific home paths, privileged execution, host namespaces, arbitrary
host mounts, daemon socket mounts, caller database endpoints and shell database
commands MUST be rejected.

#### Scenario: Project is registered for execution

- **WHEN** Atenea attempts to activate a project on the worker
- **THEN** the runtime and optional database manifests are validated and
  unsupported, unsafe or missing requirements block activation before a run is
  accepted

#### Scenario: Manifest contains a literal credential

- **WHEN** a runtime or database manifest value matches a forbidden
  literal-secret field instead of a named secret reference
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

The worker SHALL allocate every runtime and development-database namespace
from the immutable WorkSession identity.

Ports, container names, networks, volumes, process identifiers, mutable
runtime data, database identities, Tomcat bases and logs MUST derive from an
immutable WorkSession identity and MUST NOT collide with another active
session. Allocation MUST support four normal sessions and no more than two
concurrent declared heavy operations by default. Database operations SHALL use
only the rootless slot persisted in that allocation.

#### Scenario: Two projects use the same internal port

- **WHEN** two sessions start applications or databases that use the same
  internal port
- **THEN** their private runtime namespaces, database volumes, allocation
  endpoints and preview routes remain independent

#### Scenario: Heavy-operation capacity is exhausted

- **WHEN** two heavy operations are active and another session requests one
- **THEN** the request remains queued with its capacity reason and no extra
  heavy process starts

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

Runtime start, health, preview publication, stop and cleanup operations SHALL
be idempotent and SHALL preserve declared artifacts and Git evidence before
removing ephemeral resources. Preview operations MUST derive their upstream
loopback port, runtime namespace and allocation fingerprint from the immutable
WorkSession record and MUST NOT accept caller-supplied alternatives.

#### Scenario: Worker restarts during preview

- **WHEN** the worker or preview coordinator restarts during an unexpired route
- **THEN** it reconciles only the persisted exact session runtime, reports
  `RECONCILING`, and restores the same route or records a blocked state

#### Scenario: Session is closed

- **WHEN** a reconciled WorkSession reaches its cleanup policy
- **THEN** exact-owned preview listeners and ephemeral runtime resources are
  removed without deleting retained attachments, branches or audit records

#### Scenario: Cleanup identity is incomplete

- **WHEN** a cleanup candidate lacks the full preview, session, worker,
  allocation and synthetic-fixture identity
- **THEN** cleanup rejects it unchanged and reports the ambiguous ownership

### Requirement: Project onboarding gate

Atenea SHALL be treated as AX42-resident for administrative development only
after its relocation gate, and SHALL become schedulable for real AgentRuns only
after `onboard-atenea-on-ax42` proves canonical GitHub source, exact project
allowlisting, real prompt/thread continuity, schema-valid manifest, isolated
empty development data, build, tests, runtime health, private preview,
desktop/mobile browser evidence, normal delivery/close, restart
reconciliation, rollback and production non-impact.

Yvateve, Beautips, ISC, Recambios, Fomasys and Checkpol SHALL become schedulable
only after their own independent build, runtime, health, preview, browser,
delivery and cleanup evidence passes. No cohort-wide enablement is permitted.

#### Scenario: Atenea real-project onboarding fails

- **WHEN** Atenea cannot prove any required source, execution, delivery,
  runtime, visual, rollback or production non-impact check
- **THEN** administrative development remains available as its fallback and no
  real Atenea AgentRun routing is enabled

#### Scenario: One project fails compatibility validation

- **WHEN** a project's independent compatibility gate fails
- **THEN** only that project remains disabled while already accepted projects
  remain available
