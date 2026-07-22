## ADDED Requirements

### Requirement: Registered worker identity and capability
Atenea SHALL dispatch execution only to an enabled worker with an authenticated identity, current health, supported protocol version and declared capacity.

#### Scenario: Healthy compatible worker is eligible
- **WHEN** a worker authenticates, reports a supported protocol version and renews its heartbeat within the configured window
- **THEN** Atenea includes it in scheduling with its declared slots and workload capabilities

#### Scenario: Stale or incompatible worker is excluded
- **WHEN** a worker heartbeat expires or its protocol version is unsupported
- **THEN** Atenea excludes it from new dispatch and exposes an actionable unavailable reason

### Requirement: Idempotent durable dispatch
Every remotely executed AgentRun MUST have a durable dispatch identity, selected worker, session workspace identity and idempotency contract before execution starts.

#### Scenario: Dispatch is retried after a network timeout
- **WHEN** Atenea repeats a dispatch request with the same dispatch identity
- **THEN** the worker returns the existing execution rather than starting a duplicate

### Requirement: Session execution affinity
An open WorkSession SHALL remain pinned to one execution target and workspace unless an explicit, safe migration procedure is completed while no run is active.

#### Scenario: Later turn continues the session
- **WHEN** an operator submits another turn to an open WorkSession
- **THEN** Atenea routes it to the same execution target and workspace that own the session's Codex thread

### Requirement: Bounded global scheduling
The worker scheduler SHALL enforce a configurable maximum of four normal concurrent execution slots and a separate configurable limit for heavy operations.

#### Scenario: All normal slots are occupied
- **WHEN** a fifth eligible run is submitted while four normal slots are occupied
- **THEN** the run remains queued with visible admission state and does not start another unbounded runtime

#### Scenario: Heavy-operation limit is reached
- **WHEN** the configured heavy-operation permits are in use
- **THEN** an additional build, image-build or browser workload waits without blocking lightweight progress reporting

### Requirement: Per-session sequential execution
Atenea MUST preserve the existing invariant that no more than one AgentRun executes for a WorkSession at a time.

#### Scenario: Second turn arrives during a run
- **WHEN** a WorkSession already owns a non-terminal AgentRun
- **THEN** Atenea rejects or queues the second executable turn according to an explicit operator-visible policy and never runs both concurrently

### Requirement: Observable execution lifecycle
The control plane SHALL expose queued, starting, running, cancelling, reconciling and terminal execution state with timestamps, worker identity and actionable failure information.

#### Scenario: Worker reports progress
- **WHEN** a worker acknowledges or advances an execution
- **THEN** Atenea persists and publishes the latest lifecycle state without exposing credentials or raw secret-bearing payloads

### Requirement: Cancellation ownership
An authenticated operator SHALL be able to request cancellation, and the worker SHALL acknowledge graceful termination or report forced cleanup after a bounded timeout.

#### Scenario: Running job accepts cancellation
- **WHEN** the operator cancels a running AgentRun
- **THEN** its processes and session runtime are stopped safely, terminal state is persisted idempotently and unrelated sessions continue

### Requirement: Restart and partition reconciliation
Atenea MUST reconcile non-terminal remote runs with worker state after a backend restart or connectivity interruption before declaring them failed.

#### Scenario: Backend restarts while worker keeps running
- **WHEN** Atenea restarts and the worker still holds a valid lease for the AgentRun
- **THEN** Atenea restores observation of that run without starting a duplicate or marking it failed solely because of the restart

#### Scenario: Worker cannot be reached
- **WHEN** the worker is unreachable during the reconciliation window
- **THEN** the run is shown as reconciling or unreachable until the bounded policy determines a recoverable or terminal outcome
