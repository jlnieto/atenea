## ADDED Requirements

### Requirement: Non-empty real attachment backup activation

Real Atenea attachment activation SHALL consume the accepted independent
encrypted backup contract only after a non-secret real-project canary exists
under the declared attachment root and a newer exact-policy snapshot,
repository check and empty-target isolated restore prove its sidecar/content
size and SHA-256 identity. Failure MUST disable new upload/bind before any
rollback and MUST NOT run retention, delete the canary or alter existing
snapshots.

#### Scenario: Real attachment canary is protected

- **WHEN** one exact Atenea canary attachment has been accepted and the bounded
  backup/check/restore sequence passes
- **THEN** the external-backup prerequisite is satisfied for the exact project
  gate without retaining its bytes, filename, prompt or answer in evidence

#### Scenario: Backup or restore does not verify

- **WHEN** snapshot, check, restore or normalized identity comparison fails or
  times out
- **THEN** project then global create/bind are disabled, no prune/delete runs
  and existing content plus diagnostic evidence remain intact

#### Scenario: Backup source policy omits attachment content

- **WHEN** the installed policy no longer selects the canonical attachment root
  or selects an undeclared credential/runtime boundary
- **THEN** real activation is blocked before the project gate changes

