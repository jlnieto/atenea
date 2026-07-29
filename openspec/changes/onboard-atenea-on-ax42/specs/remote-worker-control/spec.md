## MODIFIED Requirements

### Requirement: Registered worker identity and capability

Atenea SHALL dispatch execution only to an enabled worker with an authenticated
identity, current health, supported protocol version, declared capacity and an
exact compatible workload capability. A real-project capability SHALL name a
versioned workload kind and SHALL be enabled only for project identities whose
individual onboarding change has passed.

#### Scenario: Healthy compatible worker is eligible

- **WHEN** a worker authenticates, reports a supported protocol version,
  renews its heartbeat and advertises the exact selected workload capability
- **THEN** Atenea includes it in scheduling with its declared normal slots,
  heavy permits and project-scoped capability

#### Scenario: Stale or incompatible worker is excluded

- **WHEN** heartbeat, protocol, workload kind or exact project capability is
  stale, absent or incompatible
- **THEN** Atenea excludes it from new dispatch and exposes an actionable
  unavailable reason

#### Scenario: Unauthenticated registration is rejected

- **WHEN** a caller omits or presents an invalid worker credential
- **THEN** the worker and Atenea reject the exchange without creating or
  changing a registry, workspace or execution record

### Requirement: Idempotent durable dispatch

Every remotely executed AgentRun MUST have a durable dispatch identity,
selected worker, session workspace identity, project/workload fingerprint and
idempotency contract before execution starts. Real-project dispatch MUST bind
operator input to the persisted project/workspace and MUST NOT accept caller
commands, paths, remotes, endpoints or environment.

#### Scenario: Dispatch is retried after a network timeout

- **WHEN** Atenea repeats an identical dispatch request with the same dispatch
  identity and workload fingerprint
- **THEN** the worker returns the existing execution and lifecycle revision
  rather than starting another Codex turn

#### Scenario: Dispatch identity is reused with conflicting input

- **WHEN** a request reuses a dispatch identity with a different session,
  workspace, project, prompt or workload fingerprint
- **THEN** the worker rejects it fail-closed and preserves the original
  execution unchanged
