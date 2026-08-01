# remote-work-continuity Specification

## Purpose
TBD - created by archiving change establish-remote-codex-platform-program. Update Purpose after archive.
## Requirements
### Requirement: Client-independent execution
An accepted AgentRun SHALL continue according to server policy when the initiating web, laptop or mobile client disconnects.

#### Scenario: Laptop is closed after prompt submission
- **WHEN** Atenea has durably accepted and dispatched the run before the laptop disconnects
- **THEN** the selected worker continues execution and Atenea persists subsequent progress and terminal state

### Requirement: Cross-surface session resume
Authenticated operator surfaces SHALL resolve the same WorkSession conversation, run state, required action and artifacts without creating a duplicate execution.

#### Scenario: Operator returns after backend restart
- **WHEN** a remote AgentRun remained live while the Atenea backend restarted
- **THEN** the operator sees the same dispatch, execution, progress and eventual result without a duplicate run or response

### Requirement: Durable visible conversation and execution trace
Operator turns, Codex responses, worker lifecycle events, external thread identity and terminal outcomes MUST be persisted independently of browser, backend process and worker-service memory.

#### Scenario: Backend restarts before a remote execution completes
- **WHEN** the dispatch and target were durably accepted before restart
- **THEN** Atenea reconciles the same execution and makes its terminal response visible exactly once

#### Scenario: Synthetic session continues for another turn
- **WHEN** the first remote turn is terminal and an operator submits a later turn
- **THEN** the new AgentRun uses the same persisted target and workspace and retains the prior visible conversation

### Requirement: Actionable interruption state
Authentication expiry, approval requirements, capacity waits, worker outages and runtime failures SHALL expose a concise state, reason and next operator action.

#### Scenario: Worker is temporarily partitioned
- **WHEN** Atenea cannot reach the selected worker during a non-terminal run
- **THEN** the run exposes reconciling state, the bounded retry window and the fact that no replacement execution is being started

#### Scenario: Capacity is exhausted
- **WHEN** the worker has no applicable normal or heavy permit
- **THEN** the run remains visibly queued with its workload class and does not appear failed

### Requirement: Operator notification

Atenea SHALL persist a generic immutable notification event in the same
transaction that commits a run completion, failure or operator-action-required
state. It SHALL expand the event into preference-aware per-device deliveries
with unique event/device/channel ownership, finite retry, expiration and
invalid-device handling. Payloads SHALL use versioned safe templates and exact
application deep links and SHALL NOT contain full prompts, final answers,
credentials or worker-internal detail.

The initial category identifiers SHALL be `RUN_COMPLETED`, `RUN_FAILED` and
`ACTION_REQUIRED`. All three SHALL default enabled for an active Android device
with no explicit preference; an explicit per-device preference SHALL survive
re-registration and application upgrade. Intermediate progress SHALL remain
in-app/SSE only and SHALL NOT create push notifications.

#### Scenario: Run finishes while all clients are closed

- **WHEN** the worker reports a terminal result for the latest submitted run
- **THEN** Atenea persists it and sends no more than one applicable completion notification to each configured device according to preference

#### Scenario: Run fails or requires action

- **WHEN** a run reaches a terminal failure or a persisted operator-action-required state
- **THEN** configured devices receive one concise notification whose deep link opens the exact WorkSession and actionable state

#### Scenario: Notification event is delivered again

- **WHEN** backend restart, dispatcher retry or provider timeout repeats the same event/device/channel delivery
- **THEN** Atenea reuses its delivery identity and never creates a second applicable user notification

#### Scenario: Device token is permanently invalid

- **WHEN** the provider reports that one device token is expired or invalid
- **THEN** Atenea disables that device without exposing its token or blocking delivery to other devices

#### Scenario: Application is already foregrounded

- **WHEN** a terminal event arrives while the exact conversation is visible
- **THEN** Android updates the in-app state and suppresses duplicate local presentation where the platform permits

#### Scenario: Future notification category is added

- **WHEN** Atenea introduces a later preference-controlled event type
- **THEN** it reuses the same event, template, preference, delivery and deep-link contracts without changing AgentRun ownership

### Requirement: Durable artifact continuity
Artifacts retained by policy SHALL remain accessible by exact WorkSession
identity after client disconnect, attachment-service restart and preview
shutdown. Restart reconciliation SHALL use persisted metadata and opaque storage
identity without inventing ownership or re-uploading bytes.

#### Scenario: Operator returns after preview teardown

- **WHEN** the operator reconnects after the preview and attachment service have
  stopped and the attachment service is restarted
- **THEN** retained screenshots and reports remain byte-identical and available
  from the originating WorkSession even though ephemeral preview resources are
  gone

### Requirement: Thread continuity with bounded recovery
The platform SHALL reuse a valid session Codex thread and SHALL create a fresh thread only through an explicit stale-thread recovery that preserves the visible WorkSession history.

#### Scenario: External thread no longer exists
- **WHEN** Codex reports the persisted thread missing
- **THEN** Atenea records the recovery, starts a replacement thread once and continues without deleting prior SessionTurns
