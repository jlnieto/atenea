## ADDED Requirements

### Requirement: Source-base reconciliation preserves continuity

Changing Atenea's canonical base declaration SHALL be an explicit,
fingerprinted reconciliation of append-only Git history and persisted project
configuration. It SHALL NOT rewrite retained WorkSession identity, invent
ownership, reassign slots, start runtimes or redispatch AgentRuns.

The compiled backend and worker source identities, request schemas, runtime
manifest and persisted declarations SHALL move as one reviewed authority set.
No mixed feature/main authority may be installed or enabled.

#### Scenario: Main reconciliation is repeated

- **WHEN** the canonical checkout, mirror, project default and worker registry
  already resolve to the accepted main commit
- **THEN** repetition changes nothing and creates no session, run, lease,
  routing, listener, container or network

#### Scenario: Reconciliation must be rolled back operationally

- **WHEN** a post-merge operational declaration fails its acceptance check
- **THEN** only that exact declaration may return to its recorded prior value;
  merged GitHub history and retained session history are not rewritten

#### Scenario: A closed session still holds active worker capacity

- **WHEN** exact persisted ownership proves the closed session is the sole
  registration and admission holder and it owns no runtime resources
- **THEN** the mediated reconciliation releases only its active registration
  and admission and does not delete its retained allocation, worktree or audit
  history
