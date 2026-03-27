# Atenea V1 Architecture

## Purpose

This document defines the architectural direction that is guiding Atenea and distinguishes clearly between:

- the current backend architecture that is already implemented
- the legacy workflow that still exists and remains operational
- the target product direction that should guide future changes

The core architectural decision remains unchanged:

- the future product core must revolve around `WorkSession`
- `Task` remains implemented and operational, but it is legacy workflow for the future product direction
- `Task` must not evolve linearly into `WorkSession`

## System role

Atenea is a remote orchestration backend for repository work on this VPS.

That means:

- Atenea operates on registered repositories
- Atenea protects repository state
- Atenea coordinates Codex execution
- Atenea persists operational and conversational state
- Atenea should increasingly remove the need for the operator to work directly in the shell for normal flows

This system is no longer best understood only as:

- create task
- launch code change
- move to review
- create PR
- close

That legacy flow still exists in code, but it is no longer the intended architectural center of the product.

## Architectural direction vs current implementation

### Direction that remains valid

The direction that continues to make architectural sense is:

- `Project` remains the stable repository anchor
- `WorkSession` should be the future root of operator-facing workflows
- `SessionTurn` should remain the persisted conversation history of a session
- `AgentRun` should remain the persisted trace of a concrete Codex execution inside a session
- the system should keep session state, conversation state, execution state and repository state clearly separated

### Current backend reality

The current backend is already in coexistence mode, not in a pre-conversational stage.

Today the repository contains two real orchestration surfaces:

- legacy surface:
  - `Project`
  - `Task`
  - `TaskExecution`
  - task-owned branch workflow
  - review / PR / close flow
- newer conversational surface:
  - `WorkSession`
  - `SessionTurn`
  - `AgentRun`
  - open session
  - read session
  - create turn
  - execute Codex inside the session
  - reuse external thread id across turns
  - list turns
  - list runs
  - close session

## Current implemented backend architecture

### Stable platform and repository model

Already implemented and still valid:

- `Project` with validated `repoPath`
- workspace-root validation through platform configuration
- Git inspection and branch operations through `GitRepositoryService`
- Codex App Server integration
- GitHub integration for the legacy task workflow

### Legacy orchestration model still present

Still implemented today:

- `Project`
- `Task`
- `TaskExecution`
- task-owned branch workflow
- review / PR / close flow
- derived operational signals on tasks and execution listings

This legacy model is still functional and must continue to work while the new core is introduced.

It is documented separately in [`docs/task-branch-workflow.md`](./task-branch-workflow.md).

### Current WorkSession architecture

Implemented today in the backend:

#### Persistence and invariants

New persistence model:

- `work_session`
- `session_turn`
- `agent_run`

Implemented constraints:

- only one `OPEN` `WorkSession` per `Project`
- only one `RUNNING` `AgentRun` per `WorkSession`

#### Session lifecycle API

Implemented endpoints:

- `POST /api/projects/{projectId}/sessions`
- `GET /api/sessions/{sessionId}`
- `POST /api/sessions/{sessionId}/close`

Implemented behavior:

- validate `Project` existence
- validate that `project.repoPath` is operational
- set `baseBranch` from request when provided
- otherwise derive `baseBranch` from the repository current branch
- persist:
  - `status = OPEN`
  - `workspaceBranch = null`
  - `externalThreadId = null`
  - coherent `openedAt` / `lastActivityAt`
- block close when the session is not `OPEN`
- block close while a run is still `RUNNING`

#### Conversation and run API

Implemented endpoints:

- `POST /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/runs`

Implemented behavior:

- persist operator-visible turns with actor `OPERATOR`
- execute Codex for the turn through the session flow
- persist Codex-visible turns with actor `CODEX`
- create and update `AgentRun`
- list visible conversation turns in chronological order
- list persisted runs in chronological order
- filter internal technical turns out of the operator-visible history

#### Thread continuity

Thread continuity is already implemented in the current backend.

Current behavior:

- the session stores `externalThreadId`
- the first turn may create the external thread
- later turns reuse that same external thread id
- each run persists its own `externalTurnId`

#### Descriptive session snapshot

`WorkSessionResponse` includes a minimal descriptive repository snapshot:

- `repoValid`
- `workingTreeClean`
- `currentBranch`
- `runInProgress`

Important constraint:

- this snapshot is descriptive only
- it does not expose workflow advice such as `nextAction`, `recoveryAction`, `blockingReason`, `launchReady`, review state or PR state

Important current behavior:

- `GET /api/sessions/{id}` does not fail if the repository became non-operational after the session was opened
- in that case it returns:
  - `repoValid = false`
  - `workingTreeClean = false`
  - `currentBranch = null`

## Domain model in transition

## `Project`

`Project` remains the stable anchor for repository work.

Current responsibility:

- identify a repository Atenea can operate on
- persist canonical `repoPath`

This part of the model remains reusable in both legacy and newer flows.

## `Task` and `TaskExecution`

Current role in code:

- root of the legacy orchestration model
- anchor for branch lifecycle, PR metadata, review outcome and execution history
- current source for operational guidance in task and execution responses

Architectural status:

- still implemented
- still supported
- legacy for the future product core

Important decision:

- `Task` must not be stretched into “session with extra fields”
- `TaskExecution` must not be treated as the future conversational run root

Current caution:

- `TaskExecution` still matters operationally today
- it still appears in legacy APIs and overview-oriented services
- it cannot be ignored while the system remains in coexistence mode

## `WorkSession`

Architectural role:

- root of the newer conversational workflow
- line of work opened over a `Project`
- owner of session-level continuity
- owner of session-level operational state

Current implementation status:

- persistence implemented
- open/read/close API implemented
- turn execution implemented
- thread continuity implemented
- descriptive operational snapshot implemented
- turns history implemented
- runs history implemented

`WorkSession` should therefore no longer be described as embryonic or persistence-only.

## `SessionTurn`

Architectural role:

- persist conversation history for the session

Current implementation status:

- persistence exists
- public API flow uses it
- visible operator and Codex turns are returned through session history
- internal technical turns also exist, but are filtered from the public history

## `AgentRun`

Architectural role:

- trace one Codex execution inside a `WorkSession`

Current implementation status:

- persistence exists
- public and service flows use it
- one running run per session is enforced
- each run persists execution status and external turn traceability

## Codex App Server in the current architecture

Codex App Server integration is not only a future architectural dependency. It is already part of the implemented backend.

Current role:

- Atenea opens or reuses external Codex threads
- Atenea starts turns against Codex App Server
- Atenea persists external thread continuity at session level
- Atenea persists external turn traceability at run level
- Atenea stores summarized output or error state on runs

Architectural meaning:

- external Codex execution is already part of the current `WorkSession` runtime model
- `AgentRun` is the internal trace of that execution
- `SessionTurn` is the conversation-facing trace of that execution

## Core architectural decisions

### 1. `WorkSession` is the future root

The future operator flow should be:

- open a session on a project
- inspect
- ask
- refine
- implement
- summarize
- close when the operator decides

Not:

- create task
- launch
- force workflow through review / PR / close

### 2. `Task` and `WorkSession` must coexist temporarily

The backend currently contains both:

- a legacy task workflow
- a newer session workflow

This coexistence is intentional during transition.

### 3. Snapshot, not workflow advice

The `WorkSession` operational surface remains intentionally descriptive.

Current `WorkSession` snapshot avoids:

- workflow advice
- legacy review semantics
- legacy PR semantics
- task-style closure guidance

### 4. Session, conversation, run and repo state must stay separated

The architecture should keep these concerns separated:

- session state
- conversation turns
- execution runs
- repository state

The current backend already implements that separation at the main model level.

## What is still legacy

The following remain legacy-centered in the current backend:

- task launch / relaunch
- task branch lifecycle
- review-pending
- PR synchronization and creation
- review outcome
- branch closure
- task-derived workflow guidance

Those features are real and implemented, but they no longer define the target shape of Atenea.

## What remains transitional or open

The architecture still contains real open questions.

Current transition points include:

- how long legacy `Task` / `TaskExecution` remains first-class
- which API surface should become canonical for future frontend work
- how higher-level product views should represent coexistence between legacy executions and newer sessions
- what should count as the stable product contract once coexistence ends

These are still architectural and product decisions, not settled facts.

## What the backend is not claiming yet

The backend should still avoid stronger claims than the repository supports.

It does not yet define:

- a completed migration away from the legacy task model
- PR or delivery artifacts attached to `WorkSession`
- a final replacement plan for all legacy task-centered UI surfaces
- a final governance model for overview or operator-facing canonical views
