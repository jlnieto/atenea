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

Artifacts retained by policy and immutable turn attachment bindings SHALL
remain accessible by exact WorkSession identity after client disconnect,
backend/attachment/worker restart, retry and preview shutdown. Restart
reconciliation SHALL use persisted metadata, ordered turn bindings, AgentRun
manifest and opaque storage identity without inventing ownership, re-uploading
bytes, dropping an image or starting a duplicate Codex execution.

#### Scenario: Operator returns after preview teardown

- **WHEN** the operator reconnects after the preview and attachment service have
  stopped and the attachment service is restarted
- **THEN** retained screenshots, reports and historical turn bindings remain
  byte-identical and available from the originating WorkSession even though
  ephemeral preview resources are gone

#### Scenario: Client disconnects after image-bearing turn acceptance

- **WHEN** Atenea committed the turn, bindings and AgentRun before the web
  client disconnected
- **THEN** the same worker executes or reconciles the exact attachment manifest
  and publishes no duplicate turn, binding, run or result

#### Scenario: Failed image-bearing run is retried

- **WHEN** safe recovery creates one linked retry for a failed remote run
- **THEN** it inherits the original turn, ordered image manifest and effective
  Codex profile without re-uploading or rebinding content

#### Scenario: Restart occurs during image materialization

- **WHEN** backend or worker state is reconstructed while an image-bearing run
  is non-terminal
- **THEN** reconciliation follows the existing dispatch identity and never
  creates a replacement execution merely because temporary image state is
  being verified

### Requirement: Thread continuity with bounded recovery
The platform SHALL reuse a valid session Codex thread and SHALL create a fresh thread only through an explicit stale-thread recovery that preserves the visible WorkSession history.

#### Scenario: External thread no longer exists
- **WHEN** Codex reports the persisted thread missing
- **THEN** Atenea records the recovery, starts a replacement thread once and continues without deleting prior SessionTurns

### Requirement: Source-base reconciliation preserves continuity

Changing Atenea's canonical base declaration SHALL be an explicit,
fingerprinted reconciliation of append-only Git history and persisted project
configuration. It SHALL NOT rewrite retained WorkSession identity, invent
ownership, reassign slots, start runtimes or redispatch AgentRuns.

The compiled backend and worker source identities, request schemas, runtime
manifest and persisted declarations SHALL move as one reviewed authority set.
No mixed feature/main authority may be installed or enabled.

#### Scenario: Main reconciliation is repeated

- **WHEN** the canonical checkout, mirror, project default and worker registry
  already resolve to the accepted main commit
- **THEN** repetition changes nothing and creates no session, run, lease,
  routing, listener, container or network

#### Scenario: Reconciliation must be rolled back operationally

- **WHEN** a post-merge operational declaration fails its acceptance check
- **THEN** only that exact declaration may return to its recorded prior value;
  merged GitHub history and retained session history are not rewritten

#### Scenario: A closed session still holds active worker capacity

- **WHEN** exact persisted ownership proves the closed session is the sole
  registration and admission holder and it owns no runtime resources
- **THEN** the mediated reconciliation releases only its active registration
  and admission and does not delete its retained allocation, worktree or audit
  history

### Requirement: Durable remote WorkSession close

Atenea SHALL persist one immutable remote-close operation before contacting the
selected worker and SHALL mark a remote WorkSession `CLOSED` only after both
delivery/Git reconciliation and an exact worker `RELEASED` receipt succeed.
The operation SHALL survive backend restart and SHALL reuse the same session,
worker, workspace and operation identities on every reconciliation.

#### Scenario: Backend stops after worker release

- **WHEN** the worker persisted a release receipt but Atenea stopped before
  committing `CLOSED`
- **THEN** startup reconciliation requests the same operation, accepts the same
  receipt once and closes the session without another release mutation

#### Scenario: Worker is unavailable during close

- **WHEN** delivery and Git are reconciled but the selected worker cannot be
  reached within the request bound
- **THEN** the WorkSession remains visibly `CLOSING/RECONCILING` and is not
  reported closed or moved to another worker

#### Scenario: Remote ownership is inconsistent during close

- **WHEN** registration, admission, allocation, runtime or workspace ownership
  is partial, foreign or ambiguous
- **THEN** the WorkSession remains `CLOSING/BLOCKED`, no further resource is
  modified and the operator receives the exact safe next action

#### Scenario: Backend restarts with a blocked legacy operation

- **WHEN** startup observes a persisted legacy operation in `BLOCKED` without
  a receipt
- **THEN** it leaves the operation unchanged and performs no worker release
  until a fresh read-only plan is explicitly confirmed
- **AND** after that confirmation restart recovery reuses only the original
  operation, session, ownership fingerprint and worker identity

#### Scenario: Historical remote session predates the close contract

- **WHEN** V63 observes a remote WorkSession already marked `CLOSED`
- **THEN** it records `UNVERIFIED_LEGACY` without releasing, adopting or
  declaring clean any worker state

#### Scenario: Normal close completes

- **WHEN** Git/delivery and the exact worker release receipt both pass
- **THEN** Atenea atomically records `CLOSED/RELEASED` and retains conversation,
  Git, attachment and audit history

### Requirement: Current-code successor creation is monotonic and recoverable

Atenea SHALL persist one exact fresh-start operation before closing the source
WorkSession when a retained failed run cannot be retried because canonical
source advanced. It SHALL persist the source's exact `RELEASED` receipt before
creating or returning one successor and SHALL resume the same operation after
response or backend loss.

#### Scenario: Backend stops after source release

- **WHEN** the source WorkSession is durably `CLOSED/RELEASED` but successor
  creation did not complete before restart
- **THEN** repeating the same operation SHALL create or return exactly one
  successor and SHALL NOT reconstruct released ownership
