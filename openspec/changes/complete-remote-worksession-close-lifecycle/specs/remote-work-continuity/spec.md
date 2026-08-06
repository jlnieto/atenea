## ADDED Requirements

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
