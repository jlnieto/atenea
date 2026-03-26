# Atenea V1 Architecture

## Purpose

This document defines the architectural direction that is now guiding Atenea.

It distinguishes clearly between:

- what is already implemented in the backend
- what still exists as legacy workflow
- what the new product core is expected to become

The main clarification is no longer optional:

- the future core of Atenea must revolve around `WorkSession`
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

This is no longer best understood as only:

- create task
- launch code change
- move to review
- create PR
- close

That legacy flow still exists in code, but it is no longer the intended center of the product.

## Current implemented state

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

### New WorkSession slice already implemented

Implemented today in the backend:

#### Slice 1

New persistence model:

- `work_session`
- `session_turn`
- `agent_run`

Implemented constraints:

- only one `OPEN` `WorkSession` per `Project`
- only one `RUNNING` `AgentRun` per `WorkSession`

#### Slice 2

New API for basic session lifecycle start and read:

- `POST /api/projects/{projectId}/sessions`
- `GET /api/sessions/{sessionId}`

Behavior already implemented:

- validate `Project` existence
- validate that `project.repoPath` is operational
- set `baseBranch` from request when provided
- otherwise derive `baseBranch` from the repository current branch
- persist:
  - `status = OPEN`
  - `workspaceBranch = null`
  - `externalThreadId = null`
  - coherent `openedAt` / `lastActivityAt`

#### Slice 3

`WorkSessionResponse` now includes a minimal descriptive snapshot:

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

`Project` remains valid as the stable anchor for repository work.

Current responsibility:

- identify a repository Atenea can operate on
- persist canonical `repoPath`

This part of the model remains reusable in both legacy and new flows.

## `Task` and `TaskExecution`

Current role in code:

- root of the legacy orchestration model
- anchor for branch lifecycle, PR metadata, review outcome and execution history

Architectural status:

- still implemented
- still supported
- legacy for the future product core

Important decision:

- `Task` must not be stretched into “session with extra fields”
- `TaskExecution` must not be treated as the future conversational run root

## `WorkSession`

Architectural role:

- new root of the future operator workflow
- line of work opened over a `Project`
- owner of conversational continuity
- owner of session-level operational state

Current implementation status:

- persistence implemented
- open/read API implemented
- descriptive operational snapshot implemented
- turn execution not implemented yet
- thread continuity not implemented yet
- close session not implemented yet

## `SessionTurn`

Architectural role:

- persist conversation history for the session

Current implementation status:

- persistence exists
- no API or service flow uses it yet

## `AgentRun`

Architectural role:

- trace one Codex execution inside a `WorkSession`

Current implementation status:

- persistence exists
- no API or service flow uses it yet

## Core architectural decisions

### 1. `WorkSession` is the future root

The future operator flow must be:

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
- an emerging session workflow

This is intentional during transition.

### 3. Snapshot, not workflow advice

The new `WorkSession` operational surface must remain descriptive for now.

Current `WorkSession` snapshot intentionally avoids:

- workflow advice
- closure semantics
- review semantics
- PR semantics

### 4. Conversation, run and repo state must stay separated

The future core should separate:

- session state
- conversation turns
- execution runs
- repo operations

Slices 1 to 3 only establish the beginning of that separation.

## Phase 1 status

Phase 1 of the `WorkSession` reconduction is not complete yet.

Completed:

- Slice 1: persistence
- Slice 2: open / read session
- Slice 3: descriptive operational snapshot

Pending inside Phase 1:

- send turns to Codex inside a session
- persist `SessionTurn` through the API flow
- persist `AgentRun` through the API flow
- reuse `externalThreadId` across turns
- list turns
- list runs
- explicit session close

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

## What the backend is not claiming yet

The backend does **not** yet implement in the new session model:

- turn execution against Codex
- continuity of external thread between turns
- session close endpoint
- PR or delivery artifacts attached to sessions
- replacement of the legacy task UI surface

Those remain future slices, not current capabilities.
