## MODIFIED Requirements

### Requirement: Persisted workspace provisioning before project dispatch

For every explicitly enabled real project, Atenea SHALL persist the remote
WorkSession and queued AgentRun before invoking a project-specific,
authenticated and idempotent workspace mediator. The worker SHALL dispatch
only after that mediator returns ownership matching the complete persisted
session, workspace, project and branch identity.

#### Scenario: Exact Atenea or Beautips workspace is absent

- **WHEN** the first queued AgentRun reaches its accepted project route
- **THEN** the worker invokes only that project's reviewed mediator and returns the same ready ownership on repetition

#### Scenario: Project identity is foreign or ambiguous

- **WHEN** an ensure request is not exactly Atenea or Beautips, or includes caller authority outside the schema
- **THEN** it is rejected before any mediator or ownership helper runs
