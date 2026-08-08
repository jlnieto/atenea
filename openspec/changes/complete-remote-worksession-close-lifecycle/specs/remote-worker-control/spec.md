## ADDED Requirements

### Requirement: Typed bounded worker failures

Every authenticated worker rejection SHALL return a closed, size-bounded safe
error envelope with stable code, category, retryability and next action. Atenea
MUST distinguish transport unavailability from an authenticated deterministic
rejection and MUST NOT persist or expose raw response bodies, mediator stderr,
commands, paths, labels, credentials or foreign ownership details.

#### Scenario: Worker is unreachable

- **WHEN** connection, request timeout, interruption or a compatible transient
  server failure prevents an authenticated response
- **THEN** Atenea keeps the same dispatch in bounded reconciliation and does
  not create a replacement execution

#### Scenario: Workspace activation is deterministically rejected

- **WHEN** the worker returns a valid ownership, source, policy or schema
  rejection before dispatch
- **THEN** Atenea persists that stable safe code immediately and does not
  describe the worker as unavailable or repeat the impossible activation

#### Scenario: Capacity has an exact persisted owner

- **WHEN** activation reports capacity held by another canonical WorkSession
- **THEN** the authenticated control plane validates that owner against its own
  worker/project/session records before choosing wait, closed-session
  reconciliation or administrator review

#### Scenario: Historical owner is diagnosed without mutation

- **WHEN** Atenea supplies the exact canonical identity of one historical
  Atenea owner to the authenticated capacity-owner diagnosis endpoint
- **THEN** the worker reads only its fixed registry, workspace, allocation and
  admission roots, validates complete exact ownership and Git identity, changes
  no state and returns only sealed sanitized fingerprints
- **AND** the endpoint rejects all partial or foreign state without accepting or
  exposing caller-selected infrastructure resources

#### Scenario: Worker error body is malformed or unsafe

- **WHEN** a non-success response is oversized, unknown, invalid JSON or
  contains unsupported fields or values
- **THEN** Atenea records only a generic protocol failure and discards the raw
  body without inferring retryability or ownership

### Requirement: Read-only canonical-source readiness

The worker SHALL compare only the exact persisted canonical Atenea identity
with its fixed refreshed mirror. It SHALL accept no caller-selected operational
authority and SHALL not activate a workspace.

#### Scenario: Readiness detects newer canonical source

- **WHEN** the requested commit equals current canonical `main` or is its exact
  ancestor
- **THEN** the worker SHALL return only `READY_FOR_RETRY` for equality or
  `SOURCE_ADVANCED` for the ancestor relationship
- **AND** it SHALL NOT change workspace registration, admission, allocation,
  runtime, preview or retained ownership

#### Scenario: Source relationship is not exact

- **WHEN** the commit is missing, unrelated, foreign or ambiguous
- **THEN** the worker SHALL reject deterministically without entering the
  worker-unavailable window or invoking activation

### Requirement: Idempotent exact workspace release

The worker SHALL expose one authenticated closed-schema operation to release a
remote WorkSession. Its immutable request SHALL contain only operation,
session, workspace, project, repository, branch, commit, manifest and
workspace-branch identities derived from persisted control-plane state. It
MUST NOT accept a command, path, slot, port, service, endpoint, resource name,
label, credential or deletion target. A successful response SHALL be one
strict ownership-matching receipt with a monotonic revision and safe
removed/released/retained projections.

#### Scenario: Exact terminal WorkSession is released

- **WHEN** the immutable request matches one registered session and the worker
  proves it has no non-terminal execution
- **THEN** the reviewed finalizer releases only that session's exact ephemeral
  ownership and returns a persisted `RELEASED` receipt

#### Scenario: Pre-dispatch WorkSession never activated ownership

- **WHEN** Atenea requests normal close for a WorkSession with retained run
  history, no remote execution identity and exact worker proof that no
  execution, registration, admission, allocation or ephemeral resource exists
- **THEN** the worker SHALL persist and return the same standard `RELEASED`
  receipt without reconstructing ownership or mutating retained workspace state
- **AND** partial, foreign or ambiguous state SHALL reject deterministically
  without entering the worker-unavailable window

#### Scenario: Release response is lost

- **WHEN** Atenea repeats the identical operation and request fingerprint after
  the worker already completed it
- **THEN** the worker returns the same receipt and performs no additional
  mutation

#### Scenario: Operation identity is reused with different input

- **WHEN** a release operation UUID or idempotency key is presented with a
  different session, workspace or project fingerprint
- **THEN** the worker rejects it unchanged and preserves the original operation
  and every session resource

#### Scenario: Session still owns a non-terminal execution

- **WHEN** release observes a queued, starting, running, cancelling or
  reconciling worker execution for the session
- **THEN** it fails before resource mutation and requires that exact execution
  to reach a proven terminal outcome

#### Scenario: Complete release boundary is diagnosed without mutation

- **WHEN** Atenea submits the exact persisted release request to the
  authenticated release-preflight endpoint while no execution is non-terminal
- **THEN** the worker serializes on the release lifecycle lock, validates the
  fixed journal root and complete fixed-root release projection, creates no
  journal and changes no resource
- **AND** it returns only the exact operation/session/workspace identities and
  sealed request, ownership and allocation fingerprints with
  `valuesExposed=false`
