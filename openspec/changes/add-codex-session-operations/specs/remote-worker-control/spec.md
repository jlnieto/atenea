## MODIFIED Requirements

### Requirement: Idempotent durable dispatch

Every remotely executed AgentRun MUST have a durable dispatch identity,
selected worker, session workspace identity, project/workload fingerprint,
canonical model, reasoning effort, accepted catalog revision and idempotency
contract before execution starts. Real-project dispatch MUST bind operator
input and its immutable effective execution profile to the persisted
project/workspace and MUST NOT accept caller commands, paths, remotes,
endpoints, providers, configuration fragments or environment.

#### Scenario: Dispatch is retried after a network timeout

- **WHEN** Atenea repeats an identical dispatch request with the same dispatch identity and workload/profile fingerprint
- **THEN** the worker returns the existing execution and lifecycle revision rather than starting another Codex turn

#### Scenario: Dispatch identity is reused with conflicting input

- **WHEN** a request reuses a dispatch identity with a different session, workspace, project, prompt, model, effort, catalog revision or workload fingerprint
- **THEN** the worker rejects it fail-closed and preserves the original execution unchanged

#### Scenario: Profile syntax is valid but authority is not

- **WHEN** a request names a model absent from the accepted catalog, an effort absent from that model, a stale catalog/Codex version or a session/workspace pair outside the exact registry
- **THEN** semantic validation rejects it before execution state or a process is created

#### Scenario: Caller adds operational authority

- **WHEN** a protocol or API request supplies a command, provider, endpoint, path, service, host, slot, environment, credential, release URL or workspace authority
- **THEN** closed schema validation rejects the request and all persisted worker and control-plane ownership remains unchanged

### Requirement: Observable execution lifecycle

The control plane SHALL expose queued, starting, running, cancelling,
reconciling and terminal execution state with monotonic revision, timestamps,
worker identity, immutable effective Codex profile and actionable failure
information. It SHALL additionally persist and publish only newer normalized
progress sequences from the accepted safe taxonomy. AgentRun process outcome
MUST remain distinct from the owning WorkSession's validation and integration
readiness.

#### Scenario: Worker reports progress

- **WHEN** a worker acknowledges or advances an execution with a newer safe lifecycle revision or progress sequence
- **THEN** Atenea persists and publishes it without exposing credentials, reasoning, raw commands, raw output or secret-bearing payloads

#### Scenario: Duplicate progress delivery arrives

- **WHEN** the worker repeats an existing progress sequence
- **THEN** Atenea retains one event and does not republish a duplicate timeline item

#### Scenario: Duplicate terminal delivery arrives

- **WHEN** the worker repeats the same terminal execution revision
- **THEN** Atenea retains one terminal outcome, one visible result and one applicable notification event without duplicating a response turn

#### Scenario: Successful process has missing acceptance checks

- **WHEN** the worker reports a successful Codex process but required build, test, visual or source-freshness evidence is absent
- **THEN** Atenea records process success while keeping the WorkSession blocked from integration with the exact missing check visible
