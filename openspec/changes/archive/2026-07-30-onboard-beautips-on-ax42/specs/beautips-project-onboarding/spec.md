## ADDED Requirements

### Requirement: Canonical Beautips onboarding identity

Beautips SHALL become schedulable on AX42 only from GitHub
`jlnieto/beautips`, branch `main`, an explicitly accepted commit and reviewed
manifest hash. Laptop, control-plane and manual-worker copies MUST NOT become
implicit source authority.

#### Scenario: Exact canonical identity is admitted

- **WHEN** a new Beautips WorkSession names the approved project identity
- **THEN** its mirror, worktree, manifest, allocation and execution derive from
  the persisted canonical commit

#### Scenario: Source identity differs

- **WHEN** repository, branch, ancestry, commit or manifest differs
- **THEN** onboarding remains disabled and creates no workspace or run

### Requirement: Administrative Beautips remains foreign

The worker MUST treat the existing manual slot 1 Beautips workspace,
containers, network, listener, secret boundary and persistent PostgreSQL,
Redis, assets/imports volumes as foreign resources outside managed
WorkSession ownership.

#### Scenario: Managed acceptance is allocated

- **WHEN** a disposable Beautips WorkSession requests capacity
- **THEN** it uses another admitted slot and distinct allocation-derived
  identities without attaching to any administrative resource

#### Scenario: Administrative-like cleanup candidate appears

- **WHEN** cleanup observes a slot 1 or manual Beautips identity
- **THEN** it rejects the candidate unchanged as foreign retained state

### Requirement: Disposable Beautips data and messaging boundary

Acceptance MUST use empty migrated PostgreSQL, disposable Redis and
deterministic synthetic fixtures and files. It MUST NOT copy a current database,
legacy dump, backup, assets/imports volume, credential or production-derived
row. All WhatsApp and external message delivery paths SHALL remain disabled.

#### Scenario: Synthetic runtime starts

- **WHEN** the exact WorkSession starts its managed runtime
- **THEN** only its separate labelled database, Redis, assets/imports and
  invented fixture state become reachable

#### Scenario: Existing or external state is requested

- **WHEN** a request resolves to manual, production, legacy, backup or WhatsApp
  authority
- **THEN** it is rejected before connection, copy, mount or transmission

### Requirement: Real Beautips Codex continuity

An admitted Beautips AgentRun SHALL execute a bounded Codex process in the
exact session worktree, retain one real thread across turns and deliver each
terminal result idempotently without caller-supplied command, path, endpoint,
remote, environment or daemon authority.

#### Scenario: Two turns continue one WorkSession

- **WHEN** the operator submits sequential accepted turns
- **THEN** both use the same worker, workspace and thread with distinct
  idempotent dispatch identities

#### Scenario: Duplicate dispatch is retried

- **WHEN** Atenea repeats an identical dispatch or terminal observation
- **THEN** no second Codex turn or conversation response is created

### Requirement: Beautips verification

Acceptance SHALL prove the canonical build and tests, synthetic data, runtime
health, tailnet-only preview, functional smoke checks, DOM assertions,
inspected desktop/mobile screenshots and sanitized artifacts from the exact
WorkSession commit.

#### Scenario: Complete verification passes

- **WHEN** the intended real prompt change exists
- **THEN** every build, test, data, health, preview, browser and artifact check
  passes before Beautips is declared schedulable

#### Scenario: One verification layer fails

- **WHEN** any required layer fails or requires localhost unexpectedly
- **THEN** Beautips remains disabled with an actionable failed layer

### Requirement: Normal Beautips delivery and close

The accepted WorkSession SHALL publish only its exact branch and commit through
one draft pull request, synchronize a reviewed non-force merge and close only
after canonical Git reconciliation.

#### Scenario: Delivery succeeds

- **WHEN** the deterministic change and delivery prerequisites pass
- **THEN** the exact draft, merge state and close retain Git and evidence
  invariants

#### Scenario: Delivery identity conflicts

- **WHEN** repository, base, head or commit differs
- **THEN** publish and close fail closed without rewriting history

### Requirement: Exact Beautips rollback and retention

Rollback SHALL disable only new Beautips selection and remove only resources
whose complete session ownership matches. It SHALL preserve Git and sanitized
evidence and leave the administrative pilot, production and other projects
unchanged. Non-Git acceptance state remains non-authoritative until an
independent external backup passes restore.

#### Scenario: Rollback is repeated

- **WHEN** exact rollback runs after its projection is absent
- **THEN** it removes nothing further and leaves unlabelled, partial, foreign
  and ambiguous resources untouched

#### Scenario: Close observation passes

- **WHEN** four normalized samples span the declared 15-minute window
- **THEN** disabled ownership, retained evidence, administrative Beautips,
  production and unrelated resources remain unchanged and healthy
