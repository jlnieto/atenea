# private-development-preview Specification

## Purpose
TBD - created by archiving change establish-remote-codex-platform-program. Update Purpose after archive.
## Requirements
### Requirement: Private preview by default
Every session preview SHALL be reachable only through the approved private network or an authenticated control-plane proxy unless the operator explicitly creates a time-bounded public share.

#### Scenario: Internet client probes a development port
- **WHEN** a client outside the approved private network addresses a worker preview or Codex port
- **THEN** no development service is reachable

### Requirement: Session-scoped preview identity
Atenea SHALL expose preview status and a stable session-scoped URL that routes to the correct runtime without requiring the operator to know host port allocations.

#### Scenario: Preview becomes healthy
- **WHEN** the runtime manifest health check passes
- **THEN** Atenea marks the preview ready and offers its URL on web and supported mobile surfaces

#### Scenario: Preview is not ready
- **WHEN** build, start or health validation fails
- **THEN** Atenea shows the blocked state and next action instead of an apparently usable empty link

### Requirement: Localhost compatibility tunnel
The platform SHALL provide a generated SSH local-forward path for applications whose cookies, callbacks or origin policy require `localhost`.

#### Scenario: Project requires localhost origin
- **WHEN** a project's runtime manifest declares localhost compatibility mode
- **THEN** the operator receives a bounded tunnel command and opens the application through a local URL without publishing the remote port

### Requirement: Worker-side browser verification
The worker SHALL support project-defined Playwright/Chromium checks that validate persistence or fixtures, rendered DOM and actual visual usability at required viewports.

#### Scenario: Responsive UI change is verified
- **WHEN** Codex completes a UI change for a responsive screen
- **THEN** the verification records critical visible-state assertions and inspected desktop and mobile screenshots before reporting success

### Requirement: Session artifact registration
Screenshots, traces, reports and other browser evidence SHALL be registered
through the WorkSession attachment boundary with immutable session, optional
run, source, timestamp, content type, size, retention and integrity metadata.

#### Scenario: Playwright captures a screenshot
- **WHEN** Playwright captures an accepted screenshot for a session preview
- **THEN** Atenea can display or download that exact retained attachment from
  the originating WorkSession and optional AgentRun without exposing a worker
  path

### Requirement: Latest screenshot semantics
“Latest screenshot”, “previous screenshot” and “last N screenshots” SHALL
resolve deterministically inside the current WorkSession and optional requested
source by descending creation time and attachment identity.

#### Scenario: Operator asks for the last three screenshots
- **WHEN** a session contains screenshots from multiple sources
- **THEN** Codex receives the three newest matching retained attachments and
  does not inspect another session, project or global image folder

### Requirement: Mobile preview access
An authenticated mobile operator on the private network SHALL be able to see preview readiness, open compatible previews and inspect browser evidence without keeping the laptop online.

#### Scenario: Laptop is offline
- **WHEN** a worker preview and its artifacts are ready while the laptop is disconnected
- **THEN** the Android operator can inspect the state and evidence through Atenea and the private route
