## MODIFIED Requirements

### Requirement: Durable project workspace provisioning

Before dispatching a real-project AgentRun, Atenea MUST persist the remote
WorkSession and queued AgentRun, then ensure the exact persisted WorkSession
owns a ready canonical mirror, session worktree, admission allocation and
project registration on its selected worker. Provisioning MUST invoke only the
explicitly enabled project's authenticated, idempotent mediator and MUST be
derived from persisted project, branch, worker and workspace identities. The
worker MUST dispatch only after the mediator returns ownership matching the
complete persisted session, workspace, project and branch identity. The
request MUST NOT accept a caller command, path, remote, slot, port,
environment or credential.

#### Scenario: First turn provisions the selected workspace

- **WHEN** a durable queued AgentRun belongs to a newly selected exact real-project WorkSession
- **THEN** Atenea ensures the same remote workspace identity before dispatch and the worker returns its persisted ready ownership

#### Scenario: Provisioning is repeated after interruption

- **WHEN** Atenea retries or reconciles the same durable AgentRun after losing the provisioning response
- **THEN** the worker returns the same workspace and allocation without creating another worktree, allocation, registration or admission lease

#### Scenario: Provisioning ownership is incomplete or conflicting

- **WHEN** any persisted or discovered project, Git, workspace, allocation, slot, label or registration identity differs
- **THEN** provisioning and dispatch fail closed without modifying or cleaning the conflicting resource

#### Scenario: Existing session predates activation

- **WHEN** routing gates change while a local or differently pinned WorkSession is already open
- **THEN** no workspace is provisioned and the session retains its original execution target

#### Scenario: Exact Atenea or Beautips workspace is absent

- **WHEN** the first queued AgentRun reaches its accepted project route
- **THEN** the worker invokes only that project's reviewed mediator and returns the same ready ownership on repetition

#### Scenario: Project identity is foreign or ambiguous

- **WHEN** an ensure request is not exactly Atenea or Beautips, or includes caller authority outside the schema
- **THEN** it is rejected before any mediator or ownership helper runs
