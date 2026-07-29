## MODIFIED Requirements

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
