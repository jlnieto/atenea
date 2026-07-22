## 1. Programme authority

- [x] 1.1 Add a canonical `docs/remote-codex-platform-program.md` ledger with objective, invariants, scope, phase order, current phase and resume protocol.
- [x] 1.2 Add a current-versus-target topology that assigns durable state, execution, repository, preview, artifact and secret ownership to Atenea or the worker.
- [x] 1.3 Record the architectural decision log, rejected alternatives, unresolved decisions, decision owner and latest safe decision date.
- [x] 1.4 State explicitly that this foundation changes no runtime routing and that each implementation phase requires its own OpenSpec change, release evidence and rollback.

## 2. Baseline and migration inputs

- [x] 2.1 Record a secret-free baseline of the laptop workflow, Atenea control-plane runtime and verified AX42 CPU, memory, RAID, disk and operating system.
- [x] 2.2 Inventory Yvateve, Beautips, ISC, Recambios, Fomasys and Checkpol by canonical remote, branch state, uncommitted-work risk, toolchain, runtime, data dependency and browser verification needs.
- [x] 2.3 Inventory the current `dev` command contract and map every laptop-specific path, fixed port, shared runtime, secret source and resource guard to its target manifest or worker responsibility.
- [x] 2.4 Record the current Codex App Server, authentication guard, workspace mount, AgentRun lifecycle and startup-reconciliation behavior that later phases must preserve or migrate.
- [x] 2.5 Produce the initial threat and exposure inventory for public SSH, worker API, Codex App Server, Docker control, previews, repositories, secrets and backup data.

## 3. Independently releasable phase contracts

- [x] 3.1 Define entry, implementation, evidence, rollback, observation and archive gates for `bootstrap-secure-codex-worker`.
- [x] 3.2 Define the same gates for `establish-project-runtime-contract`, including the manifest schema, `dev --json`, isolation spike and dummy-session proof.
- [x] 3.3 Define the same gates for `route-agent-runs-to-remote-worker`, including worker registration, durable dispatch, leases, queueing, cancellation and restart reconciliation.
- [x] 3.4 Define the same gates for `add-private-session-previews`, including private URLs, localhost tunnels, Playwright and ordered screenshot artifacts.
- [x] 3.5 Define project onboarding gates and select one pilot only after comparing Checkpol and Beautips against authentication, data and browser complexity.
- [x] 3.6 Define separate onboarding evidence for modern Docker projects and legacy ISC/Recambios Tomcat compatibility.
- [x] 3.7 Define the same gates for `harden-worker-operations`, including external restore, disk/RAID alerts, garbage collection and capacity exercises.

## 4. Acceptance and rollback matrices

- [x] 4.1 Define a laptop-parity matrix for prompt submission, continued execution, Git changes, project control, logs, manual preview, Playwright, screenshots, publish and close.
- [x] 4.2 Define the pilot end-to-end acceptance flow from WorkSession resolve through disconnect, reconnect, browser evidence, PR publish, merge synchronization, close and cleanup.
- [x] 4.3 Define measurable four-session and two-heavy-workload capacity thresholds that preserve heartbeat, SSH, cancellation and unrelated-session responsiveness.
- [x] 4.4 Define restart, control-plane disconnect, worker disconnect, stale thread, cancellation timeout, disk pressure and orphan-runtime recovery cases.
- [x] 4.5 Define web and Android acceptance evidence for queued, running, blocked, reconciling, preview-ready and terminal states without requiring the laptop to remain online.
- [x] 4.6 Define a rollback matrix that pins active sessions, restores routing only for new sessions and preserves worktrees, branches, conversation and audit state.

## 5. Validation and handoff

- [x] 5.1 Resolve or explicitly defer the tailnet ownership, backup target, pilot selection, localhost compatibility and initial sandbox decisions before their dependent phase starts.
- [x] 5.2 Cross-check the programme ledger against current code, tests, migrations and canonical Atenea documentation and record any pre-existing documentation drift separately.
- [x] 5.3 Run strict OpenSpec validation for the programme change and all generated capability specs.
- [x] 5.4 Archive the validated foundation so its six capabilities become stable specs without deploying runtime changes.
- [x] 5.5 Open `bootstrap-secure-codex-worker` only after the foundation is archived and its exact entry gate is recorded in the programme ledger.
