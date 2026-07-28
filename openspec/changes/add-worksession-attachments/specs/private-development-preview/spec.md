## MODIFIED Requirements

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
