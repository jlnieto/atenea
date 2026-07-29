## ADDED Requirements

### Requirement: Sanitized database lifecycle evidence

Database lifecycle acceptance SHALL register only sanitized command summaries,
ownership manifests, integrity hashes and restore assertions as WorkSession
evidence. Raw snapshots, dumps, row values, database credentials, connection
strings, environment captures and secret files MUST NOT be attachments.

#### Scenario: Snapshot acceptance is retained

- **WHEN** a synthetic database snapshot and restore passes
- **THEN** the WorkSession may retain its sanitized engine, revision, size,
  SHA-256 and result report without retaining raw database content

#### Scenario: Raw dump is offered as an attachment

- **WHEN** an upload is identified as a database dump, row export, credential
  file or connection configuration
- **THEN** attachment registration rejects it before retaining content or
  metadata
