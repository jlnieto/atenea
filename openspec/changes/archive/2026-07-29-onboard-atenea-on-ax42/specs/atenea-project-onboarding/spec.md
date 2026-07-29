## ADDED Requirements

### Requirement: Canonical Atenea onboarding identity

Atenea SHALL become schedulable as a real AX42 project only from an explicitly
approved GitHub repository, branch, commit and manifest hash. Laptop copies,
control-plane worktrees and unpublished state MUST NOT become implicit source
authority.

#### Scenario: Exact canonical identity is admitted

- **WHEN** a new Atenea WorkSession names the approved project and commit
- **THEN** the worker derives its mirror, worktree, allocation and manifest
  from persisted canonical ownership

#### Scenario: Source identity differs

- **WHEN** repository, branch, ancestry, commit or manifest differs from the
  approved identity
- **THEN** onboarding remains disabled and no workspace or run is created

### Requirement: Real Atenea Codex WorkSession

An admitted Atenea AgentRun SHALL execute the operator prompt through one
bounded Codex process in the exact session worktree, retain its real thread and
turn identities, and return one idempotent terminal response. The request MUST
NOT grant arbitrary command, path, remote, endpoint, environment or daemon
authority.

#### Scenario: Two turns continue one session

- **WHEN** an operator submits two sequential turns to the admitted WorkSession
- **THEN** both use the same worker, workspace and Codex thread while each turn
  owns one distinct idempotent AgentRun

#### Scenario: Duplicate dispatch is delivered

- **WHEN** Atenea repeats an identical dispatch or terminal observation
- **THEN** the worker and control plane return the existing execution/result
  without rerunning the prompt or duplicating conversation output

### Requirement: Atenea onboarding verification

Acceptance SHALL prove the canonical tests/build, empty migrated development
data, runtime health, tailnet-only preview, DOM state, inspected desktop/mobile
screenshots and retained sanitized artifacts from the exact WorkSession commit.

#### Scenario: Complete verification passes

- **WHEN** the real prompt has produced its intended Git change
- **THEN** build, tests, data, health, preview, browser and artifact checks all
  pass before the project is declared schedulable

#### Scenario: One layer fails

- **WHEN** any source, test, build, data, runtime, preview, DOM, visual or
  artifact assertion fails
- **THEN** Atenea remains disabled with an actionable failed layer

### Requirement: Normal Atenea Git delivery

The accepted WorkSession SHALL publish only its exact branch and commit through
the existing pull-request workflow, synchronize reviewed merge state without a
force update, and close only after canonical Git reconciliation succeeds.

#### Scenario: WorkSession is published and closed

- **WHEN** its deterministic change is committed and delivery prerequisites pass
- **THEN** Atenea creates or reuses one correct draft delivery, synchronizes
  exact head/base state and closes while retaining Git and evidence invariants

#### Scenario: Delivery identity conflicts

- **WHEN** the remote branch or pull request points to a different repository,
  base, head or commit
- **THEN** publish/close fails closed without rewriting either history

### Requirement: Project-scoped Atenea rollback

Rollback SHALL disable only new Atenea real-project selection, reconcile or
cancel only its exact active dispatch, and clean only resources with complete
persisted session ownership. Git, delivery and sanitized evidence SHALL remain,
while production and unrelated projects remain unchanged.

#### Scenario: Rollback is repeated

- **WHEN** the exact Atenea rollback runs after its runtime is already absent
- **THEN** it removes nothing further and preserves foreign, unlabelled,
  partial and ambiguous resources

#### Scenario: Delivered descendant is stopped

- **WHEN** normal delivery has advanced the clean WorkSession beyond the
  admitted runtime source commit
- **THEN** rollback validates the exact admitted commit/tree as its ancestor,
  retains the exact manifest and Compose hashes, and stops only the persisted
  runtime ownership without requiring the live worktree HEAD to move backwards

#### Scenario: Another project is inspected

- **WHEN** rollback observes Beautips or a non-onboarded project
- **THEN** it leaves that project unchanged and does not alter its eligibility

### Requirement: Production and backup boundary

Atenea onboarding MUST use empty migrated development data and synthetic
fixtures. Production database/network/credential/deployment authority MUST
remain unreachable, and non-Git project artifacts MUST NOT become
authoritative until an independent external backup has passed restore.

#### Scenario: Production-like input is offered

- **WHEN** a workload, fixture or manifest resolves to production data,
  credential, endpoint or deployment authority
- **THEN** it is rejected before connection, execution or persistence

#### Scenario: Acceptance artifact is retained

- **WHEN** a screenshot, trace or report is registered during onboarding
- **THEN** it remains explicitly non-authoritative synthetic acceptance
  evidence under the existing bounded retention contract
