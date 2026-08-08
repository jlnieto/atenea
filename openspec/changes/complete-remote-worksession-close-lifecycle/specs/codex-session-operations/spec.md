## ADDED Requirements

### Requirement: Action-specific pre-admission recovery

A pre-dispatch remote AgentRun failure SHALL persist a stable safe failure code
and one applicable next action independently from its generic terminal progress
category. Retry SHALL be offered only when the prior execution is terminal or
absent and every deterministic admission blocker has been cleared.

#### Scenario: Closed session owns required capacity

- **WHEN** the worker identifies a blocker and Atenea proves it is an exact
  same-project, same-worker `CLOSED` WorkSession with zero non-terminal runs
- **THEN** the failed run shows `RECONCILE_REMOTE_CLOSE` and generic retry is
  unavailable until that close reconciliation succeeds

#### Scenario: Open session owns required capacity

- **WHEN** the exact blocking owner remains open or closing
- **THEN** the new run remains visibly queued or waiting with cancel available
  and no ownership is released automatically

#### Scenario: Blocking owner is foreign or ambiguous

- **WHEN** the reported owner cannot be matched exactly to the control-plane
  worker, project and WorkSession
- **THEN** the run requires platform-administrator review and neither retry nor
  cleanup is invoked

#### Scenario: Deterministic blocker was reconciled

- **WHEN** exact closed-session release succeeds and the prior dispatch is
  proven absent or terminal
- **THEN** Atenea may offer one explicit safe retry linked to the original
  failed AgentRun while preserving its turn, profile and attachments

### Requirement: Confirmed legacy remote-close reconciliation

A `PLATFORM_ADMINISTRATOR` SHALL be able to request only the fixed
`RECONCILE_REMOTE_CLOSE` operation for one selected historical remote
WorkSession. The operation SHALL require an explicit single-use finite
confirmation bound to the exact session, worker, project and read-only
ownership fingerprint. It SHALL never retry a prompt or accept an arbitrary
resource target.

#### Scenario: Exact legacy owner is confirmed

- **WHEN** the selected session is `CLOSED/UNVERIFIED_LEGACY`, every AgentRun is
  terminal and the confirmed worker ownership fingerprint still matches
- **THEN** Atenea invokes the exact workspace-release operation and records its
  receipt without changing historical session or delivery state

#### Scenario: Confirmation is stale or reused

- **WHEN** ownership changes, the confirmation expires or the same
  authorization is submitted again with different input
- **THEN** reconciliation fails before worker mutation and requires a fresh
  read-only plan

#### Scenario: Exact release preflight blocks the first confirmation

- **WHEN** the immutable operation is durably `BLOCKED` with exact
  `WORKSPACE_RELEASE_PREFLIGHT_REJECTED/OWNERSHIP`, administrative next action,
  `retryable=false`, no receipt and no worker mutation
- **THEN** only a fresh read-only plan and a new explicit single-use platform
  administrator confirmation MAY move that same operation to `RECONCILING`
- **AND** Atenea SHALL NOT create a replacement operation, automatically retry
  release, reconstruct ownership or accept a different fingerprint

#### Scenario: Reauthorized blocked operation completes

- **WHEN** the fresh confirmation still matches the exact owner and the worker
  returns the release receipt for the original operation identity
- **THEN** Atenea persists `RELEASED` and the receipt monotonically, while
  repeated confirmation returns the same result without another mutation

#### Scenario: Fresh blocked-recovery plan requires complete release preflight

- **WHEN** the immutable operation is already `BLOCKED` and the administrator
  requests another finite plan
- **THEN** Atenea first sends the complete server-derived release request for
  the original operation to the worker's non-mutating release-preflight
  endpoint and persists the plan only after the exact sanitized fingerprints
  match
- **AND** any deterministic, transport or protocol failure prevents plan
  creation with its own category and performs no release or automatic retry

#### Scenario: Routine operator requests legacy release

- **WHEN** an operator without platform-administrator authority invokes the
  action
- **THEN** Atenea rejects it and displays the required role without changing
  the session or worker

### Requirement: Remote-close state-first operator surface

Web and Android SHALL present the same persisted remote-close state, safe
reason and single primary next action in the first viewport. A deterministic
ownership or capacity failure MUST NOT appear as worker unavailability, and
raw infrastructure identities or error payloads MUST NOT be exposed.

#### Scenario: Remote close is reconciling

- **WHEN** a release request may have completed but its receipt is not yet
  reconciled
- **THEN** both surfaces show that closing is in progress and offer only wait
  or same-operation reconciliation

#### Scenario: Closed predecessor blocks a new run

- **WHEN** an exact legacy closed session retains required capacity
- **THEN** an authorized surface makes `Reconciliar cierre` the primary action
  and explains that retry will become available only afterwards

#### Scenario: Preserved run predates typed capacity recovery

- **WHEN** a remote terminal pre-dispatch AgentRun has no V63 failure or
  next-action fields and its immediate older same-project WorkSession is an
  exact canonical `CLOSED/UNVERIFIED_LEGACY` owner
- **THEN** Atenea SHALL preserve the AgentRun unchanged, obtain a read-only
  diagnosis for only that predecessor and project `Reconciliar cierre` only
  after exact worker ownership succeeds
- **AND** an unavailable, partial, foreign or ambiguous diagnosis SHALL disable
  retry and SHALL NOT discover, adopt or release another owner

#### Scenario: Capacity was released

- **WHEN** the exact release receipt is persisted and the failed dispatch is
  proven absent or terminal
- **THEN** both surfaces show `Capacidad liberada` and offer an explicit retry
  without executing it automatically

#### Scenario: Canonical source advanced before retry

- **WHEN** the failed pre-dispatch AgentRun is otherwise retry-eligible but its
  pinned commit is an exact ancestor of current canonical `main`
- **THEN** Atenea SHALL show `Código actualizado`, SHALL NOT offer or execute
  retry, and SHALL offer one `START_FRESH_SESSION` primary action only to a
  `PLATFORM_ADMINISTRATOR`
- **AND** the retained AgentRun, turn, profile and attachment binding SHALL
  remain unchanged

#### Scenario: Fresh start is repeated after response or backend loss

- **WHEN** the same operator repeats `START_FRESH_SESSION` with the same
  idempotency key
- **THEN** Atenea SHALL resume or return the same durable operation and the same
  successor WorkSession without closing or opening any additional session

#### Scenario: Exact blocked confirmation can be validated again

- **WHEN** the backend proves the complete exact blocked-operation recovery
  predicate and the operator has platform-administrator authority
- **THEN** both surfaces show `Volver a validar cierre` as the single primary
  action and require a fresh finite plan
- **AND** a stale, consumed or newly blocked confirmation disables the action
  until explicit refresh obtains another plan

#### Scenario: Confirmation target differs from the open session

- **WHEN** an operator opens a current WorkSession while a finite legacy plan
  targets a different exact closed WorkSession
- **THEN** web and Android identify both the open WorkSession and the exact
  target in the confirmation copy, identify the target again in the primary
  confirmation action and reject a plan whose target differs from the server
  state
