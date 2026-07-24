## MODIFIED Requirements

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
