## Why

AX42 currently relies on mirrored local storage and Git, but has no independent
external backup or tested restore for authoritative configuration, metadata and
non-recreatable artifacts. This blocks authoritative Beautips routing and must
be closed before real retained project state is created.

## What Changes

- Select an independent object-storage target for encrypted AX42 backups and
  record cost, ownership, recovery and retention decisions.
- Add versioned, least-privilege backup, verification, retention and isolated
  restore automation with finite timeouts and sanitized evidence.
- Back up only declared authoritative configuration, metadata, audit logs and
  non-recreatable artifacts; exclude Git-recreatable repositories, worktrees,
  caches, runtime images and ephemeral resources.
- Prove restoration to an empty isolated path without reading secret values,
  changing live state or enabling project routing.
- Keep authoritative real-project attachments and new Beautips selection
  disabled until the external repository and restore evidence pass.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `worker-operational-safety`: Make external-backup independence, encrypted
  scope, bounded retention, credential isolation, verification and empty-target
  restore acceptance explicit.

## Impact

- Programme repository: worker backup scripts, systemd units, tests, runbook,
  decision ledger and sanitized evidence references.
- AX42: a reviewed backup client, root-owned configuration boundary, scheduled
  backup/verification and an isolated restore-test path.
- External systems: one private, least-privilege Backblaze B2 bucket and
  application key supplied out of band by the operator.
- Atenea and Beautips: no production deployment, database, endpoint, runtime,
  routing or administrative slot 1 mutation during this change.
