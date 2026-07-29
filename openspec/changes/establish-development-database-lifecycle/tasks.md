## 1. Entry evidence and decision closure

- [x] 1.1 Capture canonical Git, production, AX42, slots, volumes, storage, RAID, firewall and Phase 6 rollback fingerprints
- [x] 1.2 Classify Atenea production/preview, Beautips and retained Phase 3 databases as foreign or out of scope
- [x] 1.3 Approve deterministic PostgreSQL/MariaDB fixtures with no production-derived rows
- [x] 1.4 Close synthetic snapshot retention, named-secret, confirmation, evidence and rollback decisions
- [x] 1.5 Create the Phase 7 change, update the programme ledger and pass strict OpenSpec validation

## 2. Manifest and ownership contract

- [x] 2.1 Add a versioned optional database manifest schema with pinned engines, fixed migration/seed inputs and named secrets
- [x] 2.2 Add immutable database, WorkSession, project, worker, allocation, slot, volume, manifest and lifecycle records
- [x] 2.3 Add monotonic idempotent transitions and one-use five-minute replacement challenges
- [x] 2.4 Add private SHA-256 snapshot metadata with three-copy/seven-day synthetic retention
- [x] 2.5 Add schema, ownership, revision, challenge, retention and production-denial unit tests

## 3. Mediated AX42 database lifecycle

- [x] 3.1 Implement fixed create, migrate, seed, health, status, snapshot, replace, restore, stop and cleanup operations
- [x] 3.2 Derive rootless container, network, volume, endpoint and snapshot identities only from persisted ownership
- [x] 3.3 Implement verified pre-replacement snapshots and engine-specific atomic restore
- [x] 3.4 Reject caller endpoints, literal credentials, production-like targets and foreign/partial/ambiguous resources before mutation
- [x] 3.5 Add install, verify, default-disabled rollback and reconciliation tools without opening a host firewall port
- [x] 3.6 Add protocol/CLI, idempotence, ownership, confirmation, restart, retention and exact-cleanup tests

## 4. PostgreSQL synthetic lifecycle

- [x] 4.1 Allocate one free-slot synthetic PostgreSQL WorkSession and create its exact labelled volume/runtime
- [x] 4.2 Apply deterministic migrations and seed data, then prove private health and session-only access
- [x] 4.3 Snapshot, prepare and explicitly confirm one replacement with complete audit evidence
- [x] 4.4 Restore the pre-replacement snapshot and prove schema/data identity without changing Git
- [x] 4.5 Repeat the complete create/migrate/seed/replace/restore lifecycle idempotently

## 5. MariaDB synthetic lifecycle

- [x] 5.1 Allocate a separate free-slot synthetic MariaDB WorkSession and create its exact labelled volume/runtime
- [x] 5.2 Apply deterministic legacy migrations and seed data, then prove private health and session-only access
- [x] 5.3 Snapshot, prepare and explicitly confirm one replacement with complete audit evidence
- [x] 5.4 Restore the pre-replacement snapshot and prove schema/data identity without changing Git
- [x] 5.5 Repeat the complete create/migrate/seed/replace/restore lifecycle idempotently

## 6. Isolation, denial and continuity acceptance

- [x] 6.1 Prove the two sessions cannot inspect, connect to, snapshot, replace, restore or clean each other's database
- [x] 6.2 Prove unconfirmed, expired, replayed and stale-revision replacement attempts mutate nothing
- [x] 6.3 Prove production, preview, real-project, unlabelled, partial, foreign and ambiguous targets remain unchanged
- [x] 6.4 Restart the database mediator/rootless daemons and reconcile only persisted exact ownership without implicit creation
- [x] 6.5 Prove bounded retention removes only expired/excess exact synthetic snapshots and preserves sanitized evidence
- [ ] 6.6 Run complete worker, manifest, PostgreSQL and MariaDB regression suites twice

## 7. Rollback, evidence and archive

- [ ] 7.1 Disable new database operations and stop exact synthetic containers while preserving volumes, snapshots and records
- [ ] 7.2 Repeat rollback and prove idempotence plus rejection of unlabelled, partial, foreign and ambiguous database resources
- [ ] 7.3 Clean only recorded exact synthetic resources and compare production, AX42, slots, RAID, firewall and Beautips fingerprints
- [ ] 7.4 Retain sanitized commands, exit codes, finite timeouts, durations and `SHA256SUMS` without raw dumps or credentials
- [ ] 7.5 Run strict OpenSpec validation, archive the completed change, commit and push, then enter individual project onboarding
