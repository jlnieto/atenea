## MODIFIED Requirements

### Requirement: Least-privilege execution

Routine Codex and project workloads SHALL run without host root authority or
credentials and capabilities unrelated to their session. Managed runtimes and
real Codex AgentRuns MUST execute in a sandbox that
prevents access to the host container daemon, host root filesystem, unrelated
workspaces, production credentials and production network authority. The real
workload runner SHALL resolve all filesystem/runtime identities from persisted
exact WorkSession ownership and SHALL invoke only reviewed manifest operations.

#### Scenario: Runtime command is compromised

- **WHEN** a session process attempts a privileged host operation
- **THEN** OS and runtime controls prevent it from gaining host root or
  controlling another session

#### Scenario: Real Atenea prompt runs

- **WHEN** an exact onboarded Atenea WorkSession is admitted
- **THEN** its Codex process can change only the intended session worktree and
  declared artifacts while daemon sockets, other workspaces and production
  authority remain unavailable

#### Scenario: Workload requests extra authority

- **WHEN** a request or prompt attempts to supply or reach an arbitrary command,
  path, remote, endpoint, environment, daemon socket or foreign workspace
- **THEN** the worker rejects or sandboxes the operation without changing the
  target or any unrelated resource

### Requirement: Safe garbage collection

The worker SHALL identify and clean orphaned containers, worktrees, ports,
database resources, preview projections, real AgentRun processes and temporary
artifacts only after proving they are not owned by an active or recoverable
session. Preview, database and real-project cleanup SHALL require the complete
immutable dispatch, project, workspace, worker, allocation and resource
ownership tuple and SHALL fail closed on absent, partial, foreign,
production-like or ambiguous ownership.

#### Scenario: Exact synthetic database reaches cleanup

- **WHEN** cleanup validates the complete terminal synthetic database record,
  rootless slot, container, network, volume and snapshot identities
- **THEN** it removes only those exact ephemeral resources while retaining Git
  and sanitized evidence

#### Scenario: Foreign database-like resource exists

- **WHEN** cleanup observes an unlabelled, partially labelled, foreign,
  production-like or ambiguously owned database resource
- **THEN** it rejects the candidate unchanged and reports the ownership reason

#### Scenario: Exact closed Atenea session reaches cleanup

- **WHEN** the accepted Atenea WorkSession is terminal, delivery-reconciled and
  every exact runtime/preview/database/process identity validates
- **THEN** cleanup removes only its declared ephemeral resources, releases its
  admission and retains Git, delivery and sanitized evidence

#### Scenario: Foreign project-like resource exists

- **WHEN** cleanup observes an unlabelled, partial, foreign or ambiguous
  project/AgentRun resource
- **THEN** it rejects that candidate unchanged and reports the ownership reason
