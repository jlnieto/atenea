## Context

The secure AX42 baseline and project runtime contract are archived. The worker
has four bounded rootless slots, pinned Java 21/Maven, Node, Docker Compose and
Playwright toolchains, and no Atenea AgentRun is routed there.

Atenea development still uses the production/control-plane host's mutable
repository path and Docker development stack. The operator explicitly resolved
the pre-existing dirty state by reviewing it, passing 327 backend tests,
building and visually checking the React console, and publishing
`feature/actualizar-conversacion-en-web` at
`a9fe14989544308acc587e3eb71cb985fa637b2d`. Production remains healthy and
unrouted.

The current Atenea Compose file is not itself a worker contract: it has fixed
container names, host-specific mounts and host ports. The accepted runtime
manager also intentionally supports only the synthetic fixtures proven in the
previous phase. This change must add a narrowly scoped real-project adapter
without converting the manager into an arbitrary Compose proxy.

## Goals / Non-Goals

**Goals:**

- Make GitHub the reproducible source of an Atenea development worktree on
  AX42, starting from the accepted branch and entry commit.
- Run Atenea web build, backend tests, development runtime, empty development
  PostgreSQL, health checks and browser checks inside one admitted rootless
  slot.
- Keep all runtime identities, mounts, ports, logs, artifacts and mutable data
  owned by the synthetic or administrative Atenea development session.
- Prove desktop and mobile operator surfaces from persisted synthetic data.
- Preserve production services, data, secrets, deployment authority and legacy
  execution throughout rollout and rollback.
- Prove restart reconciliation and a real rollback that retains the worktree
  and declared evidence.

**Non-Goals:**

- Route an Atenea `AgentRun` to AX42 or change backend scheduling/persistence.
- Move Atenea production APIs, PostgreSQL, secrets, backups, monitoring,
  rescue services or deployment authority.
- Import a production database dump or production authentication material.
- Remove the existing executor, control-plane repository or development
  fallback.
- Onboard any other real project or implement generic private preview routing.
- Publish a development service to the Internet.

## Decisions

### 1. Treat the published feature branch as the relocation base

The relocation record pins the canonical repository, branch and entry commit.
Worker setup fetches from GitHub into the canonical mirror and creates a
session-owned worktree; it never copies the control-plane worktree. Subsequent
phase implementation changes are ordinary reviewed commits descended from that
base.

Alternative considered: copy the former dirty directory to AX42. Rejected
because it would make an unreviewed filesystem snapshot authoritative and
could silently include ignored state.

### 2. Add an Atenea-specific allowlisted adapter

The generic manifest remains authoritative, but the runtime engine gains only
the operations and Compose model required by the reviewed Atenea manifest. A
worker-specific Compose definition or generated override removes fixed
container names, host-specific mounts and host-published ports. It allows only
the declared application, development PostgreSQL and development Codex App
Server services, loopback allocation and owned persistent paths.

The manager validates the manifest hash, caller, WorkSession, slot, worktree,
service set, mounts, image/build inputs and generated labels before every
daemon operation. Codex and project processes never receive a Docker socket.

Alternative considered: run the existing `docker-compose.dev.yml` unchanged.
Rejected because its absolute mounts and global names violate session
ownership. A general arbitrary-Compose pass-through is also rejected.

### 3. Build one reproducible development toolchain

The manifest declares the existing pinned worker toolchains: Git, Java 21,
Maven 3.9.9, Node 22.16, rootless Docker/Compose and Playwright 1.60. The web
build runs before Maven packaging. Builds use reconstructible session caches
and write reports only to declared artifact roots.

The current AX42 toolchain verification and manifest-schema check are entry
evidence, not acceptance evidence. Implementation must repeat them against the
committed Atenea manifest and exact selected source commit.

### 4. Start from an empty, synthetic PostgreSQL fixture

The development database is PostgreSQL 16 with a new owned volume. Flyway
applies all versioned migrations to an empty schema. A synthetic operator is
bootstrapped with a named development-only secret; external OpenAI, DeepSeek,
FCM, managed-host and production GitHub actions remain disabled or use explicit
non-production fakes.

No dump, volume, credential, row or network connection from production is
permitted. The fixture plan records only schema versions, synthetic identifiers
and counts, never secret values.

Alternative considered: sanitize a production snapshot. Deferred to the
development-database-lifecycle phase because no reviewed sanitization contract
exists yet.

### 5. Separate runtime verification into data, DOM and visual evidence

Acceptance first proves migrations and synthetic records, then uses Playwright
with finite timeouts to assert login, operational state and critical actions in
the rendered DOM. Desktop `1440x900` and mobile `390x844` screenshots are
retained and inspected for hierarchy, clipping, overlap and horizontal
overflow.

Browser credentials are injected as named synthetic secrets and are never
written to Git, screenshots or ordinary logs.

### 6. Use explicit production non-impact sentinels

Before and after every mutating AX42 exercise, evidence records sanitized
production branch/commit/index state, public health, relevant container
identity/config fingerprints, database identity, and zero AX42 routing
references in source and running environments. The phase does not restart,
reconfigure or deploy production.

The control-plane worktree remains a fallback, but normal development
documentation points to the AX42 worktree after acceptance.

### 7. Keep administrative continuity distinct from managed routing

The existing private SSH/tmux bridge may host the Atenea development session
and must survive a laptop disconnect. It remains explicitly administrative and
does not represent an Atenea-dispatched `AgentRun`, worker lease or remote
routing acceptance.

### 8. Roll back only proven-owned AX42 development resources

Rollback stops the admitted Atenea development runtime, releases its
allocation, removes only resources with exact manager labels and preserves the
mirror, worktree, Git branch, logs and declared artifacts. A second rollback is
idempotent. Unlabelled, foreign or ambiguous resources are rejected.

After rollback, production and the legacy executor continue unchanged.
Restart acceptance later recreates or reconciles the AX42 development runtime
from persisted records; it does not make AX42 authoritative for AgentRuns.

### 9. Deliver the selected commit through a mediated ephemeral snapshot

The rootless slot identity cannot traverse the canonical worktree's
`atenea-worker:atenea` mode-`2770` ancestors. The runtime engine therefore
creates a byte-exact `git archive` from the allocation's selected commit,
checks its fixed archive SHA-256 and extracts it into a deterministic
WorkSession/runtime-scoped path beneath `/tmp/atenea-runtime-delivery`.

The delivery is owned by the assigned rootless slot, contains an ownership
marker with the complete WorkSession, runtime, commit and tree identities, and
is the only source bind authorized in the generated Compose input. The
canonical mirror, worktree, index, ACLs, owners, groups and modes are not
changed. Named development secrets are copied only into the private delivery
boundary with no values in the plan, command line, logs or evidence.

The engine consumes the committed manifest and AX42 Compose file only at their
reviewed hashes, then generates a closed Compose definition for the three
allowlisted services. It requires the retained labelled PostgreSQL volume as
an external resource, creates one internal labelled network and adds only
deterministic names, labels, mounts, limits and fail-safe development
environment values. Because an internal rootless bridge has no gateway
endpoint for Docker's normal publisher, the adapter registers exactly the
three allocated loopback mappings through slot2's fixed RootlessKit `3.0.2`
API and retains their returned identities for mediated removal.

The application is packaged first in a bounded, non-runtime build container.
The three-service runtime itself has no external network path. Codex App Server
uses a reviewed image built from the pinned Node digest and Codex `0.145.0`,
then fixed by its resulting OCI digest. Because Codex rejects an
unauthenticated non-loopback listener, that image keeps the authentication-
disabled App Server on container loopback and exposes the declared container
port through a fixed same-container TCP proxy. The proxy has no credentials,
authority or external route; the internal runtime network and host-loopback
RootlessKit publication remain the outer boundary. The installed client,
manager, engine and dedicated Atenea adapter remain root-owned; only
`atenea-worker` may invoke the manager through the exact sudoers boundary.

## Risks / Trade-offs

- [Atenea's current Compose assumptions bypass isolation] → replace them with a
  reviewed worker definition and negative tests for every forbidden mount,
  name, network and daemon feature.
- [The development Codex service could expose worker authentication] → use a
  dedicated named secret boundary, mount only the intended auth material and
  keep it unreadable by application/project containers.
- [An empty database misses production-shaped cases] → define explicit
  synthetic fixtures for the operator paths exercised here and defer any
  sanitized snapshot to its dedicated data-lifecycle gate.
- [Rootless image builds and the full test suite consume a heavy permit] →
  admit the lifecycle as `heavy`, retain host headroom and measure CPU, memory,
  PIDs, disk and SSH responsiveness.
- [Documentation could imply remote AgentRun routing] → label the phase as
  administrative development relocation and keep routing sentinels in every
  acceptance/rollback check.
- [Generated frontend assets can become stale] → make the canonical build
  remove prior assets, run `npm ci`, require a zero-vulnerability audit and
  verify the built index references only current artifacts.

## Migration Plan

1. Record the accepted GitHub branch/entry commit, production sentinels, AX42
   baseline, schema-valid manifest candidate and empty-fixture plan.
2. Add the committed Atenea manifest, worker Compose/adapter and negative policy
   tests while all real project activation remains disabled.
3. Create the GitHub-backed mirror and one admitted session-owned development
   worktree on AX42.
4. Build the web console and backend, run the complete tests against a new
   PostgreSQL 16 database and retain reports.
5. Start the development runtime privately, validate health and synthetic data,
   then collect and inspect desktop/mobile browser evidence.
6. Prove SSH/tmux disconnect and resume without claiming managed execution.
7. Stop and reconcile the runtime, preserve declared state, execute rollback
   twice and confirm all non-impact sentinels.
8. Repeat the lifecycle after an explicitly authorized AX42 restart.
9. Update operator documentation and archive only after the observation and
   rollback gates pass.

Rollback is available after step 3: disable Atenea development activation,
stop only its proven-owned runtime, preserve its mirror/worktree/artifacts and
continue all development and production control through the existing Atenea
host.

## Open Questions

- Does the development Codex App Server need real ChatGPT authentication for
  this phase's acceptance, or is authenticated administrative Codex continuity
  plus an actionable unavailable development service sufficient?
- Which synthetic project/session records are the minimum useful browser
  fixture beyond the operator account?
- What measured CPU and memory values should replace the initial heavy-workload
  limits for the Atenea build and browser lifecycle?
- Does any accepted Atenea callback or cookie require localhost compatibility,
  or can the development UI remain private-route only?
