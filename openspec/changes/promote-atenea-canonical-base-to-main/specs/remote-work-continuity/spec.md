## ADDED Requirements

### Requirement: Source-base reconciliation preserves continuity

Changing Atenea's canonical base declaration SHALL be an explicit,
fingerprinted reconciliation of append-only Git history and persisted project
configuration. It SHALL NOT rewrite retained WorkSession identity, invent
ownership, reassign slots, start runtimes or redispatch AgentRuns.

#### Scenario: Main reconciliation is repeated

- **WHEN** the canonical checkout, mirror, project default and worker registry
  already resolve to the accepted main commit
- **THEN** repetition changes nothing and creates no session, run, lease,
  routing, listener, container or network

#### Scenario: Reconciliation must be rolled back operationally

- **WHEN** a post-merge operational declaration fails its acceptance check
- **THEN** only that exact declaration may return to its recorded prior value;
  merged GitHub history and retained session history are not rewritten
