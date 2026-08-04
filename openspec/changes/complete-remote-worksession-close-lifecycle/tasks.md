Complete, test, document, mark, strict-validate, commit and publish each task
before beginning the next one. Every command must have a finite timeout and
every retained evidence package must be sanitized and checksum-sealed. Stop on
any Git, ownership, backup, RAID, production, preview, Beautips or foreign
resource divergence. Do not read or retain prompt text, response text,
attachment content, credentials, tokens, cookies, `auth.json`, Codex history or
environment dumps.

## 0. Entry contract and incident preservation

- [x] 0.1 Read every applicable `AGENTS.md`, the complete programme ledger,
  this proposal/design/all five deltas/tasks, and the current rollback, backup,
  continuity, attachment, runtime, ownership and security contracts
- [x] 0.2 Verify clean/upstream-exact programme and canonical Atenea Git; inspect
  `atenea` and `codex-worker`; capture services, RAID, firewall, Tailscale,
  backups, rootless slots, rootful daemons, production, preview and Beautips
  fingerprints without mutation
- [x] 0.3 Seal a content-free incident projection for WorkSessions 16 and 17 and
  AgentRun 96: identities, statuses, source, failure code/timing, workspace,
  allocation, admission, registry, run/lease/resource counts and the 81
  activation attempts; do not inspect its prompt or attachment bytes
- [x] 0.4 Confirm WorkSession 16 is still the exact active owner, WorkSession 17
  remains clean with no allocation/admission/runtime, the worker is healthy and
  no foreign resource changed; strict-validate, commit and publish the entry
  evidence and exact resume point

## 1. Additive lifecycle persistence

- [x] 1.1 Add failing domain and migration tests for the V63 remote-close
  states, immutable operation identity, monotonic revision, receipt/error
  consistency, legacy `UNVERIFIED_LEGACY` backfill and safe AgentRun failure
  code/next-action projection
- [x] 1.2 Implement additive V63 with exact constraints and indexes; do not
  rewrite historical session, run, turn, delivery or attachment data and do
  not infer released worker state for a legacy row
- [x] 1.3 Implement persistence/read-model mapping for remote close and
  action-specific failures, preserving every legacy/local API projection and
  keeping all new capability gates disabled by default
- [x] 1.4 Restore the current PostgreSQL backup into a network-isolated empty
  fixture, apply all migrations through V63, prove repeat startup and prove an
  exact V63-aware rollback image can read the expanded schema with new writers
  disabled
- [x] 1.5 Run focused and complete migration/persistence tests, seal evidence,
  update the ledger, strict-validate, mark section 1, commit and push

## 2. Typed worker failure preservation and admission decisions

- [x] 2.1 Extend the worker error envelope with a closed safe schema and an
  allowlisted mapping from reviewed mediator codes; reject unknown fields,
  unsafe detail, invalid blocker UUIDs and oversized output in focused tests
- [x] 2.2 Extend `RemoteWorkerException`/`RemoteWorkerClient` to parse only that
  schema and retain HTTP status, safe code, category, retryability and next
  action; discard raw bodies and preserve existing success decoding
- [x] 2.3 Split coordinator handling so I/O/timeouts/compatible 5xx reconcile,
  exact open-owner capacity waits in `QUEUED`, deterministic 4xx failures stop
  immediately, and exact closed-owner capacity exposes
  `RECONCILE_REMOTE_CLOSE` without redispatch
- [x] 2.4 Prove malformed responses, ownership mismatches and unknown blockers
  require platform review; prove repeated deterministic activation is not
  polled 81 times and creates no worker execution, lease or replacement run
- [x] 2.5 Prove safe retry remains blocked until the deterministic blocker is
  cleared and that the original turn, run, execution profile and attachment
  manifest remain immutable
- [x] 2.6 Run focused client/coordinator/API tests plus unchanged partition,
  restart, cancellation and retry regressions; seal evidence, update the
  ledger, strict-validate, mark section 2, commit and push

## 3. Exact worker workspace release

- [x] 3.1 Add closed request/response fixtures and tests for
  `/v1/project-workspaces/release`, immutable idempotency, strict response
  ownership, no non-terminal execution and rejection of caller authority
- [x] 3.2 Add one persistent finite lifecycle lock shared by Atenea workspace
  `ensure` and `release`; prove concurrent ensure/release serialization, lock
  timeout and no interleaved registration/admission state
- [x] 3.3 Implement complete no-write preflight over the exact workspace,
  registry, admission, allocation, runtime, preview, listener, proxy/broker,
  image/materialization and browser-process projection
- [x] 3.4 Implement the immutable release journal and monotonic
  `PREPARED`→`EPHEMERAL_RELEASED`→`UNREGISTERED`→`ADMISSION_RELEASED`→
  `ALLOCATION_RETIRED`→`RELEASED` transitions with exact stage validation
- [x] 3.5 Remove only exact-owned ephemeral resources, unregister only the
  selected workspace, release heavy before normal admission and retire only
  its active allocation by same-filesystem rename; retain all declared source,
  conversation, attachment, log, artifact, backup and policy-retained volume
  state
- [ ] 3.6 Prove interruption/restart at every stage resumes the same operation;
  prove a completed repetition removes nothing further and returns the same
  receipt/revision/fingerprint
- [ ] 3.7 Create synthetic unlabelled, partially labelled, foreign-owned,
  wrong-session, wrong-project, symlinked and ambiguous fixtures; record their
  immutable IDs, prove each rejection leaves them intact, then remove only the
  exact fixtures by recorded identity
- [ ] 3.8 Update the dedicated installer, sudoers, dependency fingerprints and
  installed verifier for the exact release authority; add an exact predecessor
  rollback that cannot remove or broaden another operation
- [ ] 3.9 Run every focused release/installer/rollback test and the complete
  sorted worker suite with zero residual fixture containers, networks, images,
  listeners, brokers or Playwright/Chromium processes; seal evidence, update
  the ledger, strict-validate, mark section 3, commit and push

## 4. Durable control-plane close and legacy reconciliation

- [ ] 4.1 Add strict `releaseWorkspace` request/receipt validation to the
  client, deriving every field from persisted WorkSession/project identity and
  accepting no caller path, slot, resource or service value
- [ ] 4.2 Refactor normal close so delivery/Git reconcile first, one immutable
  operation is committed, remote release follows, and `CLOSED/RELEASED` is
  written only after exact receipt validation; local-session behavior remains
  unchanged
- [ ] 4.3 Add startup and operator reconciliation for `CLOSING` remote sessions
  using the same operation; prove crash after request, after worker receipt and
  before final database commit creates no duplicate mutation or false closure
- [ ] 4.4 Add read-only legacy ownership planning and a fixed
  `PLATFORM_ADMINISTRATOR` `RECONCILE_REMOTE_CLOSE` operation with single-use
  finite confirmation; never scan or release legacy rows automatically
- [ ] 4.5 Require closed state, exact worker/project/workspace match, terminal
  runs, unchanged delivery/Git fingerprint and worker diagnosis before legacy
  release; reject stale confirmation, wrong role, open owner, foreign owner and
  ambiguous partial state before mutation
- [ ] 4.6 Persist safe lifecycle audit, error code and next action without raw
  worker payload; keep generic AgentRun retry unavailable until a matching
  released receipt exists and prior execution is terminal or absent
- [ ] 4.7 Run focused and complete backend unit/integration/concurrency/restart
  tests, including unchanged local close, PR close, attachments, notifications
  and recovery lineage; seal evidence, update the ledger, strict-validate, mark
  section 4, commit and push

## 5. State-first web and Android operation

- [ ] 5.1 Analyze the current conversation/close/recovery UI and implement the
  minimal shared read model so current state, blocker and one primary action
  are understandable in under three seconds without exposing infrastructure
  detail
- [ ] 5.2 Implement web states for closing/reconciling, blocked ownership,
  confirmed legacy release and capacity released; hide or disable generic retry
  until it is safe and keep secondary actions visually subordinate
- [ ] 5.3 Validate real rendered web data, DOM and visuals with Playwright at
  `1440x900` and `390x844`, including long safe messages, button authority,
  confirmation, refresh and no clipping/overlap/horizontal overflow; inspect
  and retain final screenshots
- [ ] 5.4 Implement equivalent Android state, confirmation and next-action
  behavior without adding attachment or unrelated administration scope; add
  unit/UI tests and build one signed-channel canary APK without publishing it
- [ ] 5.5 Prove web/Android authorization parity, stale-confirmation handling,
  screen refresh and exact conversation deep-link behavior; seal evidence,
  update the ledger, strict-validate, mark section 5, commit and push

## 6. Complete source validation and rollout gate

- [ ] 6.1 Run the complete Atenea backend suite and all 63 migrations from a
  clean checkout, with zero failures and no synthetic worker/database residue
- [ ] 6.2 Run the production web build, dependency audit and final desktop/mobile
  Playwright acceptance against the exact candidate tree
- [ ] 6.3 Run Android unit/instrumentation/static checks and produce the exact
  canary artifact plus checksum without publishing or installing it
- [ ] 6.4 Run the complete sorted programme/worker, runtime, preview, attachment,
  backup, installer, rollback and security suites from a clean immutable source
- [ ] 6.5 Perform an independent adversarial review of protocol closure,
  privilege boundaries, race/crash recovery, migration compatibility, UI
  actions, rollback and foreign-resource rejection; correct only in-scope
  blockers and repeat affected complete suites
- [ ] 6.6 Publish reviewed Atenea and programme candidate branches, record exact
  commits/trees/manifests/images/install bundles and checksum-sealed rollback
  predecessors; verify production, preview, Beautips and unrelated resources
  remain unchanged
- [ ] 6.7 Strict-validate the change, seal source-validation evidence, update the
  ledger, mark task 6.7, commit and push
- [ ] 6.8 STOP before any production migration, deployment, worker install,
  configuration change, capability activation or legacy reconciliation until
  the operator separately and explicitly authorizes the exact V63 rollout,
  AX42 successor, bounded rollback exercise and Atenea-only activation

## 7. Authorized rollout and retained-owner canary

- [ ] 7.1 Record the separate authorization; recapture complete Git, database,
  service, backup, RAID, firewall, Tailscale, slot, registry, admission,
  allocation, runtime, production, preview and Beautips fingerprints; stop on
  any divergence
- [ ] 7.2 Create and check a fresh encrypted external backup, restore the exact
  database into an empty isolated target, apply V63 and re-prove the
  rollback-compatible image before production migration
- [ ] 7.3 Deploy the exact V63-aware backend/web and reviewed AX42
  worker/finalizer successor with all new gates disabled; restart only declared
  services, use finite readiness, verify installed hashes and retain exact
  rollback predecessors
- [ ] 7.4 Publish and install the exact Android canary through the established
  update channel, then obtain the operator's confirmation that web and Android
  show unchanged behavior while release remains disabled
- [ ] 7.5 Enable the global prerequisite and then only canonical Atenea; prove
  Beautips and all other project gates remain disabled and every unrelated
  route/resource fingerprint is unchanged
- [ ] 7.6 Run an isolated/synthetic exact release plus repeated release and all
  foreign/ambiguous rejection fixtures on AX42; exact-clean only recorded
  fixtures and confirm zero residual processes or resources
- [ ] 7.7 Produce an in-product read-only plan for legacy WorkSession 16 and
  stop for the operator's explicit single-use confirmation; do not simulate the
  confirmation or invoke release through SSH
- [ ] 7.8 After confirmation, reconcile only remote session
  `7151dce0-69ab-4614-86e4-f93f1af825e4`; prove registration/admission release,
  exact allocation retirement, receipt idempotence, zero owned ephemeral
  resources and unchanged retained worktree/Git/turns/runs/attachment/logs/
  artifacts
- [ ] 7.9 Reconcile WorkSession 17 workspace readiness without sending its
  prompt or starting Codex/runtime; prove AgentRun 96 and its turn/attachment
  remain unchanged and the UI now offers, but does not execute, explicit retry
- [ ] 7.10 Prove production, preview, Beautips, backups, RAID, four rootless
  slots, rootful daemons, listeners, leases, routing and unrelated sessions are
  healthy and unchanged; seal evidence, update the ledger, strict-validate,
  mark section 7, commit and push

## 8. Rollback, idempotence and closure

- [ ] 8.1 Disable Atenea project then global release/reconciliation gates,
  prove zero in-progress lifecycle operations, and deploy the exact
  V63-compatible rollback application plus worker predecessors without
  reintroducing released ownership
- [ ] 8.2 Prove expanded records remain readable, WorkSession 16 stays released,
  WorkSession 17 and AgentRun 96 remain immutable, and production, preview,
  Beautips and unrelated worker resources remain healthy
- [ ] 8.3 Restore the reviewed successor, re-enable only canonical Atenea and
  repeat read-only reconciliation/release to prove no additional mutation and
  the same durable receipt
- [ ] 8.4 Re-run the complete application/worker smoke and strict OpenSpec
  validation; capture final Git/index, services, slots, ownership, backup, RAID,
  production, preview and Beautips fingerprints with zero temporary residue
- [ ] 8.5 Verify every evidence `SHA256SUMS`, update the programme decision log,
  evidence ledger and resume point, mark only completed tasks, archive the
  change, run global strict validation, commit and push all declared branches
- [ ] 8.6 Report the preserved explicit operator choice to retry or abandon
  AgentRun 96 and stop without executing either choice or beginning unrelated
  work
