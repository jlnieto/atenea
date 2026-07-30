## MODIFIED Requirements

### Requirement: External backup and restore evidence

Critical non-Git configuration MUST be backed up with already-sealed secret
material, authoritative ownership metadata and non-recreatable artifacts client-side
encrypted to a private target whose provider and administrative recovery
boundary are independent from the worker. Retention MUST keep 14 daily, 8
weekly and 12 monthly exact-host snapshots and MUST delete nothing unless a
newer snapshot and repository integrity check have passed.

The source set MUST be an explicit canonical-root allowlist. Plaintext
credentials, tokens, cookies, environment dumps, Codex authentication/history,
production data, manual project secrets, Git-recreatable repositories and
worktrees, caches, dependencies, runtime images and ephemeral resources MUST
be excluded. Repository credentials MUST remain outside Git, backup content,
commands, logs and evidence.

Initial activation and periodic acceptance MUST restore an exact snapshot to a
new empty isolated target, prove normalized manifest and SHA-256 identity, and
retain sanitized integrity evidence. Backup installation or snapshot creation
alone MUST NOT enable real-project retained state or routing.

#### Scenario: Worker is lost completely

- **WHEN** a replacement host is provisioned
- **THEN** documented automation plus canonical Git and the independent
  encrypted repository restore the declared authoritative worker contract
  without relying on either failed RAID member

#### Scenario: Source policy contains a prohibited or ambiguous path

- **WHEN** backup input resolves outside an approved canonical root or names a
  symlink, credential boundary, Codex state, production-like data or
  regenerable runtime state
- **THEN** backup fails before repository mutation and preserves the source,
  existing snapshots and every routing gate

#### Scenario: External target or integrity check is unavailable

- **WHEN** authentication, upload, verification or repository integrity does
  not complete within its finite bound
- **THEN** the run fails actionably, retention does not execute and
  authoritative real-project state remains disabled

#### Scenario: Exact snapshot is restored for acceptance

- **WHEN** the latest exact-host and policy-tagged snapshot is restored to a
  newly created empty isolated path
- **THEN** normalized path, size and SHA-256 identities match the accepted
  source manifest without overlaying or changing live worker state

#### Scenario: Retention ownership is incomplete or ambiguous

- **WHEN** retention cannot prove the exact repository, worker identity, policy
  tag, newer successful snapshot and passing integrity check
- **THEN** no snapshot or repository object is deleted and the ambiguity is
  reported in sanitized evidence

#### Scenario: Backup acceptance passes

- **WHEN** provider policy, encrypted snapshot, repository check, empty-target
  restore, repeated execution, retention selection and rollback evidence all
  pass
- **THEN** the programme may lift only the external-backup prerequisite in a
  separate project-routing change
