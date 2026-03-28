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
- `Project.defaultBaseBranch` may define the default base branch for new sessions

Persisted `WorkSession` state includes:

- `status`
- `title`
- `baseBranch`
- `workspaceBranch`
- `externalThreadId`
- `pullRequestUrl`
- `pullRequestStatus`
- `finalCommitSha`
- `openedAt`
- `lastActivityAt`
- `publishedAt`
- `closeBlockedState`
- `closeBlockedReason`
- `closeBlockedAction`
- `closeRetryable`
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
- `POST /api/projects/{projectId}/sessions/resolve`
- `POST /api/projects/{projectId}/sessions/resolve/view`
- `POST /api/projects/{projectId}/sessions/resolve/conversation-view`
- `GET /api/sessions/{sessionId}`
- `GET /api/sessions/{sessionId}/view`
- `GET /api/sessions/{sessionId}/conversation-view`
- `POST /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/runs`
- `POST /api/sessions/{sessionId}/publish`
- `POST /api/sessions/{sessionId}/pull-request/sync`
- `POST /api/sessions/{sessionId}/close`

Implemented response families:

- `WorkSessionResponse`
- `ResolveWorkSessionResponse`
- `WorkSessionViewResponse`
- `ResolveWorkSessionViewResponse`
- `WorkSessionConversationViewResponse`
- `ResolveWorkSessionConversationViewResponse`

### Current open-session behavior

When opening a session, Atenea currently:

- validates that the target `Project` exists
- validates `project.repoPath` through `WorkspaceRepositoryPathValidator`
- verifies that the repository is operational by resolving the current branch through `GitRepositoryService`
- sets `baseBranch` from `request.baseBranch` when provided
- otherwise uses `project.defaultBaseBranch` when present
- otherwise derives `baseBranch` from the repository current branch
- persists the session as `OPEN`
- derives `workspaceBranch` as `atenea/session-{sessionId}`
- prepares or recovers that `workspaceBranch` only when:
  - the repo is already on `workspaceBranch`
  - or the repo is on `baseBranch` and the worktree is clean
- blocks opening if the repo is on any third branch, requiring manual operator correction
- initializes:
  - `externalThreadId = null`
  - coherent `openedAt`
  - coherent `lastActivityAt`

### Current resolve-session behavior

Project-scoped resolve endpoints are implemented.

Current behavior:

- if an `OPEN` session already exists for the project:
  - prepare or recover its `workspaceBranch` using the same strict branch rules
  - return that session
  - do not create a new one
- if no `OPEN` session exists:
  - require `title`
  - open a new session using the same validation rules as `POST /api/projects/{projectId}/sessions`

Current response forms:

- `POST /api/projects/{projectId}/sessions/resolve`
  - returns `created = false` with the existing open session
  - or `created = true` with a newly opened session
- `POST /api/projects/{projectId}/sessions/resolve/view`
  - resolves the session and returns aggregated session view state
- `POST /api/projects/{projectId}/sessions/resolve/conversation-view`
  - resolves the session and returns conversation-ready aggregated state

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

## Aggregated session views

The backend currently exposes two aggregated read models for `WorkSession`.

### Session view

`GET /api/sessions/{sessionId}/view` returns a `WorkSessionViewResponse`.

Current contents:

- `session`
  - the base `WorkSessionResponse`
- `runInProgress`
  - derived from `session.repoState.runInProgress`
- `canCreateTurn`
  - true only when the session is operationally idle
- `latestRun`
  - latest persisted run for the session, if any
- `lastError`
  - latest failed run error summary, if any
- `lastAgentResponse`
  - latest succeeded run output summary, if any

This view is already intended as an operator-facing or frontend-facing aggregation.

### Conversation view

`GET /api/sessions/{sessionId}/conversation-view` returns a `WorkSessionConversationViewResponse`.

Current contents:

- `view`
  - the full `WorkSessionViewResponse`
- `recentTurns`
  - the latest visible conversation window
- `recentTurnLimit`
  - current fixed limit of `20`
- `historyTruncated`
  - whether older visible turns exist outside that window

This endpoint is currently the most conversation-ready payload in the backend.

## Reconciliation behavior

The current backend reconciles stale `RUNNING` runs when session state is loaded again.

Current behavior:

- session reads and session close paths trigger reconciliation through `AgentRunReconciliationService`
- if a run stayed `RUNNING` past the stale timeout window:
  - the run is marked `FAILED`
  - `finishedAt` is persisted
  - the session can return to an idle operational state

This behavior is already validated by integration tests and is part of the real operational model.

## Session close behavior

`POST /api/sessions/{sessionId}/close` is implemented.

Current rules:

- only an active session may enter close reconciliation
- closing first moves the session to `CLOSING`
- stale runs are reconciled before deciding whether close may continue
- close is blocked while a run is still `RUNNING`
- close is blocked when repository state is unsafe
- close is blocked when unpublished session commits still exist
- close is blocked when a published session pull request is not merged
- close only succeeds when Atenea can leave the repository:
  - on the project main/base branch
  - aligned with remote without local merge
  - with clean worktree
  - without local session branch
  - without remote session branch when it applies
- if close fails midway:
  - the session remains `CLOSING`
  - close-block diagnostics are persisted on the session
- successful close sets:
  - `status = CLOSED`
  - `closedAt`
  - updated `updatedAt`

Closed sessions do not accept new turns.

## Session publish and pull request behavior

The backend now implements session-first delivery behavior.

Implemented:

- `POST /api/sessions/{sessionId}/publish`
- `POST /api/sessions/{sessionId}/pull-request/sync`
- publish validation that blocks when there are no reviewable changes
- stage / commit / push through `GitRepositoryService`
- GitHub pull request creation
- pull request metadata persisted directly on `WorkSession`
- merge tracking through synchronized `pullRequestStatus`

Current persisted delivery fields are:

- `pullRequestUrl`
- `pullRequestStatus`
- `finalCommitSha`
- `publishedAt`

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
- each session owns a real `workspaceBranch`
- session branch preparation is allowed only from `baseBranch` with clean worktree or from the session `workspaceBranch` itself
- session branch preparation is blocked from any third branch
- turns require the session to be `OPEN`
- turns require an operational repository
- closing requires the session to be `OPEN`
- closing is blocked while a run is still `RUNNING`
- closing may leave the session in `CLOSING` until reconciliation can finish
- only one active session per project means:
  - `OPEN`
  - or `CLOSING`

## Current project defaults

`Project` now persists `defaultBaseBranch`.

Current meaning:

- it is the default base branch policy for new sessions on that project
- it is exposed in `ProjectResponse`
- the current frontend form should explain that leaving `baseBranch` empty on session creation uses this project default when present

Important nuance:

- `WorkSession.baseBranch` still remains persisted per session
- changing the project default later does not retroactively rewrite existing sessions

## Current error surface

From code and tests, the currently relevant error behavior is:

- `404`
  - project does not exist on `POST /api/projects/{projectId}/sessions`
  - project does not exist on:
    - `POST /api/projects/{projectId}/sessions/resolve`
    - `POST /api/projects/{projectId}/sessions/resolve/view`
    - `POST /api/projects/{projectId}/sessions/resolve/conversation-view`
  - session does not exist on:
    - `GET /api/sessions/{sessionId}`
    - `GET /api/sessions/{sessionId}/view`
    - `GET /api/sessions/{sessionId}/conversation-view`
    - `POST /api/sessions/{sessionId}/turns`
    - `GET /api/sessions/{sessionId}/turns`
    - `GET /api/sessions/{sessionId}/runs`
    - `POST /api/sessions/{sessionId}/close`
- `400`
  - request validation fails
  - or `project.repoPath` is invalid according to workspace path validation rules
  - or session `title` is missing on resolve when no open `WorkSession` exists
  - or turn message is blank
- `409`
  - a session `OPEN` already exists for the project
  - or a session `CLOSING` already exists for the project
  - a second run would start while another run is still `RUNNING`
  - the session is not `OPEN`
  - publish is blocked by session delivery rules
  - close is blocked by session reconciliation rules
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
- explicit publish-to-PR flow
- pull request metadata and merge-state visibility
- explicit session close with repository reconciliation
- project-scoped resolve semantics for open-or-create flows
- aggregated session and conversation views for frontend-oriented reads
- stale `RUNNING` run reconciliation on later state reads

## Remaining gaps after this phase

The main remaining gaps visible from the current repository are no longer the basics of `WorkSession Phase 1`.

The current gaps are instead about consolidation and product clarity:

- documentation alignment
- roadmap clarity after the now-functional session-first delivery workflow
- explicit coexistence rules between legacy task flow and session flow at product level
- definition of what should become canonical for frontend and operator workflows

## Summary

Current state is:

- `WorkSession` is no longer an early persistence-only slice
- it already supports open, resolve, aggregated reads, turn execution, conversational continuity, turns history, runs history, publish, PR sync and reconciled close
- the legacy `Task` / `TaskExecution` model still coexists and remains operational
- the next gap is not basic `WorkSession` implementation, but consolidation of operator contracts, documentation and migration strategy
