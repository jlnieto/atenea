# Session workspace and runtime identity contract v1

## Authorities

- Atenea creates the immutable WorkSession UUID, project identity and branch
  intent.
- The worker allocates one slot, worktree and runtime identity for that UUID.
- GitHub remains the canonical source. A mirror or worktree is recoverable
  execution state, not a new remote.
- The runtime manager owns process, container, network, volume, port and log
  resources. Codex never chooses those host identities directly.

## Deterministic layout

For WorkSession UUID `S` and project identifier `P`:

```text
mirror:    /srv/atenea/repositories/P.git
record:    /srv/atenea/workspaces/sessions/S/workspace-v1.json
worktree:  /srv/atenea/workspaces/sessions/S/P
runtime:   ws-<32 lowercase hexadecimal UUID characters>
logs:      /srv/atenea/artifacts/sessions/S/runtime/logs
artifacts: /srv/atenea/artifacts/sessions/S/runs/<agent-run-id>
cache:     /srv/atenea/caches/sessions/S
```

The complete UUID, without hyphens, is used in the runtime identity. It is not
truncated. Runtime-owned Compose projects, networks, volumes, Tomcat bases and
process-unit names are prefixed by that identity and a resource-kind suffix.
The project name alone is never a runtime namespace.

The worker serializes allocation changes and persists
`runtime-allocation-v1.json` beside `workspace-v1.json`. Declared internal ports
are mapped to unique ports bound only on `127.0.0.1`; the same internal port may
therefore be declared by multiple WorkSessions without collision. Repeating an
allocation returns the byte-identical record. A conflicting slot, port,
identity, ownership record or allocation state blocks reconciliation instead
of selecting a replacement silently.

Session caches are isolated below the cache root and carry a worker-written
policy marker declaring them non-authoritative, reconstructible and unavailable
for secrets. Removing a cache MUST NOT remove or rewrite source, workspace
records, logs or retained run artifacts.

The worker-owned record binds the complete session UUID to its project,
canonical credential-free remote, original base commit, session branch, mirror,
worktree and execution target. A path or branch without that matching record is
not owned merely because its name resembles a session identity.

The four normal execution slots are `slot1` through `slot4`. A heavy operation
also leases one of the two independent heavy permits. A third heavy request
remains `blocked` with code `HEAVY_CAPACITY_EXHAUSTED`; it does not start an
extra process.

## Allocation and idempotency

The allocation key is the WorkSession UUID. Repeating allocation for the same
session returns the same project, mirror, worktree, branch, runtime identity and
slot while the allocation exists.

Allocation fails closed when:

- the session already maps to another project, branch or execution target;
- the requested branch is checked out by an unrelated worktree;
- an existing path is not a Git worktree owned by that session;
- a worktree is dirty during recovery and the requested action would reset,
  clean, switch or overwrite it;
- no normal slot or required heavy permit is available.

Recovery may fetch canonical refs and re-register a proven existing worktree.
It MUST NOT run destructive Git cleanup, infer ownership from a directory name,
reuse another session's runtime resources or delete artifacts.

## State model

The allocation state is one of:

```text
pending -> allocated -> provisioning -> ready
                                  \-> blocked
                                  \-> error
ready -> stopping -> stopped
any non-terminal state -> reconciling -> previous state | blocked | error
```

`blocked` means the next operator or capacity action is known and no unsafe
mutation occurred. `error` means the requested operation failed and includes a
safe recovery action. Neither state implies that cleanup is authorized.

`session-allocation-v1.schema.json` defines persisted/allocation output.
`dev-envelope-v1.schema.json` defines the stable response from `dev --json`.
Human `dev` output is a rendering of this state and is not machine authority.

## Error contract

Every failed or blocked `dev --json` response contains:

- a stable uppercase code;
- a short non-secret message;
- whether retry without operator change is meaningful;
- one actionable next step;
- optional structured details whose values are identifiers or counts, never
  tokens, environment values, command output containing credentials or raw
  secret-provider errors.

Version 1 reserves these codes:

- `SESSION_REQUIRED`
- `SESSION_AMBIGUOUS`
- `SESSION_IDENTITY_CONFLICT`
- `WORKTREE_CONFLICT`
- `WORKTREE_DIRTY`
- `RUNTIME_OWNERSHIP_CONFLICT`
- `NORMAL_CAPACITY_EXHAUSTED`
- `HEAVY_CAPACITY_EXHAUSTED`
- `MANIFEST_INVALID`
- `TOOLCHAIN_UNAVAILABLE`
- `HEALTH_CHECK_FAILED`
- `OPERATION_FAILED`
- `RECONCILIATION_REQUIRED`

New codes require an additive schema revision. Changing the meaning of an
existing field or code requires a new envelope version.
