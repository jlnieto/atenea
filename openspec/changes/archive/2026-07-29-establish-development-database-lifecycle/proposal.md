## Why

AX42 can run isolated WorkSession runtimes and retain their evidence, but
development databases still depend on project-specific Compose behavior,
legacy production-copy scripts or manual volume handling. Those paths do not
provide one ownership authority, reproducible fixtures, explicit replacement,
bounded snapshots or a hard production-denial boundary.

Phase 7 establishes a synthetic-only database lifecycle before any real
project onboarding can make development data authoritative on the worker.

## What Changes

- Add a versioned manifest contract for WorkSession-owned PostgreSQL and
  MariaDB development databases with pinned images, deterministic migration
  and seed inputs, named secret references and bounded local snapshots.
- Add a mediated AX42 lifecycle for create, migrate, seed, health, snapshot,
  explicit confirmed replace, restore, status and stop without accepting
  caller-supplied hosts, sockets, database names, volume names or commands.
- Persist immutable database/session/project/worker/allocation/slot/volume
  ownership and monotonic lifecycle revision beneath a controlled worker root.
- Require a pre-replacement snapshot and a short-lived one-use confirmation
  challenge before a destructive development-only replace can mutate data.
- Reject production, preview, real-project, foreign-session, unlabelled,
  partially labelled and ambiguous database targets before mutation.
- Exercise the lifecycle twice on deterministic PostgreSQL and MariaDB
  fixtures while keeping authoritative activation blocked until independent
  external backup is configured and restore-tested.
- Retain only sanitized lifecycle reports through the WorkSession attachment
  contract; raw dumps, database credentials and row data are never attachments.

## Capabilities

### New Capabilities

- `development-database-lifecycle`: Defines development-only classification,
  immutable ownership, manifest operations, explicit replacement, snapshots,
  restoration, reconciliation, retention and production denial.

### Modified Capabilities

- `isolated-project-runtime`: Makes each database namespace and volume derive
  from the immutable WorkSession allocation and manifest.
- `worker-operational-safety`: Denies production-like database authority and
  constrains database cleanup to complete exact ownership.
- `worksession-attachments`: Allows sanitized lifecycle evidence while
  excluding raw database snapshots, credentials and row exports.

## Impact

- Programme repository: database manifest schema, mediated lifecycle,
  PostgreSQL/MariaDB synthetic fixtures, install/verify/rollback tools and
  regression suites.
- AX42: two temporary synthetic database fixtures in free rootless slots,
  private WorkSession-owned volumes and bounded synthetic snapshot storage.
- Atenea source and production: no migration, deployment, database connection,
  endpoint, routing or secret change in this phase.
- Real projects: remain disabled for database automation until their individual
  onboarding change proves sanitized fixtures and independent backup.
