## ADDED Requirements

### Requirement: Client-independent execution
An accepted AgentRun SHALL continue according to server policy when the initiating web, laptop or mobile client disconnects.

#### Scenario: Laptop is closed after prompt submission
- **WHEN** Atenea has durably accepted and dispatched the run before the laptop disconnects
- **THEN** the worker continues execution and Atenea persists subsequent progress and terminal state

### Requirement: Cross-surface session resume
Authenticated operator surfaces SHALL resolve the same WorkSession conversation, run state, required action and artifacts without creating a duplicate execution.

#### Scenario: Operator moves from laptop to Android
- **WHEN** the operator opens the active session on Android
- **THEN** the current conversation, run status, progress and available artifacts match the control-plane state

### Requirement: Durable visible conversation and execution trace
Operator turns, Codex responses, worker lifecycle events, external thread identity and terminal outcomes MUST be persisted independently of browser and backend process memory.

#### Scenario: Backend restarts after a Codex response
- **WHEN** the response or terminal result was accepted before restart
- **THEN** it remains visible exactly once after recovery

### Requirement: Actionable interruption state
Authentication expiry, approval requirements, capacity waits, worker outages and runtime failures SHALL expose a concise state, reason and next operator action.

#### Scenario: Codex requires operator intervention
- **WHEN** a run cannot proceed without a supported operator action
- **THEN** Atenea marks it waiting or blocked, preserves execution context and presents the required action on web and mobile

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
