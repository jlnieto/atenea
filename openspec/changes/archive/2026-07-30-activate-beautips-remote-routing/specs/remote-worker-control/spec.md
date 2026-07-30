## ADDED Requirements

### Requirement: Durable project workspace provisioning

Before dispatching a real-project AgentRun, Atenea MUST ensure the exact
persisted remote WorkSession owns a ready canonical mirror, session worktree,
admission allocation and project registration on its selected worker.
Provisioning MUST be authenticated, idempotent and derived only from persisted
project, branch, worker and workspace identities. It MUST NOT accept a caller
command, path, remote, slot, port, environment or credential.

#### Scenario: First turn provisions the selected workspace

- **WHEN** a durable queued AgentRun belongs to a newly selected exact
  real-project WorkSession
- **THEN** Atenea ensures the same remote workspace identity before dispatch
  and the worker returns its persisted ready ownership

#### Scenario: Provisioning is repeated after interruption

- **WHEN** Atenea retries or reconciles the same durable AgentRun after losing
  the provisioning response
- **THEN** the worker returns the same workspace and allocation without
  creating another worktree, allocation, registration or admission lease

#### Scenario: Provisioning ownership is incomplete or conflicting

- **WHEN** any persisted or discovered project, Git, workspace, allocation,
  slot, label or registration identity differs
- **THEN** provisioning and dispatch fail closed without modifying or cleaning
  the conflicting resource

#### Scenario: Existing session predates activation

- **WHEN** routing gates change while a local or differently pinned
  WorkSession is already open
- **THEN** no workspace is provisioned and the session retains its original
  execution target
