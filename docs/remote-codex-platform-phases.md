# Remote Codex Platform Phase Contracts

Every phase is a separate OpenSpec change. A phase is not complete until implementation, evidence, rollback and archive gates pass.

## Common lifecycle

1. Entry evidence proves dependencies and rollback are available.
2. Implementation remains disabled or non-authoritative until tests pass.
3. Technical and operator acceptance evidence is retained.
4. Rollback is executed in a representative environment, not merely documented.
5. Production observation has a named duration and owner.
6. The change is archived before a dependent phase becomes authoritative.

## Phase 1: bootstrap-secure-codex-worker

Entry:

- foundation archived;
- RAID `[UU]`, host identity and hardware evidence current;
- verified root break-glass key;
- no production workload on AX42;
- tailnet ownership may remain deferred until the enrollment task, but must be resolved before joining.

Implementation:

- versioned host automation under the Atenea repository;
- named administrative user and tested SSH key;
- package/security updates, hostname and time configuration;
- key-only SSH policy, reduced root routine access and firewall;
- Tailscale installation and least-privilege device enrollment;
- SMART/RAID/disk monitoring;
- filesystem layout, service accounts and secret directories;
- Docker/runtime prerequisites without exposing them to Codex;
- Codex/OpenSpec version pins in worker image definitions, not ad-hoc running containers.

Evidence:

- named admin and break-glass login both tested before SSH policy reload;
- public scan exposes only approved services;
- private connectivity between laptop, Atenea and AX42;
- RAID/SMART healthy and an alert test recorded;
- reboot returns all baseline services and private access.

Rollback:

- retain an active verified SSH session during policy changes;
- restore the previous sshd/firewall configuration atomically;
- remove Tailscale enrollment without affecting public break-glass;
- stop/disable worker prerequisites; no repository or AgentRun state exists yet.

Observation/archive:

- 24 hours without auth, network, RAID or service-start regression;
- archive only after automation can re-check the declared state.

## Phase 2: establish-project-runtime-contract

Entry:

- secure worker phase archived;
- worker private access and host headroom proven;
- no real AgentRun routing enabled.

Implementation:

- manifest schema for toolchains, build/start/stop/health/preview/browser/artifacts/secrets/workload class;
- `dev` compatibility CLI plus `--json`;
- canonical mirrors and session worktree layout;
- dynamic runtime names/ports and controlled caches;
- mediated sandbox prototype that proves no host Docker socket or cross-session access;
- dummy modern-Docker and legacy-Tomcat manifests.

Evidence:

- schema rejects missing/unsafe fields;
- two dummy sessions using the same internal port remain isolated;
- `dev up/status/logs/url/stop/doctor` is idempotent and structured;
- boundary tests deny host and neighbouring workspace access;
- reboot reconciliation and cleanup preserve declared artifacts.

Rollback:

- stop dummy runtimes and remove only their proven-owned resources;
- uninstall/disable runner service while preserving mirrors and worktrees for inspection;
- no Atenea production session is routed to the worker.

Observation/archive:

- repeat the isolation/lifecycle suite after a worker reboot;
- archive after the manifest contract and CLI are stable enough for remote routing.

## Phase 3: route-agent-runs-to-remote-worker

Entry:

- runtime contract archived;
- worker identity, protocol compatibility and dummy capacity healthy;
- database backup and migration rollback available;
- existing executor remains usable.

Implementation:

- worker registration and health/capacity model;
- AgentRun dispatch identity, execution target, workspace identity and lease state;
- idempotent dispatch, progress, cancellation and terminal delivery;
- four-slot queue and two-heavy-operation permits;
- startup/partition reconciliation by execution target;
- feature switch that affects only newly opened WorkSessions.

Evidence:

- duplicate dispatch cannot create duplicate work;
- backend restart preserves a live worker run;
- network partition produces reconciling state and no duplicate terminal response;
- cancellation preserves unrelated sessions;
- old locally executed sessions retain their current reconciliation semantics.

Rollback:

- route only new sessions back to the old executor;
- reconcile/cancel remote active sessions explicitly;
- never reassign an active workspace automatically;
- schema rollback is expand/contract and does not require destructive down migration.

Observation/archive:

- one synthetic session completes multiple turns over at least one backend restart;
- archive before any real project becomes authoritative on the worker.

## Phase 4: add-private-session-previews

Entry:

- remote synthetic run lifecycle proven;
- tailnet ACL and preview ownership approved;
- artifact storage and authentication boundary available.

Implementation:

- session preview registry and readiness state;
- private reverse proxy/Tailscale route;
- generated SSH localhost tunnel;
- Playwright/Chromium execution and required viewports;
- session/run artifact metadata, retention and ordered latest/N resolution;
- web and Android preview/artifact read models.

Evidence:

- Internet scan cannot reach preview or Codex ports;
- laptop and Android open a private ready preview;
- localhost tunnel passes a declared compatibility case;
- DOM assertions and inspected desktop/mobile screenshots are retained after preview teardown;
- latest/previous/N never cross session boundaries.

Rollback:

- disable preview route and new UI affordances while retaining artifacts;
- stop preview runtimes without affecting Codex/Git session state;
- remove temporary public share immediately if one was explicitly tested.

Observation/archive:

- validate URL expiry, artifact retention and mobile behaviour through one complete synthetic session.

## Phase 5: project onboarding

Common entry gate per project:

- canonical remote, branch and commit selected;
- uncommitted laptop/Atenea work resolved without automatic overwrite;
- manifest and named secrets reviewed;
- deterministic non-production data/fixtures available;
- build, runtime, health, browser and cleanup commands defined;
- rollback and local fallback documented.

Pilot comparison:

| Candidate | Advantages | Risks | Decision |
|---|---|---|---|
| Checkpol | two-service Compose stack; Java 21; focused product | 14 current local changes; Stripe, SES and truststore paths; branch diverges | do not pilot until active work is reconciled |
| Beautips | locally clean main; existing Docker workflow and health endpoint | three services, persistent assets/imports and WhatsApp/bootstrap secrets; commit differs from Atenea | provisional pilot after commit reconciliation and safe fixtures |

Modern Docker cohort:

- Beautips pilot;
- Checkpol after feature reconciliation;
- Yvateve after importing canonical remote and classifying untracked artifacts;
- Fomasys only after resolving conflicting remotes and dirty session worktree.

Legacy cohort:

- ISC proves JDK 17 build, JDK 8/Tomcat 8 runtime, five language variants, storage and browser origins;
- Recambios proves feature/base branch choice, JDK 17 build, JDK 8/Tomcat 8 runtime and representative data/services.

Acceptance per project:

- prompt changes the correct session worktree;
- tests/build pass using canonical commands;
- runtime health and manual private preview pass;
- Playwright DOM and visual evidence pass where UI applies;
- laptop disconnect/reconnect preserves work;
- publish, merge sync, close and cleanup preserve Git invariants.

Rollback disables only that project on the worker and keeps already accepted projects available.

## Phase 6: harden-worker-operations

Entry:

- at least one real project completes end to end;
- external backup target and retention approved;
- worker metrics and alert delivery path available.

Implementation:

- encrypted external backup and restore automation;
- RAID/SMART/capacity alerts;
- run age, queue, slot, resource, preview and cleanup telemetry;
- safe garbage collection and quarantine;
- four-session/two-heavy capacity suite;
- restart, partition, cancellation, disk-pressure and host-loss runbooks.

Evidence:

- restore to an empty supported host or isolated path;
- degraded RAID/disk threshold alert test;
- orphan cleanup never removes an active session resource;
- all acceptance thresholds in `remote-codex-platform-acceptance.md` pass.

Rollback disables automated cleanup/routing first, preserves audit and worktrees, and restores the previous known-good policy/image.

Observation/archive:

- seven days of representative operation including at least one planned reboot;
- default cutover is a separate recorded decision after archive.
