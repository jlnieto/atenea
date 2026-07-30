## ADDED Requirements

### Requirement: Explicit Beautips routing activation

After onboarding and independent restore acceptance pass, production Atenea SHALL
enable remote routing only for the exact Beautips identity. Activation
MUST keep generic real-project and synthetic routing disabled, provision at
most the one open Beautips WorkSession outside slot 1, and preserve the manual
administrative runtime, unrelated projects and production data.

#### Scenario: New exact Beautips session is activated

- **WHEN** the global worker gate and exact Beautips gate are enabled and a new
  canonical Beautips WorkSession is opened
- **THEN** Atenea pins it to AX42, provisions one exact workspace in slots 2–4
  and dispatches its turns through the registered project route

#### Scenario: Partial or unrelated project identity is submitted

- **WHEN** project name, repository path, base branch, worker capability,
  commit or manifest differs from the accepted Beautips identity
- **THEN** no Beautips workspace, allocation, registration or remote AgentRun
  is created

#### Scenario: Beautips routing is disabled

- **WHEN** the operator disables the exact Beautips selector or worker
  execution gate
- **THEN** no new Beautips session or turn is routed remotely, existing
  persisted ownership is retained for reconciliation, and no session is
  silently moved

#### Scenario: Accepted route is re-enabled

- **WHEN** exact disabled ownership, worker health, backup health and
  non-impact fingerprints pass
- **THEN** the same registered workspace can be re-enabled without adopting,
  restarting or changing the administrative Beautips runtime

#### Scenario: Real activation acceptance passes

- **WHEN** a production-control-plane Beautips WorkSession completes a real
  turn, continuation, private preview and reversible disable boundary
- **THEN** Beautips is declared available for normal Atenea operation while
  every other real-project route remains disabled
