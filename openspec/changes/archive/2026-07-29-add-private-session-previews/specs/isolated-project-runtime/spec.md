## MODIFIED Requirements

### Requirement: Deterministic lifecycle and cleanup

Runtime start, health, preview publication, stop and cleanup operations SHALL
be idempotent and SHALL preserve declared artifacts and Git evidence before
removing ephemeral resources. Preview operations MUST derive their upstream
loopback port, runtime namespace and allocation fingerprint from the immutable
WorkSession record and MUST NOT accept caller-supplied alternatives.

#### Scenario: Worker restarts during preview

- **WHEN** the worker or preview coordinator restarts during an unexpired route
- **THEN** it reconciles only the persisted exact session runtime, reports
  `RECONCILING`, and restores the same route or records a blocked state

#### Scenario: Session is closed

- **WHEN** a reconciled WorkSession reaches its cleanup policy
- **THEN** exact-owned preview listeners and ephemeral runtime resources are
  removed without deleting retained attachments, branches or audit records

#### Scenario: Cleanup identity is incomplete

- **WHEN** a cleanup candidate lacks the full preview, session, worker,
  allocation and synthetic-fixture identity
- **THEN** cleanup rejects it unchanged and reports the ambiguous ownership
