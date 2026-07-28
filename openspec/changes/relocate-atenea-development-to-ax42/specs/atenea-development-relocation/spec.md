## ADDED Requirements

### Requirement: Canonical relocation source
The Atenea development relocation SHALL start from an explicitly selected
GitHub repository, branch and commit. The worker MUST create or recover its
mirror and session-owned worktree from that canonical source and MUST NOT copy,
reset, discard or infer authority from a dirty control-plane worktree.

#### Scenario: Selected Atenea branch is activated
- **WHEN** the relocation begins after the operator has published the selected branch and entry commit
- **THEN** AX42 records that identity and creates the development worktree from GitHub without reading source from the control-plane worktree

#### Scenario: Canonical identity changes unexpectedly
- **WHEN** the remote branch no longer descends from the approved entry commit or the expected repository identity differs
- **THEN** relocation is blocked with an operator action and no mirror, worktree or runtime is made authoritative

### Requirement: AX42-owned Atenea development lifecycle
The worker SHALL execute the Atenea web build, backend build and tests,
development runtime, development PostgreSQL, development Codex service,
Playwright checks, logs and artifacts on AX42 within an admitted rootless slot
and session-owned namespaces. Project or Codex processes MUST NOT receive the
container-daemon socket, host root filesystem or unrelated workspaces.

#### Scenario: Atenea development lifecycle runs
- **WHEN** the schema-valid Atenea manifest is executed for an admitted development session
- **THEN** every process, container, network, port, mutable path, log and artifact is attributable to that session and the declared rootless slot

#### Scenario: Atenea requests an unsafe runtime feature
- **WHEN** its Compose input or command requests a fixed global identity, host namespace, daemon socket, device, privileged mode or undeclared host mount
- **THEN** the runtime manager rejects the request before creating any resource and reports the unsafe field

#### Scenario: Rootless slot cannot traverse the canonical worktree
- **WHEN** the admitted rootless slot cannot traverse the protected canonical worktree ancestors
- **THEN** the mediator verifies a byte-exact archive of the selected commit and mounts only a WorkSession/runtime-scoped ephemeral delivery without changing canonical path permissions or accepting another source identity

#### Scenario: Development Codex runs without authentication
- **WHEN** the private runtime starts the authentication-disabled Codex App Server
- **THEN** Codex listens only on container loopback and a fixed credential-free same-container proxy exposes its declared port solely through the internal runtime network and the slot's exact retained RootlessKit host-loopback publication

#### Scenario: Internal rootless network has no gateway publisher
- **WHEN** Docker cannot publish a port from the internal rootless bridge because it has no gateway endpoint
- **THEN** the fixed adapter registers only the three allocated `tcp4` mappings through the assigned slot's RootlessKit API, retains their returned identities and removes only those identities during mediated stop

#### Scenario: Stable socket proxy cannot preserve exec output
- **WHEN** the stable lifecycle socket proxy cannot return Docker's hijacked exec stream for SQL verification
- **THEN** only the fixed root-owned adapter may use the assigned slot's exact user-owned daemon socket for non-interactive read-only `psql`, and no daemon socket is exposed inside any container

### Requirement: Development-only Atenea data
The AX42 Atenea database SHALL be a development-only PostgreSQL instance
created from an empty schema by versioned migrations and explicit synthetic
fixtures. Production dumps, volumes, rows, endpoints and credentials MUST NOT
be copied or reachable from the worker.

#### Scenario: Development database is initialized
- **WHEN** the Atenea development runtime starts with a new owned database volume
- **THEN** Flyway applies the committed migrations and only declared synthetic operator and verification records are created

#### Scenario: Production-like data source is requested
- **WHEN** a fixture, environment or network target resolves to a production database identity, credential or endpoint
- **THEN** setup fails closed before connection or mutation and records a non-secret denial reason

### Requirement: Atenea development verification evidence
Relocation acceptance SHALL include the complete Atenea backend test suite,
web build, runtime health, persisted synthetic data checks, rendered DOM
assertions and inspected desktop and mobile screenshots. Evidence MUST identify
the source commit, manifest hash, slot, runtime identity and artifact integrity
without recording secret values.

#### Scenario: Operator console is accepted
- **WHEN** the synthetic development runtime is healthy
- **THEN** Playwright proves the authenticated operator state at `1440x900` and `390x844`, retains screenshots and confirms no clipping, overlap or unintended horizontal overflow

#### Scenario: One verification layer fails
- **WHEN** data, build, test, health, DOM or visual validation does not meet its declared expectation
- **THEN** relocation remains blocked with the failed layer and next action, even if the other layers pass

### Requirement: Production control-plane preservation
Relocating Atenea development MUST NOT change or restart Atenea production
web/mobile APIs, production PostgreSQL, production secrets, backups,
monitoring, rescue services, deployment authority, public routing or current
AgentRun execution. Sanitized non-impact sentinels SHALL be compared before and
after each mutating worker exercise.

#### Scenario: AX42 development runtime is started or stopped
- **WHEN** the relocation lifecycle mutates owned AX42 resources
- **THEN** production health, container and database sentinels remain unchanged and Atenea contains no routing to AX42

#### Scenario: Production mutation is requested
- **WHEN** a relocation command targets a production container, database, secret, deployment path or routing variable
- **THEN** the command is rejected before mutation and the phase remains disabled

### Requirement: Administrative development continuity
An accepted administrative Atenea development session on AX42 SHALL survive
laptop or SSH client disconnection and be resumable by the authorized operator.
This continuity MUST remain labelled as administrative and MUST NOT be treated
as managed AgentRun dispatch or lease evidence.

#### Scenario: Laptop disconnects during administrative development
- **WHEN** the operator detaches after the AX42 session has been accepted
- **THEN** the named session remains available and resumes with the same worktree and conversation context

### Requirement: Atenea development rollback and restart recovery
Rollback SHALL stop and remove only proven-owned ephemeral Atenea development
resources while preserving the mirror, worktree, Git state, logs and declared
artifacts. Repeating rollback MUST be idempotent, and restart reconciliation
MUST use persisted ownership records before recreating or removing resources.

#### Scenario: Relocation rollback is executed twice
- **WHEN** the runtime manager rolls back the same admitted Atenea development session two times
- **THEN** the first pass removes only its proven-owned ephemeral resources and the second removes nothing while retained state remains intact

#### Scenario: AX42 restarts with retained Atenea development state
- **WHEN** an explicitly authorized worker restart occurs after the development lifecycle has been recorded
- **THEN** reconciliation reports or safely recreates only the owned runtime and leaves production routing and unrelated worker resources unchanged
