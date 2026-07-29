## MODIFIED Requirements

### Requirement: Private service exposure

Worker API, Codex App Server, runtime control endpoints, databases and previews MUST
bind only to loopback, isolated runtime networks or approved private
interfaces; this binding requirement MUST be enforced before activation.
Development databases SHALL have no host firewall admission and
their loopback endpoint SHALL derive only from a persisted WorkSession
allocation. Preview ingress SHALL use a bounded dedicated port range admitted
only on the private interface, SHALL forward only to allocation-derived
loopback targets and SHALL expose no arbitrary proxy operation.

#### Scenario: Public interface is scanned

- **WHEN** an unauthenticated Internet client scans the worker while a
  synthetic database or preview is ready
- **THEN** only explicitly approved bootstrap or break-glass services are
  reachable and neither database nor preview content is exposed

#### Scenario: Arbitrary database endpoint is requested

- **WHEN** a database request supplies a host, port, socket, database name,
  volume or network not derived from persisted WorkSession ownership
- **THEN** the worker rejects it before invoking a client or container command
  and preserves every existing database

### Requirement: Safe garbage collection

The worker SHALL identify and clean orphaned containers, worktrees, ports,
database resources, preview projections and temporary artifacts only after
proving they are not owned by an active or recoverable session. Preview and
database deletion SHALL require the complete immutable ownership tuple and
SHALL fail closed on absent, partial, foreign, production-like or ambiguous
labels.

#### Scenario: Exact synthetic database reaches cleanup

- **WHEN** cleanup validates the complete terminal synthetic database record,
  rootless slot, container, network, volume and snapshot identities
- **THEN** it removes only those exact ephemeral resources while retaining Git
  and sanitized evidence

#### Scenario: Foreign database-like resource exists

- **WHEN** cleanup observes an unlabelled, partially labelled, foreign,
  production-like or ambiguously owned database resource
- **THEN** it rejects the candidate unchanged and reports the ownership reason
