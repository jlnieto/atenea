## MODIFIED Requirements

### Requirement: Private service exposure

Worker API, Codex App Server, runtime control endpoints, databases and previews MUST
bind only to loopback, isolated runtime networks or approved private
interfaces. Preview ingress SHALL use a bounded dedicated port range admitted
only on the private interface, SHALL forward only to allocation-derived
loopback targets and SHALL expose no arbitrary proxy operation.

#### Scenario: Public interface is scanned

- **WHEN** an unauthenticated Internet client scans the worker while a synthetic
  preview is ready
- **THEN** only explicitly approved bootstrap or break-glass services are
  reachable and the preview content is not

#### Scenario: Arbitrary upstream is requested

- **WHEN** a preview request supplies a host, port, path or route identity not
  derived from the persisted WorkSession allocation
- **THEN** the worker rejects it before creating a listener and preserves every
  existing route

### Requirement: Safe garbage collection

The worker SHALL identify and clean orphaned containers, worktrees, ports,
preview projections and temporary artifacts only after proving they are not
owned by an active or recoverable session. Preview deletion SHALL require the
complete immutable ownership tuple and SHALL fail closed on absent, partial,
foreign or ambiguous labels.

#### Scenario: Worker restarts with orphaned resources

- **WHEN** reconciliation finds a complete exact-owned synthetic preview whose
  persisted lease has expired
- **THEN** its live projection is removed, the action is audited and unrelated
  runtime, Git and attachment state remains

#### Scenario: Foreign preview-like listener exists

- **WHEN** cleanup observes an unlabelled, partially labelled, foreign or
  ambiguously owned listener or process
- **THEN** it rejects the candidate unchanged and reports the ownership reason

## ADDED Requirements

### Requirement: Authenticated preview control protocol

The AX42 preview coordinator SHALL expose a versioned authenticated control
protocol only to Atenea over the approved private network. Requests SHALL carry
the immutable preview, WorkSession, project, worker, allocation and expected
revision identities; malformed, stale or conflicting requests SHALL mutate
nothing.

#### Scenario: Duplicate activation is retried

- **WHEN** Atenea repeats an identical activation after losing the response
- **THEN** the worker returns the same preview route and revision without
  starting another listener

#### Scenario: Stale revision is submitted

- **WHEN** a client submits an ownership-valid request with an older lifecycle
  revision
- **THEN** the worker returns conflict and leaves the current projection
  unchanged
