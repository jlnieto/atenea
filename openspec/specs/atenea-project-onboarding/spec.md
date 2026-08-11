# atenea-project-onboarding Specification

## Purpose
TBD - created by archiving change onboard-atenea-on-ax42. Update Purpose after archive.
## Requirements
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

An admitted Atenea AgentRun SHALL automatically and idempotently provision the
exact persisted session mirror, worktree, admission, allocation and project
registration before its first dispatch. It SHALL then execute the operator
prompt through one bounded Codex process in that worktree, retain its real
thread and turn identities, and return one idempotent terminal response. The
request MUST NOT grant arbitrary command, path, remote, endpoint, environment
or daemon authority.

#### Scenario: First production-control-plane turn

- **WHEN** an operator submits the first turn to a newly opened exact Atenea WorkSession
- **THEN** AX42 ensures the persisted workspace ownership and dispatches one AgentRun without an SSH preparation step

#### Scenario: Two turns continue one session

- **WHEN** an operator submits two sequential turns to the admitted WorkSession
- **THEN** both use the same worker, workspace and Codex thread while each turn owns one distinct idempotent AgentRun

#### Scenario: Partial or foreign provision exists

- **WHEN** any mirror, worktree, admission, allocation or registry identity conflicts
- **THEN** provisioning fails closed without replacing, deleting or adopting that state

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

### Requirement: Project-scoped real Atenea screenshot activation

Atenea SHALL permit authoritative operator screenshot creation only when the
global attachment gate and canonical `atenea` gate are enabled, the WorkSession
was created with policy revision `atenea-real-attachments-v1`, exact AX42
remote workspace ownership is complete, the compatible storage/runner
capabilities are healthy and the independent backup prerequisite is accepted.
No other project, existing session, production data path or deployment
authority SHALL become eligible.

#### Scenario: First real screenshot turn succeeds

- **WHEN** the operator uploads one generated non-secret screenshot in a new
  clean Atenea WorkSession and submits it with an exact message
- **THEN** one image-bearing AgentRun delivers that image to Codex, returns one
  response demonstrating image understanding and retains one immutable turn
  binding

#### Scenario: Same-thread continuation follows the screenshot turn

- **WHEN** the first image-bearing run is terminal and the operator submits a
  later text-only turn
- **THEN** Atenea reuses the same WorkSession, workspace and Codex thread
  without implicitly attaching an older image

#### Scenario: Beautips attempts attachment creation

- **WHEN** a Beautips or unrelated WorkSession reaches the attachment API during
  Atenea-only activation
- **THEN** it receives a project-disabled state and no metadata, content,
  binding, AgentRun or gate changes

#### Scenario: Atenea attachment activation is rolled back

- **WHEN** the exact project then global gates are disabled and rollback is
  repeated
- **THEN** new create/bind remains unavailable while historical content,
  bindings, Git, routing, production, preview, Beautips and unrelated worker
  resources remain unchanged

### Requirement: Canonical Atenea main promotion

Atenea SHALL use the reviewed GitHub `main` history as the single canonical
source and default base for newly created Atenea WorkSessions. Promotion SHALL
preserve the accepted feature and attachment commit ancestry without squash,
force update or branch deletion, and SHALL reconcile the canonical checkout,
mirror, project policy and worker registry to one exact resulting commit.

#### Scenario: Ordered accepted histories are promoted

- **WHEN** the feature tip is an ancestor of the attachment candidate and both
  pass their required validation
- **THEN** separate ordered pull requests merge them into `main` with both tips
  retained as ancestors of the resulting commit

#### Scenario: A new Atenea WorkSession is opened

- **WHEN** canonical project and worker declarations have reconciled to the
  accepted main commit
- **THEN** the WorkSession persists `main` as its base and derives its exact
  remote workspace from that commit without starting a runtime or AgentRun

#### Scenario: Compiled source authority is reconciled

- **WHEN** the accepted backend, runtime manifest, worker programs, mediators
  and request schemas still name the retained feature branch
- **THEN** one reviewed identity-only successor changes all of them to `main`,
  assigns the resulting immutable manifest hash and is completely validated
  before any production declaration moves

#### Scenario: Installed activation mediator is stale

- **WHEN** the reviewed release is `main` but the installed activator is the
  one exact accepted feature predecessor
- **THEN** the dedicated installer may replace only that exact bundle, the
  AgentRun installer verifies the resulting dependency, and a sealed rollback
  retains the predecessor bytes without starting a runtime or AgentRun

#### Scenario: Installed activation bundle is not exact

- **WHEN** its program, sudoers boundary or dependencies are partial,
  symlinked, foreign, ambiguous or changed after preflight
- **THEN** installation fails closed before writing, restarting a service or
  adopting any resource

#### Scenario: Closed prior canary retains active worker ownership

- **WHEN** the exact sole worker registration and held admission belong to a
  control-plane WorkSession proven closed with zero non-terminal runs and zero
  runtime resources
- **THEN** only its registration and active admission are mediatedly released,
  while its allocation sidecar, worktree, Git, logs, attachments and artifacts
  remain unchanged

#### Scenario: Released closed canary still occupies the active allocation name

- **WHEN** WorkSession 15 is exactly closed, its admission is released, its
  registration and owned runtime resources are absent, and its active
  allocation matches the previously sealed immutable identity
- **THEN** only that file is renamed on the same filesystem to the canonical
  retired allocation name with identical bytes, inode, ownership, mode, size
  and mtime before WorkSession 16 activation is repeated; filesystem-managed
  atime and ctime are recorded but may advance through required reads and the
  intrinsic rename

#### Scenario: Closed allocation retirement is not exact

- **WHEN** the source hash or metadata differs, the retired destination exists,
  ownership is partial or ambiguous, admission is not released, or any owned
  resource remains
- **THEN** retirement fails closed without moving, rewriting, adopting or
  deleting either the allocation or any foreign resource

#### Scenario: Promotion identity is inconsistent

- **WHEN** a ref, ancestry, project row, mirror, registry, workspace or service
  identity is partial, foreign, ambiguous or no longer matches its fingerprint
- **THEN** promotion fails closed without rewriting Git history or adopting,
  repairing, deleting or rebuilding the inconsistent resource

#### Scenario: Historical WorkSessions are inspected after promotion

- **WHEN** retained closed or blocked sessions refer to their original feature
  base and immutable delivery state
- **THEN** their history, Git refs, logs and artifacts remain unchanged while
  only new sessions use `main`

#### Scenario: Unrelated systems are observed during promotion

- **WHEN** routing, production, preview, Beautips, backups or another worker
  resource is fingerprinted before and after
- **THEN** it remains healthy and unchanged apart from ordinary time-varying
  health metadata

### Requirement: Consecutive canonical Atenea WorkSession lifecycle

Canonical Atenea SHALL support consecutive `main`-based WorkSessions without
manual AX42 repair. A normal remote close SHALL release exact active worker
ownership before the control plane records `CLOSED`, and the next WorkSession
SHALL acquire only the released canonical slot through the reviewed mediator.
Activation of this successor SHALL remain restricted to Atenea until separate
project acceptance exists.

#### Scenario: Canonical Atenea session closes normally

- **WHEN** its runs are terminal, delivery and Git are reconciled and exact
  remote finalization succeeds
- **THEN** the session becomes `CLOSED/RELEASED` while its source, conversation,
  attachments, logs and artifacts remain reviewable

#### Scenario: Next Atenea session starts

- **WHEN** a clean newly opened session uses accepted `main` after the prior
  release receipt is durable
- **THEN** it obtains the canonical Atenea workspace, allocation, admission and
  registration without a host-side repair or foreign resource change

#### Scenario: Next Atenea session replaces stale pre-dispatch source

- **WHEN** the open session's failed pre-dispatch run is pinned behind current
  canonical `main` and the operator explicitly starts fresh
- **THEN** Atenea SHALL close that session normally and open exactly one clean
  successor whose source is admitted only when the operator later sends a new
  turn
- **AND** it SHALL NOT copy or resend the stale turn, prompt, AgentRun or
  attachment binding

#### Scenario: Retained legacy Atenea owner is reconciled

- **WHEN** a platform administrator separately confirms the exact closed
  predecessor and its zero-resource ownership proof
- **THEN** the product-mediated release frees only that predecessor's active
  ownership and leaves the blocked successor and its failed prompt unchanged

#### Scenario: Another project is inspected during rollout

- **WHEN** Beautips or any non-Atenea project, slot, route or resource is
  fingerprinted before and after
- **THEN** it remains ineligible for the new gate and unchanged apart from
  ordinary time-varying health metadata
