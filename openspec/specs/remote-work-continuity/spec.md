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
Atenea SHALL notify configured operator devices when a run completes, fails or requires action, subject to notification preference and deduplication policy.

#### Scenario: Run finishes while all clients are closed
- **WHEN** the worker reports a terminal result
- **THEN** Atenea persists it and sends no more than one applicable completion notification per configured device/event policy

### Requirement: Durable artifact continuity
Artifacts retained by policy SHALL remain accessible after client disconnect, worker service restart and preview shutdown.

#### Scenario: Preview has been cleaned up
- **WHEN** the operator later opens the completed session
- **THEN** retained screenshots and reports remain available even though ephemeral runtime resources are gone

### Requirement: Thread continuity with bounded recovery
The platform SHALL reuse a valid session Codex thread and SHALL create a fresh thread only through an explicit stale-thread recovery that preserves the visible WorkSession history.

#### Scenario: External thread no longer exists
- **WHEN** Codex reports the persisted thread missing
- **THEN** Atenea records the recovery, starts a replacement thread once and continues without deleting prior SessionTurns
