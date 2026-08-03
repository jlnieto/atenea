## ADDED Requirements

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
