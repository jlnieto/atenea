## MODIFIED Requirements

### Requirement: Real Atenea Codex WorkSession

An admitted Atenea AgentRun SHALL automatically and idempotently provision the
exact persisted session mirror, worktree, admission, allocation and project
registration before its first dispatch. It SHALL then execute the operator
prompt through one bounded Codex process in that worktree, retain its real
thread and turn identities, and return one idempotent terminal response. The
request MUST NOT grant arbitrary command, path, remote, endpoint, environment
or daemon authority.

#### Scenario: First production-control-plane turn

- **WHEN** an operator submits the first turn to a newly opened exact Atenea WorkSession
- **THEN** AX42 ensures the persisted workspace ownership and dispatches one AgentRun without an SSH preparation step

#### Scenario: Two turns continue one session

- **WHEN** an operator submits two sequential turns to the admitted WorkSession
- **THEN** both use the same worker, workspace and Codex thread while each turn owns one distinct idempotent AgentRun

#### Scenario: Partial or foreign provision exists

- **WHEN** any mirror, worktree, admission, allocation or registry identity conflicts
- **THEN** provisioning fails closed without replacing, deleting or adopting that state
