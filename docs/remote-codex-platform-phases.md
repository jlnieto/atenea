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

## Phase 3: relocate-atenea-development-to-ax42

Entry:

- runtime contract archived and its isolation boundary accepted;
- GitHub branch/commit and the existing dirty Atenea worktree are reconciled by
  an explicit operator decision, never copied or discarded automatically;
- AX42 has a manifest-valid Atenea development toolchain and non-production
  fixture plan;
- production Atenea remains healthy and no production routing change is needed.

Implementation:

- create the GitHub-backed Atenea mirror and session-owned development worktree
  on AX42;
- move Atenea build, test, development runtime, development PostgreSQL,
  Playwright and development artifacts to AX42;
- update developer/operator documentation so `/srv/atenea` on the control plane
  is not treated as the normal development workspace;
- retain production web/mobile APIs, production PostgreSQL, secrets, backups,
  monitoring and deploy/rollback authority on the Atenea server;
- remove no legacy executor component during this phase.

Evidence:

- Atenea tests, build, development runtime, DOM checks and inspected
  desktop/mobile screenshots pass on AX42;
- GitHub remains canonical and the AX42 worktree can be recreated from the
  selected commit plus declared non-secret inputs;
- production containers, production database and public endpoints remain
  unchanged throughout the exercise;
- a laptop disconnect does not terminate the administrative development
  session, without claiming managed AgentRun routing.

Rollback:

- stop only the AX42 Atenea development runtime and preserve its worktree and
  artifacts for inspection;
- continue using the existing Atenea production/control plane and legacy
  executor while the later routing phase is pending;
- never overwrite the dirty pre-existing Atenea worktree or production data.

Observation/archive:

- repeat the Atenea development lifecycle after an AX42 restart;
- archive before changing AgentRun routing.

## Phase 4: route-agent-runs-to-remote-worker

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

## Phase 5: add-worksession-attachments

Entry:

- remote synthetic run continuity is proven;
- attachment storage, metadata authority, access control and retention proposal
  are approved;
- upload and artifact limits have safe defaults.

Implementation:

- WorkSession- and AgentRun-scoped attachment registration for operator uploads,
  screenshots, traces and reports;
- ordered metadata containing source, creation time, content type, size,
  retention and integrity identity;
- web/mobile upload and retrieval through Atenea without granting arbitrary
  worker filesystem access;
- deterministic current-session resolution for “latest screenshot”,
  “previous screenshot” and “last N screenshots”.

Evidence:

- prompt and image input reach only the intended WorkSession;
- latest/previous/N ordering is deterministic and cannot cross sessions or
  projects;
- retained attachments survive client disconnect, worker service restart and
  preview teardown;
- unauthorized, oversized and unsupported inputs fail with an actionable state.

Rollback:

- disable new upload affordances while preserving already indexed evidence;
- do not delete retained attachments as part of routing rollback.

Observation/archive:

- exercise upload, Codex consumption, ordering and later mobile retrieval across
  one complete synthetic session.

## Phase 6: add-private-session-previews

Entry:

- remote synthetic run lifecycle proven;
- tailnet ACL and preview ownership approved;
- WorkSession attachment capability archived;
- artifact storage and authentication boundary available.

Implementation:

- session preview registry and readiness state;
- private reverse proxy/Tailscale route;
- generated SSH localhost tunnel;
- Playwright/Chromium execution and required viewports;
- preview-generated artifacts registered through the WorkSession attachment
  contract;
- web and Android preview/artifact read models.

Evidence:

- Internet scan cannot reach preview or Codex ports;
- laptop and Android open a private ready preview;
- localhost tunnel passes a declared compatibility case;
- DOM assertions and inspected desktop/mobile screenshots are retained after preview teardown;
- preview evidence remains associated with the originating session and run.

Rollback:

- disable preview route and new UI affordances while retaining artifacts;
- stop preview runtimes without affecting Codex/Git session state;
- remove temporary public share immediately if one was explicitly tested.

Observation/archive:

- validate URL expiry, artifact retention and mobile behaviour through one complete synthetic session.

## Phase 7: establish-development-database-lifecycle

Entry:

- runtime namespaces and WorkSession attachments are accepted;
- each candidate database is classified as development-only or production;
- fixture, sanitization, retention and secret requirements are reviewed.

Implementation:

- isolated development database identities, storage, health and backup/restore
  commands on AX42;
- manifest-declared create, migrate, seed, snapshot and replace operations;
- explicit confirmation for destructive replacement;
- hard rejection of production hosts, credentials, database identities and
  network targets.

Evidence:

- two sessions cannot read or replace each other's database;
- a confirmed development replacement is auditable and reproducible;
- an unconfirmed replacement and every production-like target are denied before
  mutation;
- rollback restores a declared development snapshot without touching Git or
  production data.

Rollback:

- stop database automation, preserve owned development volumes for inspection
  and keep production connectivity unavailable to the worker.

Observation/archive:

- run create/migrate/seed/replace/restore twice on representative modern and
  legacy fixtures before any dependent project onboarding.

## Phase 8: individual project onboarding

Each project is a separate OpenSpec change. No cohort-wide change may make every
project schedulable at once. Planned changes are:

1. `onboard-atenea-on-ax42`
2. `onboard-beautips-on-ax42`
3. `onboard-checkpol-on-ax42`
4. `onboard-yvateve-on-ax42`
5. `onboard-fomasys-on-ax42`
6. `onboard-iscspain-on-ax42`
7. `onboard-recambios-on-ax42`

The exact order after Atenea and Beautips may change when entry evidence is
refreshed. A project that fails its gate remains disabled without blocking
already accepted projects.

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

## Phase 9: add-controlled-production-deployments

Entry:

- at least one onboarded project completes build, verification, publish and
  reconciled close on AX42;
- artifact format, provenance, retention and target mapping are approved;
- restricted production deploy identities and rollback runbooks exist outside
  ordinary Codex execution.

Implementation:

- produce immutable, versioned deployment artifacts from reviewed Git commits;
- separate build/publish from a confirmed production promotion command;
- allowlist target/service actions and restrict credentials to the deployment
  boundary;
- require explicit confirmation, preflight, health checks, audit record and
  version-addressed rollback;
- prevent normal AgentRuns and development database tooling from reaching
  production deployment or database authority.

Evidence:

- an unconfirmed, unreviewed or mismatched artifact cannot deploy;
- a confirmed non-production rehearsal records artifact, actor, target, checks
  and result;
- representative health failure triggers the documented rollback path;
- rollback selects a known version and does not rebuild mutable source.

Rollback:

- disable promotion while preserving artifacts and audit records;
- continue manual approved production operations until the workflow is accepted.

Observation/archive:

- each production target requires its own enablement evidence; no global
  production permission is inferred from one service.

## Phase 10: harden-worker-operations

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

## Phase 11: retire-legacy-atenea-executor

Entry:

- AX42 is the accepted default for all enabled development projects;
- controlled deployment and hardening changes are archived;
- no open WorkSession or recoverable run is pinned to the legacy executor;
- rollback and restore evidence cover worker loss.

Implementation:

- disable new legacy execution first;
- observe and inventory remaining Atenea-hosted Codex, preview, repository and
  development-database dependencies;
- retire only proven-unused executor components in bounded steps;
- keep Atenea production, PostgreSQL, secrets, backups, monitoring and
  deploy/rollback services intact.

Evidence:

- web/mobile control, scheduling, notifications and production operations remain
  healthy with the legacy executor disabled;
- all development execution, builds, runtimes, databases, Playwright, previews,
  repositories, worktrees and attachments resolve through AX42;
- no production secret or database dependency migrated unintentionally.

Rollback:

- re-enable the last known-good legacy route for newly opened sessions only;
- never move an active AX42 session implicitly or delete its worktree/artifacts.

Observation/archive:

- archive after a named observation window and an explicit owner decision that
  bounded legacy executor removal is complete.
