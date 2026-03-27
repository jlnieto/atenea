# WorkSession Phase 1

## Purpose

This document records the real backend state of the `WorkSession` model as implemented today.

The file name remains `worksession-phase1.md` because the current implementation corresponds to the first complete vertical slice of the new conversational core.

Current precise meaning:

- `WorkSession Phase 1` is functionally implemented in the backend
- the legacy `Task` / `TaskExecution` flow still exists and remains operational
- Atenea is still in coexistence mode, not in full migration completion

## Current architectural position

The backend currently exposes two real orchestration models:

- legacy model:
  - `Project`
  - `Task`
  - `TaskExecution`
  - task-owned branch workflow
  - review / PR / close lifecycle
- new conversational model:
  - `WorkSession`
  - `SessionTurn`
  - `AgentRun`

The new product direction is centered on `WorkSession`, but the legacy task flow is still implemented and must not be ignored.

## Implemented in code today

### Persistence model

Implemented tables:

- `work_session`
- `session_turn`
- `agent_run`

Implemented constraints:

- only one `OPEN` `WorkSession` per `Project`
- only one `RUNNING` `AgentRun` per `WorkSession`

Persisted `WorkSession` state includes:

- `status`
- `title`
- `baseBranch`
- `workspaceBranch`
- `externalThreadId`
- `openedAt`
- `lastActivityAt`
- `closedAt`

Persisted `SessionTurn` state includes:

- `sessionId`
- `actor`
- `messageText`
- `internal`
- `createdAt`

Persisted `AgentRun` state includes:

- `sessionId`
- `originTurnId`
- `resultTurnId`
- `status`
- `targetRepoPath`
- `externalTurnId`
- `startedAt`
- `finishedAt`
- `outputSummary`
- `errorSummary`

### Current API surface

Implemented endpoints:

- `POST /api/projects/{projectId}/sessions`
- `GET /api/sessions/{sessionId}`
- `POST /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/runs`
- `POST /api/sessions/{sessionId}/close`

### Current open-session behavior

When opening a session, Atenea currently:

- validates that the target `Project` exists
- validates `project.repoPath` through `WorkspaceRepositoryPathValidator`
- verifies that the repository is operational by resolving the current branch through `GitRepositoryService`
- sets `baseBranch` from `request.baseBranch` when provided
- otherwise derives `baseBranch` from the repository current branch
- persists the session as `OPEN`
- initializes:
  - `workspaceBranch = null`
  - `externalThreadId = null`
  - coherent `openedAt`
  - coherent `lastActivityAt`

### Current turn execution flow

When creating a turn on an open session, Atenea currently:

1. validates that the session exists and is `OPEN`
2. validates that the project repository remains operational
3. persists an operator-visible `SessionTurn` with actor `OPERATOR`
4. creates a `RUNNING` `AgentRun` linked to the operator turn
5. calls Codex through `SessionCodexOrchestrator`
6. reuses `session.externalThreadId` when present
7. persists execution progress:
   - updates `WorkSession.externalThreadId`
   - updates `AgentRun.externalTurnId`
8. on success:
   - persists a visible `SessionTurn` with actor `CODEX`
   - marks the run as `SUCCEEDED`
   - links the run to the resulting Codex turn
9. on failure:
   - marks the run as `FAILED`
   - keeps the persisted operator turn and run trace

This means the current `WorkSession` flow is already conversational and already integrated with Codex execution.

## Continuity model

Thread continuity is implemented in the current backend.

Current behavior:

- the first turn starts without `externalThreadId`
- after Codex returns a thread id, Atenea persists it on `WorkSession`
- the next turn sends that existing thread id back to Codex
- the same `externalThreadId` is therefore reused across turns in the same session
- each run still keeps its own `externalTurnId`

The implemented continuity root is:

- session continuity -> `WorkSession.externalThreadId`
- per-run traceability -> `AgentRun.externalTurnId`

## Listing behavior

### Session turns

`GET /api/sessions/{sessionId}/turns` returns the visible conversation history.

Current rules:

- turns are ordered by `createdAt` ascending
- internal technical turns are filtered out
- operator and Codex turns are exposed

This means the public conversation history is not polluted by internal technical markers used to support run tracking.

### Session runs

`GET /api/sessions/{sessionId}/runs` returns the persisted run history for the session.

Current rules:

- runs are ordered by `createdAt` ascending
- the response includes origin turn, result turn, status, repo path and external turn id

## Session close behavior

`POST /api/sessions/{sessionId}/close` is implemented.

Current rules:

- only an `OPEN` session may be closed
- a session cannot be closed while a run is still `RUNNING`
- closing a session sets:
  - `status = CLOSED`
  - `closedAt`
  - updated `updatedAt`

Closed sessions do not accept new turns.

## Descriptive session snapshot

`WorkSessionResponse` includes a descriptive repository snapshot under `repoState`:

- `repoValid`
- `workingTreeClean`
- `currentBranch`
- `runInProgress`

Current meaning:

- the snapshot is descriptive, not a workflow engine
- it does not expose legacy task-derived fields such as:
  - `nextAction`
  - `recoveryAction`
  - `blockingReason`
  - `launchReady`
  - review / PR state

Current behavior if the repository becomes invalid after the session was opened:

- `GET /api/sessions/{id}` still returns the session
- `repoState.repoValid = false`
- `repoState.workingTreeClean = false`
- `repoState.currentBranch = null`

`runInProgress` is derived from `agent_run.status = RUNNING`.

It does not depend on legacy `TaskExecution`.

## Current restrictions and invariants

The following invariants are currently enforced:

- only one `OPEN` session per project
- only one `RUNNING` run per session
- turns require the session to be `OPEN`
- turns require an operational repository
- closing requires the session to be `OPEN`
- closing is blocked while a run is still `RUNNING`

## Current error surface

From code and tests, the currently relevant error behavior is:

- `404`
  - project does not exist on `POST /api/projects/{projectId}/sessions`
  - session does not exist on:
    - `GET /api/sessions/{sessionId}`
    - `POST /api/sessions/{sessionId}/turns`
    - `GET /api/sessions/{sessionId}/turns`
    - `GET /api/sessions/{sessionId}/runs`
    - `POST /api/sessions/{sessionId}/close`
- `400`
  - request validation fails
  - or `project.repoPath` is invalid according to workspace path validation rules
  - or turn message is blank
- `409`
  - a session `OPEN` already exists for the project
  - a second run would start while another run is still `RUNNING`
  - the session is not `OPEN`
- `422`
  - the repository path is valid but not operational for opening a session or executing a turn
- `502`
  - Codex execution fails during session turn execution

## What this phase validates today

The currently implemented `WorkSession` slice now validates all of the following:

- a live session rooted in `WorkSession`
- separation between:
  - session state
  - conversation turns
  - execution runs
  - repository snapshot state
- real Codex execution inside the session flow
- conversational continuity through reused external thread ids
- explicit session close

## Remaining gaps after this phase

The main remaining gaps visible from the current repository are no longer the basics of `WorkSession Phase 1`.

The current gaps are instead about consolidation and product clarity:

- documentation alignment
- roadmap clarity after the now-functional `WorkSession` core
- explicit coexistence rules between legacy task flow and session flow at product level
- definition of what should become canonical for frontend and operator workflows

## Summary

Current state is:

- `WorkSession` is no longer an early persistence-only slice
- it already supports open, turn execution, conversational continuity, turns history, runs history and close
- the legacy `Task` / `TaskExecution` model still coexists and remains operational
- the next gap is not basic `WorkSession` implementation, but consolidation of product contracts, documentation and migration strategy
