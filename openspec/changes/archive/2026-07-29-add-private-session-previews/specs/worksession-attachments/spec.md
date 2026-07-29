## ADDED Requirements

### Requirement: Preview browser evidence ownership

Every screenshot, trace or browser report accepted for a preview SHALL be
registered through the attachment boundary under the exact preview
WorkSession, project and optional same-session AgentRun with `PLAYWRIGHT` source
and recorded preview identity. Preview teardown or expiry SHALL NOT change its
attachment ordering, integrity or retention.

#### Scenario: Browser captures desktop and mobile evidence

- **WHEN** the mediated check accepts the ready preview at both required
  viewports
- **THEN** each retained artifact is indexed only under the originating
  WorkSession and optional AgentRun and remains byte-identical after route
  teardown

#### Scenario: Foreign AgentRun is supplied

- **WHEN** preview evidence names an AgentRun owned by another WorkSession
- **THEN** attachment registration and preview acceptance fail without
  retaining content or modifying the foreign run
