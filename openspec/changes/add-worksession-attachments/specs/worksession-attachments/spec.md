## ADDED Requirements

### Requirement: Immutable WorkSession attachment ownership

Every attachment SHALL be indexed under exactly one WorkSession and project
with an immutable attachment identity and MAY reference only an AgentRun owned
by that WorkSession.

#### Scenario: Operator uploads an image

- **WHEN** an authenticated operator uploads an allowed image to a WorkSession
- **THEN** Atenea registers one immutable attachment for that session and no
  other session or project can list or retrieve it

#### Scenario: AgentRun belongs to another session

- **WHEN** attachment registration names an AgentRun outside the WorkSession
- **THEN** registration fails without creating metadata or retained content

### Requirement: Split metadata and content authority

Atenea/PostgreSQL SHALL own ordered attachment metadata while AX42 SHALL store
content under an ownership-derived opaque identity. Client APIs SHALL NOT
expose or accept worker filesystem paths.

#### Scenario: Client retrieves attachment metadata

- **WHEN** an authorized client reads an attachment
- **THEN** it receives the opaque attachment identity, source, kind, media type,
  size, retention, creation time and SHA-256 identity but no host path

### Requirement: Bounded validated atomic upload

The attachment boundary SHALL reject empty, oversized, unsupported, ambiguous
or content-type-mismatched input before it becomes retained or indexed. The
default per-file limit SHALL be 16 MiB and default retained WorkSession quota
SHALL be 256 MiB.

#### Scenario: Oversized upload is attempted

- **WHEN** input exceeds the configured per-file or session quota
- **THEN** Atenea returns an actionable limit state and neither metadata nor
  retained content is created

#### Scenario: Declared image contains another format

- **WHEN** the declared media type does not match the validated content
- **THEN** the worker rejects it fail closed and preserves existing attachments

### Requirement: Integrity and idempotency

Content SHALL be streamed to a temporary owned file, SHA-256 verified and
atomically retained. Reusing an attachment identity with identical content
SHALL return the same record; conflicting reuse SHALL change nothing.

#### Scenario: Upload response is lost

- **WHEN** Atenea retries the same attachment identity and content
- **THEN** the worker returns the original storage identity and digest without
  storing a duplicate

### Requirement: Deterministic screenshot resolution

Latest, previous and last-N screenshot references SHALL resolve only within the
requested WorkSession and optional source, ordered by creation time descending
then attachment identity descending.

#### Scenario: Two projects capture screenshots

- **WHEN** an operator requests the latest screenshot for one WorkSession
- **THEN** only that WorkSession set participates even if another project has a
  newer global filesystem timestamp

### Requirement: Authenticated scoped retrieval

Upload, listing, metadata and content retrieval SHALL require the authenticated
operator boundary and exact WorkSession ownership. Foreign and missing
identities SHALL fail without disclosing foreign storage state.

#### Scenario: Foreign session requests exact content

- **WHEN** a request presents an attachment identity owned by another session
- **THEN** the request is denied and the foreign content remains unchanged

### Requirement: Explicit retention and rollback preservation

Each attachment SHALL record `TRANSIENT` (24 hours), `SESSION` (30 days) or
`EVIDENCE` (180 days) retention. Disabling creation SHALL preserve indexed
content and retrieval; general deletion SHALL NOT occur in Phase 5.

#### Scenario: Attachment creation is rolled back

- **WHEN** the Phase 5 creation switch is disabled
- **THEN** new uploads are rejected actionably while retained authorized
  attachments remain byte-identical and retrievable

### Requirement: Preproduction activation boundary

The capability SHALL default off and SHALL be restricted to an exact synthetic
project until external backup for authoritative non-Git artifacts is configured
and restore-tested.

#### Scenario: Real project lacks external backup activation

- **WHEN** a real-project WorkSession attempts retained attachment creation
- **THEN** Atenea keeps the capability disabled without falling back to a global
  upload directory
