# WorkSession Phase 1

## Purpose

This document records the real backend state of the new `WorkSession` core after completion of:

- Slice 1
- Slice 2
- Slice 3

It is intentionally narrow:

- it describes what is already in code
- it identifies what still belongs to the legacy task workflow
- it lists what remains pending inside Phase 1

## Architectural meaning

The key product decision is now fixed:

- the future core of Atenea must revolve around `WorkSession`
- `Task` must not evolve linearly into `WorkSession`

This does **not** mean the legacy task flow disappeared.

Current backend reality is coexistence:

- legacy `Task` workflow still exists and remains operational
- new `WorkSession` core has started and is the direction for the future product

## Implemented in code today

### Slice 1. Persistence

Implemented:

- table `work_session`
- table `session_turn`
- table `agent_run`

Implemented constraints:

- only one `OPEN` `WorkSession` per `Project`
- only one `RUNNING` `AgentRun` per `WorkSession`

Current note:

- `session_turn` and `agent_run` persistence exists, but their API flow is not implemented yet

### Slice 2. Open and read session

Implemented endpoints:

- `POST /api/projects/{projectId}/sessions`
- `GET /api/sessions/{sessionId}`

Implemented behavior when opening a session:

- validate `Project` existence
- validate `project.repoPath` through the existing workspace path validator
- ensure the repository is operational
- set `baseBranch` from `request.baseBranch` when provided
- otherwise derive `baseBranch` from the repository current branch
- persist:
  - `status = OPEN`
  - `workspaceBranch = null`
  - `externalThreadId = null`
  - coherent `openedAt`
  - coherent `lastActivityAt`

Current precise meaning of "repository is operational" in Slice 2:

- `project.repoPath` is valid according to `WorkspaceRepositoryPathValidator`
- and Atenea can resolve the current repository branch through `GitRepositoryService.getCurrentBranch(...)`

Not implemented in Slice 2:

- turn creation
- run creation
- Codex execution
- thread continuity
- session close

### Slice 3. Descriptive operational snapshot

`WorkSessionResponse` now includes:

- `repoState.repoValid`
- `repoState.workingTreeClean`
- `repoState.currentBranch`
- `repoState.runInProgress`

Important decisions already implemented:

- the snapshot is descriptive, not prescriptive
- it does not expose:
  - `nextAction`
  - `recoveryAction`
  - `blockingReason`
  - `launchReady`
  - review-related state
  - PR-related state

Current behavior if the repository becomes invalid after the session was opened:

- `GET /api/sessions/{id}` still returns the session
- `repoState.repoValid = false`
- `repoState.workingTreeClean = false`
- `repoState.currentBranch = null`

`runInProgress` is derived from the new model only:

- it is based on `agent_run.status = RUNNING`
- it does not depend on legacy `TaskExecution`
- `runInProgress` already exists in the snapshot even though the new `WorkSession` API flow for creating or listing `AgentRun` is not implemented yet

## Current error surface for the implemented WorkSession API

Current behavior of the implemented endpoints:

- `404`
  - `Project` does not exist on `POST /api/projects/{projectId}/sessions`
  - `WorkSession` does not exist on `GET /api/sessions/{sessionId}`
- `409`
  - a session `OPEN` already exists for the target project
- `400`
  - request validation fails
  - or `project.repoPath` is invalid according to the existing repository path validation rules
- `422`
  - `project.repoPath` is valid
  - but the repository is still not operational for session opening

## What still belongs to the legacy model

The following are still implemented but remain outside `WorkSession Phase 1`:

- `Task`
- `TaskExecution`
- task launch / relaunch
- task branch lifecycle
- review / PR / close flow
- task-derived operational guidance

Those are real backend capabilities, but they are legacy relative to the future product core.

## What Phase 1 is trying to validate

Phase 1 is meant to validate:

- a live session rooted in `WorkSession`
- repository-oriented session state over a `Project`
- separation between:
  - session
  - conversation
  - execution run
  - repository state

Only the first part of that validation is implemented so far.

## Pending inside Phase 1

Still pending:

1. send turns to Codex inside a session
2. persist `SessionTurn` through the API flow
3. persist `AgentRun` through the API flow
4. reuse the same external thread between turns
5. list turns for a session
6. list runs for a session
7. close the session explicitly

Phase 1 has therefore **not** yet validated:

- real Codex turn execution inside `WorkSession`
- real conversational continuity through reused external thread ids

## Current API in the new model

Already available:

- `POST /api/projects/{projectId}/sessions`
- `GET /api/sessions/{sessionId}`

Not available yet:

- `POST /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/runs`
- session close endpoint

## Current operational boundaries

What the new `WorkSession` slice already reuses from the existing backend:

- `Project`
- `WorkspaceRepositoryPathValidator`
- `GitRepositoryService`

What it does not use:

- `TaskWorkflowService`
- `TaskExecutionService`
- `TaskExecutionReadinessService`
- `TaskOperationalStateService`
- `TaskOperationalStateResolver`

## Summary

Current state is:

- `WorkSession` has started in the backend
- its persistence and basic open/read API are already implemented
- its operational snapshot exists and is intentionally minimal
- the conversational core of Phase 1 is still pending

So Atenea is already in architectural transition, but not yet at the point where `WorkSession` can replace the legacy task flow.
