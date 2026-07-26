# codex-environment-parity Specification

## Purpose
TBD - created by archiving change establish-remote-codex-platform-program. Update Purpose after archive.
## Requirements
### Requirement: Versioned Codex operating context
The worker SHALL assemble global instructions, project instructions, approved
custom skills, plugin declarations and Codex configuration from an explicit
allowlist of versioned non-secret sources. Each AgentRun SHALL record the
effective context version and hashes. Laptop authentication, history, sessions,
SSH keys and whole home directories MUST NOT be promoted as context.

#### Scenario: Run starts after a context update
- **WHEN** a reviewed context version is deployed
- **THEN** new runs record and use that version while prior audit records remain attributable to their original version

#### Scenario: Laptop context is promoted
- **WHEN** the parity process examines the laptop Codex home
- **THEN** it copies only allowlisted configuration, instructions and skills and explicitly excludes credentials, history, sessions, logs and caches

### Requirement: Declared toolchain compatibility
The worker SHALL install toolchains from version-pinned, reproducible
definitions and verify the JDK, Maven, Node, container engine, Chromium,
Playwright, Git and supporting versions required by each active project
manifest. Selecting one session's toolchain MUST NOT mutate another session's
effective toolchain.

#### Scenario: Legacy Java project is selected
- **WHEN** ISC or Recambios requests its declared build and runtime toolchains
- **THEN** the worker selects the compatible toolchain without changing the toolchain of another session

### Requirement: Independent Codex authentication
Codex on the worker MUST be installed from an official pinned release,
authenticated explicitly through a supported headless flow for the intended
ChatGPT mode, and pass a sanitized authentication guard before accepting real
work. Laptop credential files MUST NOT be copied to establish the login.

#### Scenario: Worker has no valid ChatGPT session
- **WHEN** the authentication guard detects missing, expired or API-key-mode credentials
- **THEN** execution is blocked with an actionable authentication state and no token content is exposed

#### Scenario: Headless login is required
- **WHEN** the operator authenticates the AX42 without a browser on the worker
- **THEN** Codex uses the supported device flow and stores the resulting credentials with permissions restricted to the intended worker identity

### Requirement: Secret separation
Authentication tokens, SSH private keys, API credentials and project secrets MUST NOT be stored in OpenSpec artifacts, Git repositories, ordinary logs or copied laptop configuration bundles.

#### Scenario: Project declares a named secret
- **WHEN** a session starts a project requiring that secret
- **THEN** the runtime obtains it from the approved secret boundary and exposes it only to the authorized session process

### Requirement: Laptop workflow parity gate
The worker SHALL be compared against a recorded baseline of the operator's real
laptop workflows before becoming the default executor. The comparison SHALL
include persistent disconnect/resume, prompt and image input, Git changes,
project lifecycle, logs, browser tests, screenshot ordering and delivery.

#### Scenario: Parity assessment is run
- **WHEN** the pilot project is evaluated
- **THEN** prompting, Git changes, project control, logs, browser testing, screenshots, disconnect/resume and delivery are each classified as equivalent, intentionally changed or blocked

### Requirement: Controlled tool updates
Codex, OpenSpec, browser and build-tool updates SHALL be version-pinned and promoted only after compatibility checks for the active project set.

#### Scenario: New Codex CLI version is available
- **WHEN** an update is proposed
- **THEN** it is tested in a non-authoritative worker image and can be rolled back without rewriting session source or history
