## Context

The runtime contract already assigns immutable WorkSession IDs, rootless
slots, isolated networks, ports, worktrees, logs and artifacts. Phase 5
provides retained evidence, while the Phase 3 Atenea runtime proved that a
single retained PostgreSQL volume can survive stop and reboot. What is missing
is a generic data lifecycle that distinguishes recreatable development
fixtures from production, prevents cross-session replacement and makes
snapshot/restore auditable.

The current worker has two protected database classes that are explicitly
outside this change: Atenea production/control databases on the Atenea host,
and Beautips plus the retained Phase 3 Atenea volume on AX42. No existing
database volume is a migration source for Phase 7.

## Goals / Non-Goals

**Goals:**

- classify every target before any database command runs;
- derive database identity, network, volume and endpoint from persisted
  WorkSession ownership;
- support deterministic PostgreSQL and MariaDB development fixtures;
- make replacement explicit, one-use, pre-snapshotted and auditable;
- restore exact snapshots without changing Git or unrelated runtimes;
- reject production-like, foreign, partial and ambiguous targets unchanged;
- retain sanitized evidence and clean only exact synthetic resources.

**Non-Goals:**

- copying, dumping, querying or sanitizing real production data;
- connecting AX42 automation to Atenea, preview or project production hosts;
- onboarding Atenea, Beautips or any other real project;
- selecting an external backup provider or enabling authoritative snapshots;
- adding a general SQL console, arbitrary command runner or caller-selected
  database endpoint.

## Decisions

### Synthetic-only activation until independent backup exists

Phase 7 accepts only records and resources marked
`syntheticDevelopmentFixture=true`. PostgreSQL and MariaDB fixture datasets are
generated from versioned migration and seed files containing no copied
production rows. Real-project activation remains blocked because RAID is
availability, not backup, and no independent external target has yet passed a
restore test.

Alternative considered: use the retained Phase 3 Atenea PostgreSQL volume as a
representative source. Rejected because its ownership predates this change and
the phase must prove that foreign retained data is not adopted implicitly.

### One database identity and volume per WorkSession

The persisted record repeats database UUID, WorkSession UUID, project,
worker, allocation identity/fingerprint, rootless slot, engine, immutable
volume identity and manifest hash. Container, network, volume and snapshot
names derive from the database UUID plus WorkSession runtime identity. The
caller cannot provide a host, port, socket, database name or Docker resource.

Alternative considered: one shared development database per project. Rejected
because replacement and schema experiments would cross WorkSession boundaries.

### Manifest-declared fixed lifecycle

An optional versioned `database` manifest section declares only reviewed
PostgreSQL or MariaDB engine identity, pinned image digest, development-only
classification, fixed migration and seed paths, named password reference,
health query, logical snapshot format and bounded retention. Literal
credentials, absolute paths, shell strings and network targets are invalid.

The mediator exposes fixed operations: `create`, `migrate`, `seed`, `health`,
`status`, `prepare-replace`, `replace`, `snapshot`, `restore`, `stop` and
`cleanup`. It executes engine-native commands from argument arrays and never
evaluates a manifest shell fragment.

### Replacement requires a one-use confirmation and pre-snapshot

`prepare-replace` validates complete ownership, current health and a
development-only target, then persists a random operation UUID with a
five-minute expiry and the current lifecycle revision. `replace` requires that
exact UUID, the unchanged revision and an explicit confirmation value. Before
dropping fixture data it writes and verifies a logical snapshot. A successful,
failed or expired attempt consumes the challenge so replay cannot repeat a
destructive operation.

Alternative considered: treat the operator's broad session authorization as a
permanent replacement switch. Rejected because the runtime contract requires
the destructive boundary to remain explicit and auditable for each target.

### Snapshots are private, bounded and integrity-addressed

Synthetic logical snapshots live beneath
`/srv/atenea/database-snapshots-v1/<worksession>/<database>` with worker-only
permissions. Metadata records engine, schema version, lifecycle revision,
created time, byte count and SHA-256. Phase 7 keeps at most three synthetic
snapshots for seven days. Retention never deletes an authoritative or foreign
snapshot, and authoritative activation remains blocked without external
backup.

Only sanitized operation reports may be attached to a WorkSession. Raw dumps,
row values, passwords, connection strings and database environment are never
uploaded, logged or returned.

### Fail closed before the first mutation

Every operation validates all current resource labels and immutable record
fields before creating, replacing, restoring or deleting anything. Missing,
unlabelled, partial, foreign, real-project or multiply matching resources
return a fixed actionable conflict and remain unchanged. A production-like
host, address, database identity, credential reference or project
classification is rejected before a database client or container command is
invoked.

### Rollback stops automation and preserves owned data first

Rollback disables new lifecycle operations, stops exact synthetic database
containers and preserves their labelled volumes, records and snapshots for
inspection. It is idempotent. Final synthetic cleanup occurs only after
snapshot/restore evidence is sealed and requires complete exact ownership;
foreign volumes and the real Beautips/Phase 3 resources remain untouched.

## Risks / Trade-offs

- [Logical snapshots differ between engines] → pin engine versions and keep a
  versioned format/restore command per engine.
- [A replacement can fail after the old data is dropped] → require a verified
  pre-snapshot and prove restore in both fixture families.
- [A stale challenge could target changed state] → bind it to immutable
  ownership and lifecycle revision, expire after five minutes and consume it
  once.
- [Database dumps can contain sensitive data] → Phase 7 uses generated
  synthetic rows only, stores snapshots privately and never attaches raw data.
- [Retention cleanup can erase needed evidence] → cap only exact synthetic
  snapshots and preserve sanitized reports plus SHA-256 manifests.

## Migration Plan

1. Capture Git, production, worker, slots, volumes, storage and database
   classification; close fixture, sanitization, retention, secret and rollback
   decisions.
2. Add the manifest schema and pure ownership/confirmation/state tests without
   starting a database.
3. Install the default-disabled mediator and prove production/foreign denial.
4. Run the complete lifecycle twice for one PostgreSQL and one MariaDB
   synthetic WorkSession in free slots.
5. Prove isolation, confirmed replace, snapshot restore, restart reconciliation
   and rejection cases.
6. Disable operations, repeat rollback, seal evidence, clean only exact
   synthetic resources and archive before project onboarding.

## Open Questions

None for synthetic Phase 7. External provider/retention, real-project fixture
policy and authoritative snapshot promotion remain explicit onboarding or
operations-hardening gates.
