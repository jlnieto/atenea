# codex-environment-parity Specification

## Purpose
TBD - created by archiving change establish-remote-codex-platform-program. Update Purpose after archive.
## Requirements
### Requirement: Versioned Codex operating context
The worker SHALL assemble global instructions, project instructions, approved custom skills and plugin declarations from versioned sources with a recorded context version per AgentRun.

#### Scenario: Run starts after a context update
- **WHEN** a reviewed context version is deployed
- **THEN** new runs record and use that version while prior audit records remain attributable to their original version

### Requirement: Declared toolchain compatibility
The worker SHALL provide and verify the JDK, Maven, Node, Docker, browser, Playwright, Git and supporting tool versions required by each active project manifest.

#### Scenario: Legacy Java project is selected
- **WHEN** ISC or Recambios requests its declared build and runtime toolchains
- **THEN** the worker selects the compatible toolchain without changing the toolchain of another session

### Requirement: Independent Codex authentication
Codex on the worker MUST be authenticated explicitly for the intended ChatGPT mode and MUST pass a sanitized authentication guard before accepting real work.

#### Scenario: Worker has no valid ChatGPT session
- **WHEN** the authentication guard detects missing, expired or API-key-mode credentials
- **THEN** execution is blocked with an actionable authentication state and no token content is exposed

### Requirement: Secret separation
Authentication tokens, SSH private keys, API credentials and project secrets MUST NOT be stored in OpenSpec artifacts, Git repositories, ordinary logs or copied laptop configuration bundles.

#### Scenario: Project declares a named secret
- **WHEN** a session starts a project requiring that secret
- **THEN** the runtime obtains it from the approved secret boundary and exposes it only to the authorized session process

### Requirement: Laptop workflow parity gate
The worker SHALL be compared against a recorded baseline of the operator's real laptop workflows before becoming the default executor.

#### Scenario: Parity assessment is run
- **WHEN** the pilot project is evaluated
- **THEN** prompting, Git changes, project control, logs, browser testing, screenshots and delivery are each classified as equivalent, intentionally changed or blocked

### Requirement: Controlled tool updates
Codex, OpenSpec, browser and build-tool updates SHALL be version-pinned and promoted only after compatibility checks for the active project set.

#### Scenario: New Codex CLI version is available
- **WHEN** an update is proposed
- **THEN** it is tested in a non-authoritative worker image and can be rolled back without rewriting session source or history
