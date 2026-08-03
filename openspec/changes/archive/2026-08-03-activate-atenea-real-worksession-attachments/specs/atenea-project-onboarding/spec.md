## ADDED Requirements

### Requirement: Project-scoped real Atenea screenshot activation

Atenea SHALL permit authoritative operator screenshot creation only when the
global attachment gate and canonical `atenea` gate are enabled, the WorkSession
was created with policy revision `atenea-real-attachments-v1`, exact AX42
remote workspace ownership is complete, the compatible storage/runner
capabilities are healthy and the independent backup prerequisite is accepted.
No other project, existing session, production data path or deployment
authority SHALL become eligible.

#### Scenario: First real screenshot turn succeeds

- **WHEN** the operator uploads one generated non-secret screenshot in a new
  clean Atenea WorkSession and submits it with an exact message
- **THEN** one image-bearing AgentRun delivers that image to Codex, returns one
  response demonstrating image understanding and retains one immutable turn
  binding

#### Scenario: Same-thread continuation follows the screenshot turn

- **WHEN** the first image-bearing run is terminal and the operator submits a
  later text-only turn
- **THEN** Atenea reuses the same WorkSession, workspace and Codex thread
  without implicitly attaching an older image

#### Scenario: Beautips attempts attachment creation

- **WHEN** a Beautips or unrelated WorkSession reaches the attachment API during
  Atenea-only activation
- **THEN** it receives a project-disabled state and no metadata, content,
  binding, AgentRun or gate changes

#### Scenario: Atenea attachment activation is rolled back

- **WHEN** the exact project then global gates are disabled and rollback is
  repeated
- **THEN** new create/bind remains unavailable while historical content,
  bindings, Git, routing, production, preview, Beautips and unrelated worker
  resources remain unchanged

