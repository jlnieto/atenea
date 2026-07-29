## ADDED Requirements

### Requirement: Development-only database classification

Every managed database target SHALL be classified before any database client,
container or filesystem mutation. Phase 7 SHALL accept only deterministic
synthetic development fixtures and SHALL reject production, preview,
real-project, unknown or production-like targets without probing their data.

#### Scenario: Production-like target is submitted

- **WHEN** a request names a production host, address, database identity,
  credential reference or non-synthetic project
- **THEN** the mediator rejects it before opening a connection or creating a
  resource and returns one fixed safe recovery action

#### Scenario: Existing worker database is discovered

- **WHEN** reconciliation observes Beautips or the retained Phase 3 database
  volume
- **THEN** it classifies the resource as foreign and leaves it unchanged

### Requirement: Immutable WorkSession database ownership

Each development database SHALL have one immutable database UUID bound to one
WorkSession, project, worker, runtime allocation fingerprint, rootless slot,
engine, manifest hash and labelled volume identity. Endpoints and resource
names MUST be derived from that record and MUST NOT be caller supplied.

#### Scenario: Two sessions use the same internal database port

- **WHEN** PostgreSQL or MariaDB listens on its standard internal port in two
  WorkSessions
- **THEN** the sessions receive distinct private networks, volumes and
  allocation-derived loopback endpoints

#### Scenario: Foreign session requests an operation

- **WHEN** a request repeats a database UUID with a different WorkSession,
  project, allocation or volume identity
- **THEN** the operation returns ownership conflict and leaves the database,
  snapshots and current lifecycle revision unchanged

### Requirement: Manifest-declared deterministic lifecycle

A versioned project manifest SHALL declare a supported pinned engine,
development-only classification, fixed migration and seed inputs, named secret
reference, health query, logical snapshot format and bounded retention.
Lifecycle execution SHALL use fixed argument arrays and SHALL NOT evaluate
manifest shell strings.

#### Scenario: Valid fixture is created

- **WHEN** an exact synthetic manifest requests create, migrate and seed
- **THEN** the worker creates one labelled private database, applies each
  versioned input once and reports a monotonic healthy revision

#### Scenario: Manifest contains unsafe authority

- **WHEN** the database contract contains a literal credential, absolute path,
  arbitrary command, host, port, socket, mount or unpinned image
- **THEN** schema validation fails before allocation or database execution

### Requirement: Explicit confirmed replacement

A destructive development replacement SHALL require a healthy exact-owned
database, a verified pre-replacement snapshot and a short-lived one-use
confirmation challenge bound to the current lifecycle revision.

#### Scenario: Operator confirms replacement

- **WHEN** the exact challenge is submitted before expiry against the unchanged
  revision
- **THEN** the worker snapshots current data, replaces only that synthetic
  database, consumes the challenge and records the result

#### Scenario: Replacement is unconfirmed or replayed

- **WHEN** confirmation is missing, expired, stale, mismatched or already used
- **THEN** no schema, row, volume, snapshot or lifecycle state is changed

### Requirement: Integrity-addressed snapshot and restore

Synthetic snapshots SHALL be private, engine-versioned, bounded by SHA-256 and
restorable only to the same immutable database ownership. At most three
synthetic snapshots or seven days of snapshots SHALL be retained. Raw dumps
and row data SHALL NOT be returned or attached.

#### Scenario: Exact snapshot is restored

- **WHEN** an ownership-valid restore names a retained snapshot whose engine,
  revision and SHA-256 match
- **THEN** the worker restores that WorkSession database and proves the
  expected schema/data identity without modifying Git

#### Scenario: Retention finds a foreign snapshot

- **WHEN** retention encounters absent, partial, foreign, ambiguous or
  authoritative ownership
- **THEN** it rejects that candidate unchanged and reports the ownership reason

### Requirement: Reconciliation and idempotent rollback

After mediator or rootless daemon restart, reconciliation SHALL inspect only
persisted exact-owned database records and SHALL NOT create, adopt, reassign or
replace a database implicitly. Rollback SHALL disable new operations and stop
exact synthetic containers while preserving labelled volumes, records and
snapshots until explicit cleanup.

#### Scenario: Worker restarts with an owned stopped database

- **WHEN** persisted intent is stopped or rollback-disabled
- **THEN** reconciliation reports the retained state without starting a
  container or changing slot ownership

#### Scenario: Rollback is repeated

- **WHEN** the same rollback executes again after exact synthetic containers
  are already stopped
- **THEN** it produces no additional deletion and preserves every foreign
  resource
