## MODIFIED Requirements

### Requirement: Bounded validated atomic upload

The attachment boundary SHALL reject empty, oversized, unsupported, ambiguous
or content-type-mismatched input before it becomes retained or indexed. The
default per-file limit SHALL be 16 MiB and default retained WorkSession quota
SHALL be 256 MiB. A routine operator upload SHALL be classified by Atenea as
`OPERATOR_UPLOAD`, SHALL use `SESSION` retention and SHALL derive `IMAGE` or
`FILE` from validated content; caller attempts to claim browser, trace, report
or evidence authority MUST fail closed.

#### Scenario: Oversized upload is attempted

- **WHEN** input exceeds the configured per-file or session quota
- **THEN** Atenea returns an actionable limit state and neither metadata nor
  retained content is created

#### Scenario: Declared image contains another format

- **WHEN** the declared media type does not match the validated content
- **THEN** the worker rejects it fail closed and preserves existing attachments

#### Scenario: Routine operator claims evidence authority

- **WHEN** an operator upload supplies browser, trace, report, `EVIDENCE` or
  another classification that Atenea did not derive
- **THEN** Atenea rejects it before worker storage and creates no attachment

### Requirement: Explicit retention and rollback preservation

Each attachment SHALL record `TRANSIENT` (24 hours), `SESSION` (30 days) or
`EVIDENCE` (180 days) retention. `retainUntil` SHALL be a minimum keep boundary
and SHALL prevent new turn binding after expiry, but SHALL NOT by itself
authorize deletion. Disabling creation SHALL preserve indexed content,
immutable turn bindings and authenticated retrieval. General deletion SHALL
require a later separately specified ownership, tombstone and backup policy.

#### Scenario: Attachment creation is rolled back

- **WHEN** the global or project create/bind switch is disabled
- **THEN** new uploads and new bindings are rejected actionably while retained
  authorized attachments and historical bindings remain byte-identical and
  retrievable

#### Scenario: Unbound expired image is selected

- **WHEN** an operator attempts to bind an image after its `retainUntil`
- **THEN** the new turn is rejected without changing the image, conversation or
  AgentRun state

#### Scenario: Bound failed run is retried after retention eligibility

- **WHEN** a safely failed AgentRun is retried with its immutable image
  manifest after `retainUntil` while the retained bytes still verify
- **THEN** the retry reuses the exact existing binding and does not create a new
  attachment or silently remove the image

### Requirement: Preproduction activation boundary

The capability SHALL default off globally and SHALL keep synthetic fixture
admission separate from real-project admission. Real creation SHALL require an
exact canonical project identity registered by code, present in the runtime
allowlist, pinned to the expected remote worker and snapshotted as a policy
revision on a newly created WorkSession. An unknown project or a WorkSession
that predates activation MUST remain ineligible. `atenea` SHALL be the only
real project enabled by this change.

#### Scenario: New exact Atenea session is eligible

- **WHEN** global creation and canonical project `atenea` are enabled and a new
  remote WorkSession snapshots `atenea-real-attachments-v1` on AX42
- **THEN** Atenea may expose real upload/bind readiness for that session after
  worker compatibility and quota checks pass

#### Scenario: Existing session predates activation

- **WHEN** an open Atenea WorkSession has no accepted attachment-policy revision
- **THEN** enabling the project does not retroactively permit upload or binding

#### Scenario: Foreign or unknown project is configured

- **WHEN** configuration names Beautips, another disabled project, a display
  name or an unregistered identity
- **THEN** attachment creation remains disabled for it and unknown configured
  identities fail startup without changing any session

## ADDED Requirements

### Requirement: Immutable image-bearing turn submission

An image-bearing operator turn SHALL carry a client request UUID and an ordered
list of one to four distinct WorkSession attachment UUIDs totalling no more
than 32 MiB. Atenea SHALL validate same project, WorkSession, worker, remote
session, workspace, real storage scope, non-expired new-binding eligibility,
image kind, PNG/JPEG/WebP media type, size and SHA-256 before atomically
creating the visible turn, immutable bindings and AgentRun attachment manifest.

#### Scenario: Image-bearing turn is accepted

- **WHEN** an eligible operator submits a message with two valid ordered images
- **THEN** one visible turn and one AgentRun commit with the same two immutable
  bindings and one canonical attachment-manifest SHA-256 before dispatch

#### Scenario: Accepted submission response is lost

- **WHEN** the client repeats the same request UUID, normalized message and
  ordered image identities after a timeout
- **THEN** Atenea returns the original turn and AgentRun without another binding,
  dispatch or Codex turn

#### Scenario: Submission identity is reused with different content

- **WHEN** the same client request UUID carries a different message, image,
  order or attachment manifest
- **THEN** Atenea returns conflict and preserves the first submission unchanged

#### Scenario: One selected image is foreign or altered

- **WHEN** any selected identity belongs to another session/project/worker,
  exceeds a bound, is duplicated, expired for new binding, is not an image or
  no longer matches retained integrity
- **THEN** the entire turn fails before persistence or dispatch and every
  attachment remains unchanged

### Requirement: State-first web screenshot composition

The web conversation SHALL present attachment readiness and the next action in
the existing composer without adding a competing dashboard. When ready, file
selection and image clipboard paste SHALL use the same bounded upload path, a
successful image SHALL become selected for the next message, and compact
selected-image controls SHALL permit removal before submission. Send SHALL
remain the sole primary action.

#### Scenario: New Atenea session is ready

- **WHEN** capability, project, session, worker and quota checks pass
- **THEN** the operator sees one secondary attach action, accepted image limits,
  selected-image state and the primary Send action without scrolling past the
  current execution state

#### Scenario: Capability is blocked

- **WHEN** the global gate, project gate, session policy, worker or quota blocks
  creation
- **THEN** the composer disables or omits the upload affordance and displays one
  concise reason plus the applicable next action instead of allowing a doomed
  upload

#### Scenario: Submit outcome is uncertain

- **WHEN** upload succeeded but turn submission fails or times out without an
  accepted response
- **THEN** the selected images and stable client request UUID remain available
  for safe retry and are cleared only after Atenea confirms acceptance

#### Scenario: Accepted historical turn is rendered

- **WHEN** the conversation reloads after an image-bearing turn
- **THEN** it shows bounded attachment metadata and authenticated download for
  that exact turn without embedding bytes, worker paths or storage identities

