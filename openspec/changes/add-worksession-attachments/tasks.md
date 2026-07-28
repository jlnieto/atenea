## 1. Entry evidence and decision closure

- [x] 1.1 Record canonical Git, production, AX42, routing, storage and non-impact fingerprints before Phase 5 mutation
- [x] 1.2 Close metadata authority, worker storage, access-control, retention, backup-activation and rollback decisions
- [x] 1.3 Approve safe file, session-quota, type, filename, ordering and API defaults
- [x] 1.4 Update the programme ledger, create the active change and pass strict OpenSpec validation

## 2. Persistent attachment model

- [x] 2.1 Add an additive Flyway migration for immutable WorkSession attachment metadata and indexes
- [x] 2.2 Add persistence models and same-session AgentRun ownership validation
- [x] 2.3 Add deterministic screenshot latest, previous and bounded last-N queries
- [x] 2.4 Add transactional quota, idempotency and integrity reconciliation operations

## 3. Private AX42 attachment storage

- [x] 3.1 Implement the versioned authenticated attachment service with opaque storage identities and no filesystem browsing
- [x] 3.2 Implement streaming validation, SHA-256 identity, atomic create and conflicting-idempotency rejection
- [x] 3.3 Enforce file/type limits, exact ownership and synthetic-fixture-only deletion
- [x] 3.4 Add protocol, authentication, malformed input, MIME, quota, restart and traversal tests
- [ ] 3.5 Install the private service and retained root on AX42 without starting a project runtime

## 4. Atenea API and clients

- [ ] 4.1 Add default-off configuration and a finite-timeout authenticated worker client
- [ ] 4.2 Add authenticated WorkSession upload, ordered list, exact metadata/content and screenshot-resolution APIs
- [ ] 4.3 Prevent cross-session/project/run access and avoid exposing worker paths or sensitive errors
- [ ] 4.4 Replace the global mobile upload path when the scoped capability is enabled while preserving disabled-mode compatibility

## 5. Operator web/mobile experience

- [ ] 5.1 Add a WorkSession-scoped upload and retained-attachment panel with one clear current state and action
- [ ] 5.2 Expose actionable unauthorized, oversized, unsupported, quota and worker-unavailable states
- [ ] 5.3 Add API/UI tests for upload, ordered retrieval and exact download
- [ ] 5.4 Verify the rendered flow at desktop and mobile viewports with DOM assertions and inspected screenshots

## 6. Automated validation and continuity

- [ ] 6.1 Run migration, persistence, service, controller, worker-protocol and complete backend regression suites
- [ ] 6.2 Prove prompt plus image content reaches only the intended synthetic WorkSession and AgentRun
- [ ] 6.3 Prove latest, previous and last-N ordering cannot cross sessions, projects or sources
- [ ] 6.4 Prove retained content survives client disconnect, attachment-service restart and preview teardown
- [ ] 6.5 Prove unauthorized, oversized, unsupported, empty, traversal and conflicting inputs fail closed without residual metadata or content

## 7. Rollback, evidence and archive

- [ ] 7.1 Disable new attachment creation while retained indexed evidence remains retrievable
- [ ] 7.2 Repeat rollback and prove idempotence plus exact cleanup of only recorded synthetic fixtures
- [ ] 7.3 Compare final Git, production, routing, AX42, storage, RAID, firewall, slots and Beautips fingerprints with the baseline
- [ ] 7.4 Retain sanitized commands, exit codes, timeouts, durations, UI evidence and `SHA256SUMS`
- [ ] 7.5 Run strict OpenSpec validation, archive the completed change, commit and push both repositories, then enter Phase 6
