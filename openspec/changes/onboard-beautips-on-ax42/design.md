## Context

The Atenea onboarding proved the generic real-project protocol and then
returned it to a disabled/released boundary. Beautips is a different
composition: Java 21 plus a Node 22 frontend build, PostgreSQL 16, Redis 7,
persistent assets/imports and optional WhatsApp/bootstrap secrets.

AX42 already runs Beautips administratively in rootless slot 1 from
`/srv/atenea/workspaces/manual/beautips`. This is useful non-impact control
evidence, not a WorkSession that may be adopted. Its database and files have no
independent restore-tested external backup.

## Goals / Non-Goals

**Goals:**

- onboard only Beautips from one canonical GitHub identity;
- prove a separate session-owned worktree and runtime without touching slot 1;
- execute real Codex turns with exact ownership and normal Git delivery;
- verify build, tests, synthetic data, health and desktop/mobile UI;
- leave production, WhatsApp, the administrative pilot and other projects
  unchanged.

**Non-Goals:**

- migrating or backing up the existing administrative Beautips data;
- importing legacy or production-derived salon data;
- enabling WhatsApp, public preview or production deployment;
- reusing the manual `.env`, database, Redis, assets or imports volumes;
- enabling a wildcard or any other project.

## Decisions

### GitHub main is canonical

GitHub `jlnieto/beautips` branch `main` at
`5044a3b07b3db82895e9c8ff47bc4bc9b0e97130`, tree
`132719df7a400f7ba9e724499425e0a64f5b8991`, is canonical. The local
`a6d2f28` and Atenea `bd15a16` copies were clean ancestors and were advanced
only by `pull --ff-only`; AX42 already matched GitHub. All three are now clean
and synchronized.

The entry-gate source manifest was `ops/atenea-runtime.yml`, SHA-256
`09717d5633fe3909f97bdcd0cb7b30817f02b202a9b8d57933323efa041a44ec`.
It was pinned as an input, not accepted unchanged.

Task 2.1 replaces it at commit
`e4256d7fe1610e191099bd12ce993591a5cd4b7a`, tree
`8e52657add269c84700105aa7028728d4ddf2810`, with the runtime-contract v1
manifest `ops/atenea-runtime.json`, SHA-256
`365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82`,
and the separate managed Compose definition `ops/docker-compose.atenea.yml`,
SHA-256
`840e64166e8e1ddaefb74d11763fe150e6539074bb02c3173e2175a446555941`.
The manual Compose definition remains unchanged.

The managed definition has no fixed container name, project name, host port,
network or volume identity and does not reference a tracked or manual env
file. The worker allocation supplies the exact WorkSession paths, Compose
project, loopback ports, network and volume prefix; complete ownership labels
are mandatory. Its stop operation always requests volumes, orphans and local
images be removed. This declares the cleanup boundary without enabling or
executing it before the reviewed mediator and exact registry exist.

Task 2.2 adds the source registry
`ops/worker/project-codex-allowlist-v1.json`, SHA-256
`31694d1e345f7c40f5a7287fa5ee91cd8f8c2df39031a4771c0f931b907d3418`,
under the closed Draft 2020-12 schema
`runtime-contract/project-codex-allowlist-v1.schema.json`, SHA-256
`97e49b814f0a339fead7bf8598bb05dda53b3ddb2078457994c233f8b4d271cc`.
It contains exactly Beautips and pins repository, branch, commit, tree,
manifest and managed Compose identities to worker `ax42-01`, normal workload
and slots 2–4. Selection and execution are both false and the workspace map is
empty.

This registry is reviewed source only at this gate. It is not installed over
the disabled Atenea registry, consumed by routing or capable of lifecycle
execution. Tasks 2.3–2.6 must add the mediated operations, exact sandbox,
secret boundary and install/rollback tooling before any installed Beautips
record can exist.

Task 2.3 advances the accepted source to
`e9e0b3c319c518363d4135f5378ebbddced96dfb`, tree
`533d32f97ae362997ad003170a826da674c31c1d`, solely to make both smoke
scripts reject repository `.env` and missing named inputs in managed mode.
Their manual default mode remains compatible.

The source-only mediator
`ops/worker/beautips-operation-mediator-v1.py` accepts only a canonical
WorkSession UUID and one of ten symbolic operations. It derives allocation,
slot, worktree, Git, manifest, Compose, ports, runtime names, cache paths,
timeouts, fixed images and named secret references without accepting caller
commands, paths, endpoints or environment. Every generated plan validates
against `runtime-contract/beautips-operation-plan-v1.schema.json` and remains
`executionEnabled=false` until the later installation gate.

The exact operation set is Node build, Maven test, Compose build, runtime
start/health/logs/stop/cleanup and the two reviewed smoke scripts. Unknown
operations, slot 1, noncanonical sessions, duplicate or foreign ports, foreign
project/path/Git identity and modified manifest or Compose fail closed before
any operation can execute.

### The administrative pilot is foreign retained state

The three slot 1 containers, their network, four volumes, loopback listener,
manual workspace and root-owned secret boundary are immutable non-impact
controls. Managed allocation MUST use a different free slot and MUST reject
any attempt to register, relabel, attach, stop, snapshot or clean those
identities.

### Acceptance data is synthetic and disposable

The managed runtime starts with empty PostgreSQL migrations, empty Redis and a
small versioned fixture containing only invented tenants, users, loyalty
events and files. It uses separate session-labelled database, Redis,
assets/imports volumes. No existing dump, backup, legacy import, production row
or administrative volume is mounted or copied.

Local backup folders are on the laptop root filesystem and AX42 has no
configured restic, borg or rclone target. They do not satisfy independent
external backup. Therefore every acceptance artifact remains
non-authoritative and cleanup is mandatory.

### Secrets and external messaging fail closed

Only named secret requirements may enter manifests. No current manual env
file, value, token, cookie, credential or Codex authentication material enters
requests or evidence. Acceptance uses generated synthetic bootstrap values in
an exact session secret boundary. WhatsApp embedded signup, webhook,
scheduler, outbox delivery and external Graph API egress remain disabled.

### Tailnet preview; no localhost contract

The accepted UI paths use relative navigation and the WhatsApp/OAuth paths are
disabled. Beautips therefore declares no required localhost compatibility for
this acceptance. The managed preview is tailnet-only on an allocation-derived
port. Any absolute-localhost, cookie, redirect or browser failure blocks this
decision and requires an explicit manifest revision rather than an implicit
tunnel.

### Build, verification and delivery are fixed

The build uses the committed Node 22/Maven 3.9.9/Java 21 Dockerfile and the
canonical Maven test entrypoint. Health uses `/actuator/health`; functional
checks use reviewed bounded smoke scripts and Playwright verifies exact
desktop/mobile states. Delivery uses one exact WorkSession draft pull request,
reviewed non-force merge synchronization and canonical close.

### Rollback is exact and observation is finite

Rollback first disables new Beautips selection, reconciles/cancels only the
persisted WorkSession execution and removes only its fully owned runtime,
preview, PostgreSQL, Redis, assets/imports and browser resources. Git and
sanitized evidence remain. The slot 1 administrative pilot and every foreign,
partial or ambiguous resource remain untouched.

Close observation lasts 15 minutes with normalized samples at minute 0, 5, 10
and 15. Any drift in the administrative pilot, production, other slots,
persisted ownership or health blocks archive.

## Migration Plan

1. Seal the canonical identity, administrative control and decisions.
2. Make the Beautips manifest and project registry session-safe while disabled.
3. Add control-plane allowlisting and focused denial tests.
4. Allocate one disposable WorkSession outside slot 1 and enable only it.
5. Execute real turns, build/tests, synthetic runtime, preview and browser
   acceptance.
6. Publish, synchronize, close, restart, disable, rollback and exact-clean.
7. Observe, retain evidence and archive before another project begins.

## Open Questions

No question may be answered implicitly. Independent external backup remains a
gate for authoritative non-Git Beautips state, and production promotion remains
outside this change.
