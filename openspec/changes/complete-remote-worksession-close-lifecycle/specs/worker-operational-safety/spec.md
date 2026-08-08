## ADDED Requirements

### Requirement: Monotonic exact session ownership finalization

Worker-side WorkSession finalization SHALL serialize ensure and release under
one finite lifecycle lock, validate every candidate before the first mutation,
and persist an immutable staged journal that can resume only the same release.
It SHALL remove exact-owned ephemeral runtime, preview, broker/proxy, listener,
session-image and temporary browser/materialization resources; unregister the
exact project workspace; release heavy admission before normal admission; and
retire the active allocation by same-filesystem rename. It SHALL retain the
workspace record, worktree, Git, turns, AgentRuns, attachments, logs,
artifacts, release receipt, backups and policy-retained data volumes.

#### Scenario: Exact session has no live ephemeral resources

- **WHEN** all registration, admission, allocation and workspace identities
  belong to the terminal session and its ephemeral inventory is empty
- **THEN** finalization unregisters it, releases its permits, retires its
  allocation and records a reusable exact receipt

#### Scenario: Exact session has stopped ephemeral resources

- **WHEN** every candidate has the complete session, runtime, project, worker
  and allocation identity
- **THEN** finalization removes only those declared ephemeral resources before
  releasing capacity and retains all declared evidence and policy-retained
  volumes

#### Scenario: Finalizer stops between stages

- **WHEN** a process or host interruption occurs after a journaled mutation
- **THEN** repetition accepts only the exact expected stage successor and
  completes without recreating released ownership or repeating deletion

#### Scenario: Candidate is unlabelled, partial, foreign or ambiguous

- **WHEN** any container, network, image, listener, broker, browser process,
  registry entry, admission record or allocation lacks the complete expected
  identity
- **THEN** the whole operation rejects before its next mutation and leaves that
  candidate plus all unrelated resources intact

#### Scenario: Allocation is retired

- **WHEN** registration is absent, admission is released and owned ephemeral
  resources are zero
- **THEN** `runtime-allocation-v1.json` moves to the canonical retired name
  with identical bytes, inode, owner, group, mode, size and mtime; atime/ctime
  are observed but not rewritten

#### Scenario: Finalization is repeated after completion

- **WHEN** the same request reaches a matching `RELEASED` journal and receipt
- **THEN** no container, network, image, listener, process, registration,
  admission or allocation is changed again

### Requirement: Readiness diagnosis never acquires resources

The canonical-source readiness operation SHALL be read-only apart from the
fixed mirror refresh and SHALL never call workspace activation or change
registration, admission, allocation, runtime, preview or retained ownership.

#### Scenario: Pinned source is behind current main

- **WHEN** the requested commit is an exact ancestor of the refreshed fixed
  canonical ref
- **THEN** the worker returns `SOURCE_ADVANCED` with no resource mutation and
  no resource locator in the response

#### Scenario: Installer observes an empty disabled registry behind main

- **WHEN** the exact root-owned canonical Atenea registry has zero workspaces,
  selection and execution are both disabled, and its commit is an exact
  ancestor of the fixed canonical mirror ref
- **THEN** the reviewed installer MAY advance only the registry commit while
  preserving the empty disabled state and all retained WorkSession data
- **AND** it SHALL reject before stopping the worker when either gate is
  enabled, a workspace exists, modern fixed authority is incomplete, or the
  retained commit is unrelated, missing, foreign or ambiguous

#### Scenario: Routing installer observes the exact release-preflight predecessor

- **WHEN** the installed release mediator and four-rule sudoers boundary match
  the complete checksum-pinned AX42 predecessor
- **THEN** apply retains those exact root-owned mediator bytes beneath the
  fixed private predecessor root before installing the five-rule successor
- **AND** rollback restores the exact four-rule authority and retained mediator
  bytes without reconstructing released ownership
- **AND** a partial, changed, symlinked, missing or foreign predecessor rejects
  before the installed routing bundle is mutated
