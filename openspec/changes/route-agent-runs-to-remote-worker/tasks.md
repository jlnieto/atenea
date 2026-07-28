## 1. Entry evidence and decision closure

- [x] 1.1 Record canonical Git, production, AX42, routing, capacity and protocol fingerprints before any Phase 4 mutation
- [x] 1.2 Create a current production-schema backup, restore it in a network-disabled disposable PostgreSQL fixture and retain sanitized checksummed evidence
- [x] 1.3 Close Phase 4 decisions for target affinity, leases, protocol authentication, migration rollback, synthetic scope and later-phase deferrals
- [x] 1.4 Update the programme ledger to make this change active and run strict OpenSpec validation

## 2. Persistent routing and lifecycle model

- [x] 2.1 Add an additive Flyway migration for worker registration, WorkSession execution affinity and AgentRun dispatch, workload, lease and lifecycle state
- [x] 2.2 Expand persistence and API models with safe local defaults and one non-terminal AgentRun per session
- [x] 2.3 Add repository operations for compatible-worker selection, monotonic lifecycle updates, lease renewal and exact terminal acceptance
- [x] 2.4 Prove V45 backup restore plus V46 migration and document expand/contract rollback without destructive down migration

## 3. Private worker protocol and scheduler

- [x] 3.1 Implement the versioned authenticated synthetic worker service with durable atomic state and no arbitrary command/runtime fields
- [x] 3.2 Implement idempotent dispatch, conflicting-identity rejection, status/progress, lease renewal, cancellation and monotonic terminal state
- [x] 3.3 Enforce four normal slots, two heavy permits and durable FIFO recovery after worker-service restart
- [x] 3.4 Add protocol, authentication, malformed-input, duplicate, capacity, restart and cancellation tests
- [x] 3.5 Install the private worker service on AX42 with root-owned configuration, narrow Tailscale/UFW access and no public listener

## 4. Atenea dispatch, affinity and reconciliation

- [x] 4.1 Add default-disabled configuration and select remote execution only for a newly opened WorkSession in the exact synthetic allowlist
- [x] 4.2 Add authenticated health/capacity and execution clients with finite timeouts, sanitized errors and stable idempotency headers
- [x] 4.3 Dispatch remote AgentRuns only after target, workspace, dispatch and lease state are durable
- [x] 4.4 Poll and persist monotonic progress and exactly-once terminal response delivery
- [x] 4.5 Implement exact remote cancellation while preserving unrelated sessions
- [x] 4.6 Reconcile persisted remote targets on startup and partition without duplicate dispatch, automatic reassignment or local fallback
- [x] 4.7 Preserve the existing local executor and local startup/stale-run reconciliation semantics

## 5. Automated validation and regression

- [x] 5.1 Run migration, repository, service and API tests for local defaults, remote lifecycle, non-terminal uniqueness and terminal deduplication
- [x] 5.2 Prove feature-switch changes affect only new sessions and remote selection fails safely when the worker is unavailable or incompatible
- [x] 5.3 Prove duplicate dispatch produces one execution and a conflicting payload changes nothing
- [x] 5.4 Prove four normal and two heavy limits, visible queuing and permit recovery
- [x] 5.5 Run the complete Atenea backend suite and canonical build without changing production configuration

## 6. Synthetic acceptance and continuity

- [ ] 6.1 Create a disposable synthetic control-plane environment and confirm production routing remains zero
- [ ] 6.2 Complete one remote turn, then keep another live across an Atenea backend restart and accept its terminal response exactly once
- [ ] 6.3 Introduce and heal a bounded control-plane-to-worker partition, observing reconciling state without duplicate work or terminal delivery
- [ ] 6.4 Cancel one exact execution and prove unrelated queued/running sessions, worker slots and Beautips remain unchanged
- [ ] 6.5 Complete multiple turns in one synthetic WorkSession with the same persisted worker and workspace identity

## 7. Rollback, observation and handoff

- [ ] 7.1 Disable remote selection for new sessions, explicitly reconcile or cancel every synthetic non-terminal remote run and leave existing session affinity unchanged
- [ ] 7.2 Execute rollback twice and remove only exact Phase 4 fixtures while retaining schema, logs, artifacts and lifecycle evidence
- [ ] 7.3 Compare final production, Git, database, routing, AX42, RAID, firewall, slots and Beautips fingerprints with the accepted baseline
- [ ] 7.4 Retain sanitized commands, exit codes, timeouts, durations and `SHA256SUMS`; record the observation result and operator workflow
- [ ] 7.5 Run strict OpenSpec validation, confirm every Phase 4 task complete, archive the change, commit and push both repositories, then stop before Phase 5
