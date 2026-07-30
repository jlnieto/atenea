## ADDED Requirements

### Requirement: Immutable effective Codex execution profile

Every AgentRun SHALL persist the canonical model, reasoning effort, catalog
revision, Codex version and configuration source that govern its execution.
Resolution SHALL occur before durable dispatch using next-turn, WorkSession,
project, platform and accepted worker-default precedence. A settings change
MUST affect only future AgentRuns and MUST NOT rewrite execution history.

#### Scenario: Operator changes the WorkSession effort

- **WHEN** an authenticated operator changes a WorkSession from medium to high while no turn is being submitted
- **THEN** the next AgentRun persists high effort while every earlier AgentRun retains its original effective profile

#### Scenario: Next-turn override is supplied

- **WHEN** a valid next-turn model or effort override is submitted with a prompt
- **THEN** it is consumed by that AgentRun only and the WorkSession default remains unchanged

#### Scenario: Profile cannot be resolved exactly

- **WHEN** the requested model, effort, catalog revision or selected worker capability is missing, unsupported, stale or ambiguous
- **THEN** dispatch is blocked with the unsupported field and no silent model substitution occurs

### Requirement: Closed model and effort authority

Atenea SHALL expose only model identifiers and reasoning efforts advertised by
the selected compatible worker and permitted by platform/project policy. The
worker runner SHALL derive reviewed Codex flags from those fields and SHALL NOT
accept an arbitrary provider, endpoint, configuration fragment, argument array,
path, environment value or credential.

#### Scenario: Supported profile is selected

- **WHEN** an operator selects a catalog-advertised model and supported low, medium, high or xhigh effort
- **THEN** Atenea displays the effective selection and the worker invokes Codex with exactly that validated profile

#### Scenario: Caller injects a Codex option

- **WHEN** a request includes an unrecognized effort, arbitrary model, provider, endpoint, flag or configuration value
- **THEN** Atenea and the worker reject it before starting Codex or changing persisted settings

### Requirement: Safe durable progress timeline

Atenea SHALL persist and publish monotonically sequenced, sanitized progress
events from a closed operational taxonomy. Each AgentRun SHALL retain at most
200 coalesced events and SHALL always retain its current state, latest event,
elapsed time and required next action. Raw chain-of-thought, model reasoning,
command arguments, command output, environment values and secret-bearing
payloads MUST NOT be stored or published.

#### Scenario: Codex performs a multi-step task

- **WHEN** the worker receives accepted structured lifecycle and tool events
- **THEN** web and Android show concise stages such as preparing, inspecting, checking, waiting and finalizing in monotonic order

#### Scenario: Client reconnects after missing events

- **WHEN** a client resumes with its last persisted event sequence
- **THEN** Atenea replays the durable gap once and then continues live publication without duplicating events

#### Scenario: Event contains unsafe detail

- **WHEN** an event contains reasoning, raw command/output, credential-shaped content or an unsupported event type
- **THEN** the unsafe content is discarded and only an allowed generic state may be retained

### Requirement: Idempotent self-service recovery

An authenticated operator SHALL be able to cancel an exact non-terminal
AgentRun, request reconciliation of an exact unreachable/reconciling dispatch,
retry a safely terminal failed run and obtain a sanitized diagnostic summary.
Recovery commands SHALL be persisted, idempotent and scoped to the operator's
WorkSession.

#### Scenario: Unreachable run is reconciled

- **WHEN** an operator requests reconciliation for a persisted non-terminal dispatch
- **THEN** Atenea observes that same worker execution and does not create a replacement AgentRun

#### Scenario: Failed run is retried safely

- **WHEN** the worker proves the previous dispatch terminal or absent and the WorkSession has no non-terminal run
- **THEN** Atenea creates one new AgentRun linked to the failed run and keeps the original attempt unchanged

#### Scenario: Prior execution may still be live

- **WHEN** terminal or absent ownership cannot be proven
- **THEN** retry is rejected and Atenea presents reconciliation or privileged assistance as the next action

### Requirement: Privileged operational boundary

Routine operators, privileged operators and platform administrators SHALL have
distinct action allowlists. Privileged actions SHALL use fixed mediated
operations and exact persisted ownership. No Atenea endpoint SHALL accept an
arbitrary host, service, command, slot or resource target.

#### Scenario: Routine operator requests host restart

- **WHEN** a routine WorkSession operator attempts to restart AX42, a worker service or Codex
- **THEN** the request is denied without changing platform state and the required administrator role is shown

#### Scenario: Privileged operator restarts an exact execution service

- **WHEN** policy permits a mediated restart and the complete worker/session/service identity matches
- **THEN** only the reviewed service is restarted and its persisted runs are reconciled before new dispatch

### Requirement: Managed Codex version lifecycle

Atenea SHALL expose the selected worker's installed, current and previous Codex
versions plus catalog/compatibility state. A real update SHALL require separate
platform-administrator authorization, zero active executions, verified release
input, version-matched schema checks, focused contracts, health and one canary.
The previous verified version SHALL remain available for exact rollback.

#### Scenario: New Codex version is available

- **WHEN** an administrator requests a read-only update plan
- **THEN** Atenea reports current, candidate, compatibility gates and expected service impact without installing anything

#### Scenario: Active execution exists

- **WHEN** update activation is requested while any worker execution is non-terminal
- **THEN** activation is blocked and no binary, link or service changes

#### Scenario: Canary or compatibility fails

- **WHEN** schema, contract, health or canary acceptance fails after staging
- **THEN** the current version remains active or the previous verified link is restored without restarting project runtimes or unrelated slots

### Requirement: State-first cross-surface controls

Web and Android SHALL show current run state, effective model, reasoning effort,
Codex version, elapsed time, latest progress and primary next action without
scrolling. Both surfaces SHALL consume the same authorization and read model,
and errors SHALL state what happened and what the operator can do next.

#### Scenario: Run is active

- **WHEN** an AgentRun is queued, preparing, running, waiting or reconciling
- **THEN** its state and latest safe progress are immediately visible and cancel or reconciliation is the single applicable primary action

#### Scenario: Run fails safely

- **WHEN** an AgentRun reaches a retryable or administrator-required failure
- **THEN** the conversation distinguishes retry, reconcile and request-admin actions rather than showing a generic error
