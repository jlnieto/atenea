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

#### Scenario: Closed prior canary retains active worker ownership

- **WHEN** the exact sole worker registration and held admission belong to a
  control-plane WorkSession proven closed with zero non-terminal runs and zero
  runtime resources
- **THEN** only its registration and active admission are mediatedly released,
  while its allocation sidecar, worktree, Git, logs, attachments and artifacts
  remain unchanged

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
