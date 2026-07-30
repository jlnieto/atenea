## ADDED Requirements

### Requirement: Canonical source admission and draft quarantine

Every write-capable AgentRun SHALL bind an exact repository, branch, canonical
commit, mirror observation and clean WorkSession HEAD before execution. For a
new implementation the HEAD MUST equal the accepted canonical commit; merely
being its ancestor is insufficient. The canonical commit SHALL be observed and
persisted at runtime by fixed control-plane and worker mirror authorities and
MUST NOT be a self-referential compile-time constant in the observed
repository. A dirty stale WorkSession SHALL be retained as a blocked draft and
SHALL NOT be automatically rebased, merged, reset, committed, copied or
discarded.

#### Scenario: Clean WorkSession matches canonical source

- **WHEN** a write-capable run names a clean WorkSession whose exact HEAD equals the persisted canonical branch HEAD
- **THEN** the worker admits the run with that immutable source fingerprint

#### Scenario: WorkSession is behind canonical source

- **WHEN** the WorkSession HEAD is an ancestor of a newer accepted canonical HEAD
- **THEN** the write is blocked before Codex starts and Atenea offers creation of a new clean WorkSession

#### Scenario: Canonical observations differ or move

- **WHEN** the control plane and worker mirror observe different commits, the configured ref is missing, or the ref moves before admission
- **THEN** dispatch is blocked without substituting a compile-time pin or accepting an ancestor

#### Scenario: Stale WorkSession contains a draft

- **WHEN** a stale WorkSession has modified or untracked files
- **THEN** Atenea fingerprints and retains the draft unchanged and requires reviewed file-by-file porting into a new current WorkSession

### Requirement: Closed mediated validation

Atenea SHALL provide versioned symbolic operations for backend tests, web
build, Android build, Playwright acceptance, strict OpenSpec validation and
worker contract suites. Each operation SHALL bind exact WorkSession,
repository, source tree and validator ownership, use a finite timeout, retain
sanitized results and artifacts, and SHALL NOT expose a Docker socket,
arbitrary command, image, compose file, environment, path, host, slot,
endpoint or credential to the caller.

#### Scenario: Operator requests an accepted backend validation

- **WHEN** the exact WorkSession and source tree request the reviewed backend-test operation
- **THEN** the mediator runs its fixed definition with bounded authority and persists the exit status, duration and sanitized artifact manifest

#### Scenario: Caller changes validator authority

- **WHEN** a request supplies or alters a command, path, image, environment, service, slot, endpoint or foreign workspace identity
- **THEN** the mediator rejects it before starting a process or mounting a resource

#### Scenario: Validation request is repeated

- **WHEN** the same validation identity, source tree and validator revision are submitted again
- **THEN** Atenea returns or reconciles the same operation and does not start an ambiguous duplicate

### Requirement: Truthful work acceptance

Atenea SHALL represent Codex process outcome separately from validation and
integration readiness. A successful AgentRun SHALL mean only that Codex
returned a terminal result. Work SHALL remain draft, validating or blocked
until every required check for the immutable source tree passes, and SHALL
become integration-ready only after freshness and review gates also pass.

#### Scenario: Codex returns an uncompiled draft

- **WHEN** Codex exits successfully but a required build or test is missing or failed
- **THEN** the AgentRun may show process success while the WorkSession remains blocked and identifies the required next validation

#### Scenario: Source changes after validation

- **WHEN** any tracked or untracked source content changes after required checks passed
- **THEN** prior validation is invalidated and integration readiness is removed

#### Scenario: Every acceptance gate passes

- **WHEN** required builds, tests, visual checks, source freshness and review pass for one exact tree
- **THEN** Atenea marks that tree integration-ready without committing, publishing or deploying it implicitly

### Requirement: Reviewed instruction bundle

Every AgentRun SHALL persist the fingerprint and sources of a reviewed
platform/project instruction bundle. Ambient user configuration SHALL remain
excluded, but the runner MUST NOT silently ignore applicable repository
operating rules. Unknown, mutable, secret-bearing or caller-supplied rule
sources SHALL be rejected.

#### Scenario: Project has an accepted AGENTS contract

- **WHEN** Atenea resolves a platform bundle and repository-owned `AGENTS.md` for an exact source revision
- **THEN** the runner applies that immutable reviewed bundle and persists its fingerprint with the AgentRun

#### Scenario: Rule source is ambiguous

- **WHEN** rules resolve outside accepted repository/platform ownership or change after fingerprinting
- **THEN** dispatch is blocked without falling back to ignored or ambient instructions

### Requirement: Exact multi-repository authority

A cross-repository change SHALL declare each repository's exact identity,
branch, commit, mirror, WorkSession role and read/write authority. Code,
programme OpenSpec and worker source SHALL use separate owned worktrees and
validation profiles linked by one change identity. No repository or installed
root-owned file SHALL become writable merely because another repository is in
scope.

#### Scenario: Code change also requires worker and OpenSpec updates

- **WHEN** an accepted change declares all three exact repository roles
- **THEN** Atenea creates or selects separate owned worktrees and tracks validation/readiness for each component

#### Scenario: Code-only WorkSession attempts worker modification

- **WHEN** a code-only session targets the installed worker or an undeclared repository
- **THEN** the write is rejected and existing worker, repositories and services remain unchanged

### Requirement: Immutable effective Codex execution profile

Every AgentRun SHALL persist the canonical model, reasoning effort, catalog
revision, Codex version and configuration source that govern its execution.
Resolution SHALL occur before durable dispatch using next-turn, WorkSession,
project, platform and accepted worker-default precedence. A settings change
MUST affect only future AgentRuns and MUST NOT rewrite execution history.

#### Scenario: Operator changes the WorkSession effort

- **WHEN** an authenticated operator changes a WorkSession from medium to high while no turn is being submitted
- **THEN** the next AgentRun persists high effort while every earlier AgentRun retains its original effective profile

#### Scenario: Next-turn override is supplied

- **WHEN** a valid next-turn model or effort override is submitted with a prompt
- **THEN** it is consumed by that AgentRun only and the WorkSession default remains unchanged

#### Scenario: Profile cannot be resolved exactly

- **WHEN** the requested model, effort, catalog revision or selected worker capability is missing, unsupported, stale or ambiguous
- **THEN** dispatch is blocked with the unsupported field and no silent model substitution occurs

### Requirement: Closed model and effort authority

Atenea SHALL expose only model identifiers and reasoning efforts advertised by
the selected compatible worker and permitted by platform/project policy. The
worker runner SHALL derive reviewed Codex flags from those fields and SHALL NOT
accept an arbitrary provider, endpoint, configuration fragment, argument array,
path, environment value or credential.

#### Scenario: Supported profile is selected

- **WHEN** an operator selects a catalog-advertised model and supported low, medium, high or xhigh effort
- **THEN** Atenea displays the effective selection and the worker invokes Codex with exactly that validated profile

#### Scenario: Caller injects a Codex option

- **WHEN** a request includes an unrecognized effort, arbitrary model, provider, endpoint, flag or configuration value
- **THEN** Atenea and the worker reject it before starting Codex or changing persisted settings

### Requirement: Safe durable progress timeline

Atenea SHALL persist and publish monotonically sequenced, sanitized progress
events from a closed operational taxonomy. Each AgentRun SHALL retain at most
200 coalesced events and SHALL always retain its current state, latest event,
elapsed time and required next action. Raw chain-of-thought, model reasoning,
command arguments, command output, environment values and secret-bearing
payloads MUST NOT be stored or published.

#### Scenario: Codex performs a multi-step task

- **WHEN** the worker receives accepted structured lifecycle and tool events
- **THEN** web and Android show concise stages such as preparing, inspecting, checking, waiting and finalizing in monotonic order

#### Scenario: Client reconnects after missing events

- **WHEN** a client resumes with its last persisted event sequence
- **THEN** Atenea replays the durable gap once and then continues live publication without duplicating events

#### Scenario: Event contains unsafe detail

- **WHEN** an event contains reasoning, raw command/output, credential-shaped content or an unsupported event type
- **THEN** the unsafe content is discarded and only an allowed generic state may be retained

### Requirement: Idempotent self-service recovery

An authenticated operator SHALL be able to cancel an exact non-terminal
AgentRun, request reconciliation of an exact unreachable/reconciling dispatch,
retry a safely terminal failed run and obtain a sanitized diagnostic summary.
Recovery commands SHALL be persisted, idempotent and scoped to the operator's
WorkSession.

#### Scenario: Unreachable run is reconciled

- **WHEN** an operator requests reconciliation for a persisted non-terminal dispatch
- **THEN** Atenea observes that same worker execution and does not create a replacement AgentRun

#### Scenario: Failed run is retried safely

- **WHEN** the worker proves the previous dispatch terminal or absent and the WorkSession has no non-terminal run
- **THEN** Atenea creates one new AgentRun linked to the failed run and keeps the original attempt unchanged

#### Scenario: Prior execution may still be live

- **WHEN** terminal or absent ownership cannot be proven
- **THEN** retry is rejected and Atenea presents reconciliation or privileged assistance as the next action

### Requirement: Privileged operational boundary

Routine operators, privileged operators and platform administrators SHALL have
distinct action allowlists. Privileged actions SHALL use fixed mediated
operations and exact persisted ownership. No Atenea endpoint SHALL accept an
arbitrary host, service, command, slot or resource target.

#### Scenario: Routine operator requests host restart

- **WHEN** a routine WorkSession operator attempts to restart AX42, a worker service or Codex
- **THEN** the request is denied without changing platform state and the required administrator role is shown

#### Scenario: Privileged operator restarts an exact execution service

- **WHEN** policy permits a mediated restart and the complete worker/session/service identity matches
- **THEN** only the reviewed service is restarted and its persisted runs are reconciled before new dispatch

### Requirement: Managed Codex version lifecycle

Atenea SHALL expose the selected worker's installed, current and previous Codex
versions plus catalog/compatibility state. A real update SHALL require separate
platform-administrator authorization, zero active executions, verified release
input, version-matched schema checks, focused contracts, health and one canary.
The previous verified version SHALL remain available for exact rollback.

#### Scenario: New Codex version is available

- **WHEN** an administrator requests a read-only update plan
- **THEN** Atenea reports current, candidate, compatibility gates and expected service impact without installing anything

#### Scenario: Active execution exists

- **WHEN** update activation is requested while any worker execution is non-terminal
- **THEN** activation is blocked and no binary, link or service changes

#### Scenario: Canary or compatibility fails

- **WHEN** schema, contract, health or canary acceptance fails after staging
- **THEN** the current version remains active or the previous verified link is restored without restarting project runtimes or unrelated slots

### Requirement: State-first cross-surface controls

Web and Android SHALL show current run state, effective model, reasoning effort,
Codex version, elapsed time, latest progress and primary next action without
scrolling. Both surfaces SHALL consume the same authorization and read model,
and errors SHALL state what happened and what the operator can do next.

#### Scenario: Run is active

- **WHEN** an AgentRun is queued, preparing, running, waiting or reconciling
- **THEN** its state and latest safe progress are immediately visible and cancel or reconciliation is the single applicable primary action

#### Scenario: Run fails safely

- **WHEN** an AgentRun reaches a retryable or administrator-required failure
- **THEN** the conversation distinguishes retry, reconcile and request-admin actions rather than showing a generic error
