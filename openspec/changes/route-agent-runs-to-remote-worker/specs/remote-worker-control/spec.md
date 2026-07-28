## MODIFIED Requirements

### Requirement: Registered worker identity and capability
Atenea SHALL dispatch execution only to an enabled worker with an authenticated identity, current health, supported protocol version and declared capacity.

#### Scenario: Healthy compatible worker is eligible
- **WHEN** a worker authenticates, reports a supported protocol version and renews its heartbeat within the configured window
- **THEN** Atenea includes it in scheduling with its declared normal slots, heavy permits and workload capabilities

#### Scenario: Stale or incompatible worker is excluded
- **WHEN** a worker heartbeat expires or its protocol version is unsupported
- **THEN** Atenea excludes it from new dispatch and exposes an actionable unavailable reason

#### Scenario: Unauthenticated registration is rejected
- **WHEN** a caller omits or presents an invalid worker credential
- **THEN** the worker and Atenea reject the exchange without creating or changing a registry or execution record

### Requirement: Idempotent durable dispatch
Every remotely executed AgentRun MUST have a durable dispatch identity, selected worker, session workspace identity and idempotency contract before execution starts.

#### Scenario: Dispatch is retried after a network timeout
- **WHEN** Atenea repeats an identical dispatch request with the same dispatch identity
- **THEN** the worker returns the existing execution and lifecycle revision rather than starting a duplicate

#### Scenario: Dispatch identity is reused with conflicting input
- **WHEN** a request reuses a dispatch identity with a different session, workspace or workload fingerprint
- **THEN** the worker rejects it fail-closed and preserves the original execution unchanged

### Requirement: Session execution affinity
An open WorkSession SHALL remain pinned to one execution target and workspace unless an explicit, safe migration procedure is completed while no run is active.

#### Scenario: Later turn continues the session
- **WHEN** an operator submits another turn to an open WorkSession
- **THEN** Atenea routes it to the same execution target, selected worker and workspace that own the session's Codex thread

#### Scenario: Feature switch changes after session creation
- **WHEN** remote routing is enabled or disabled while a WorkSession is open
- **THEN** that WorkSession retains its persisted target and only later newly opened sessions are evaluated

### Requirement: Bounded global scheduling
The worker scheduler SHALL enforce a configurable maximum of four normal concurrent execution slots and a separate configurable limit of two heavy operations.

#### Scenario: All normal slots are occupied
- **WHEN** a fifth eligible run is submitted while four normal slots are occupied
- **THEN** the run remains queued with visible admission state and does not start another unbounded runtime

#### Scenario: Heavy-operation limit is reached
- **WHEN** two admitted executions hold heavy-operation permits
- **THEN** an additional heavy workload waits without blocking lightweight progress reporting

#### Scenario: Worker service restarts
- **WHEN** the worker scheduler restarts with queued and non-terminal executions in durable state
- **THEN** it rebuilds permit accounting without starting a duplicate or exceeding either limit

### Requirement: Per-session sequential execution
Atenea MUST preserve the invariant that no more than one non-terminal AgentRun exists for a WorkSession at a time.

#### Scenario: Second turn arrives during a run
- **WHEN** a WorkSession already owns a queued, starting, running, cancelling or reconciling AgentRun
- **THEN** Atenea rejects the second executable turn with an operator-visible conflict and never runs both concurrently

### Requirement: Observable execution lifecycle
The control plane SHALL expose queued, starting, running, cancelling, reconciling and terminal execution state with monotonic revision, timestamps, worker identity and actionable failure information.

#### Scenario: Worker reports progress
- **WHEN** a worker acknowledges or advances an execution
- **THEN** Atenea persists and publishes only a newer lifecycle revision without exposing credentials or raw secret-bearing payloads

#### Scenario: Duplicate terminal delivery arrives
- **WHEN** the worker repeats the same terminal execution revision
- **THEN** Atenea retains one terminal outcome and one visible result without duplicating a response turn

### Requirement: Cancellation ownership
An authenticated operator SHALL be able to request cancellation, and the worker SHALL acknowledge graceful termination or report forced cleanup after a bounded timeout.

#### Scenario: Running job accepts cancellation
- **WHEN** the operator cancels a running AgentRun using its persisted worker, dispatch and execution identities
- **THEN** only its processes are stopped safely, terminal state is persisted idempotently and unrelated sessions continue

#### Scenario: Cancellation is repeated
- **WHEN** the same cancellation is delivered again after the execution is terminal
- **THEN** the worker returns the existing terminal state and does not modify another execution

### Requirement: Restart and partition reconciliation
Atenea MUST reconcile non-terminal remote runs with worker state after a backend restart or connectivity interruption before declaring them failed.

#### Scenario: Backend restarts while worker keeps running
- **WHEN** Atenea restarts and the selected worker still holds the matching AgentRun
- **THEN** Atenea restores observation of that run without starting a duplicate or marking it failed solely because of the restart

#### Scenario: Worker cannot be reached
- **WHEN** the selected worker is unreachable during the reconciliation window
- **THEN** the run is shown as reconciling or unreachable until the bounded policy determines a recoverable or terminal outcome

#### Scenario: Local run exists during backend startup
- **WHEN** a non-terminal AgentRun is pinned to the local executor
- **THEN** Atenea applies the pre-existing local reconciliation policy and does not reinterpret it as remote

#### Scenario: Different worker reports the dispatch
- **WHEN** reconciliation receives a claim from a worker other than the persisted selected worker
- **THEN** Atenea rejects the claim, records an ownership conflict and does not reassign the run
