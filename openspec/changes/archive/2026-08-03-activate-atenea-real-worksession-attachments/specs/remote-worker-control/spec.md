## MODIFIED Requirements

### Requirement: Idempotent durable dispatch

Every remotely executed AgentRun MUST have a durable dispatch identity,
selected worker, session workspace identity, project/workload fingerprint,
canonical model, reasoning effort, accepted catalog revision and idempotency
contract before execution starts. Real-project dispatch MUST bind operator
input, its immutable effective execution profile and any immutable ordered
attachment manifest to the persisted project/workspace. An image-bearing run
SHALL use `project-codex-v3` and SHALL transmit only exact attachment UUID,
media type, byte size and SHA-256 references. No request may accept caller
commands, paths, remotes, endpoints, providers, configuration fragments,
environment, filenames, storage identities, URLs or attachment bytes.

#### Scenario: Dispatch is retried after a network timeout

- **WHEN** Atenea repeats an identical dispatch request with the same dispatch
  identity and workload/profile/attachment fingerprint
- **THEN** the worker returns the existing execution and lifecycle revision
  rather than starting another Codex turn

#### Scenario: Dispatch identity is reused with conflicting input

- **WHEN** a request reuses a dispatch identity with a different session,
  workspace, project, prompt, model, effort, catalog revision, attachment
  identity/order/hash or workload fingerprint
- **THEN** the worker rejects it fail-closed and preserves the original
  execution unchanged

#### Scenario: Profile syntax is valid but authority is not

- **WHEN** a request names a model absent from the accepted catalog, an effort
  absent from that model, a stale catalog/Codex version or a session/workspace
  pair outside the exact registry
- **THEN** semantic validation rejects it before execution state or a process
  is created

#### Scenario: Caller adds operational authority

- **WHEN** a protocol or API request supplies a command, provider, endpoint,
  path, service, host, slot, environment, credential, release URL, workspace
  authority or attachment storage authority
- **THEN** closed schema validation rejects the request and all persisted worker
  and control-plane ownership remains unchanged

## ADDED Requirements

### Requirement: Exact Codex image materialization

For an accepted `project-codex-v3` Atenea run, the runner SHALL derive each
retained source from configured roots and persisted remote
session/attachment identities, verify its private sidecar and complete content
identity, create only bounded execution-owned temporary copies, expose only
those copies read-only inside Bubblewrap and pass them as ordered fixed
`codex exec --image` arguments for both new and resumed turns. The retained
attachment root MUST NOT be visible to Codex.

#### Scenario: Two owned images are dispatched

- **WHEN** both sidecars and files match project, session, workspace, UUID,
  media type, size and SHA-256
- **THEN** Codex receives exactly two ordered read-only images and no other
  attachment or storage directory

#### Scenario: Retained ownership is incomplete or conflicting

- **WHEN** a file or sidecar is absent, symlinked, unlabelled, partial, foreign,
  ambiguously owned, permission-invalid or integrity-mismatched
- **THEN** the runner starts no Codex process, leaves retained content unchanged
  and reports one sanitized actionable failure

#### Scenario: Image-bearing execution terminates

- **WHEN** the run succeeds, fails, is cancelled, times out or the runner is
  interrupted
- **THEN** its exact temporary copies are removed and unrelated or ambiguous
  materializations are not touched

#### Scenario: Worker starts with stale materialization candidates

- **WHEN** startup reconciliation finds an execution-labelled image directory
- **THEN** it removes it only after proving the exact execution absent or
  terminal and otherwise retains it while reporting the ownership state

### Requirement: Compatible real attachment storage extension

The private attachment service SHALL retain the v1 health, metadata, content
and public response contracts while advertising real-project capability on a
separate authenticated endpoint. A real create SHALL require the fixed
canonical project, remote WorkSession UUID, workspace identity and explicit
non-synthetic scope, persist them in the private sidecar and expose no new path
authority. Base v1 readers SHALL continue retrieving the content after worker
or backend rollback.

#### Scenario: New backend reaches old attachment service

- **WHEN** real creation is requested but the authenticated capability endpoint
  is absent or incompatible
- **THEN** creation fails closed before content while existing list/download
  remains available

#### Scenario: Compatible old reader retrieves real content

- **WHEN** creation has been disabled and the base v1 service reads a retained
  extended sidecar
- **THEN** it verifies and returns the same public metadata/content without
  deleting or rewriting the extra ownership fields

#### Scenario: Real content is offered to the synthetic delete route

- **WHEN** any caller attempts exact synthetic deletion for a real attachment
- **THEN** the service rejects it and preserves the sidecar and bytes unchanged
