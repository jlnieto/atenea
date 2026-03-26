# Atenea Roadmap

## Purpose

This document tracks the current product direction and distinguishes:

- what is implemented today
- what is legacy but still active
- what is being built as the future core

## Current Version

Current formal version state:

- application artifact version: `0.0.1-SNAPSHOT`
- product stage: `V0`

This still counts as `V0` because the backend is in controlled architectural transition:

- the legacy task workflow is operational
- the new `WorkSession` core has started
- the future root of the product is decided
- but the new conversational session flow is not complete yet

## Current Product Direction

The main product shift is now explicit:

- Atenea should evolve toward a remote `WorkSession` orchestrator over repositories
- `Task` remains implemented, but is no longer the target product core
- the new frontend must not grow on top of `Task` endpoints

This means current backend state is transitional by design.

## Architectural Transition Model

### Legacy model still present

Still implemented and in use:

- `Project`
- `Task`
- `TaskExecution`
- task branch workflow
- review / PR / close path

This is the current operational legacy surface.

### New core under construction

Now introduced:

- `WorkSession`
- `SessionTurn`
- `AgentRun`

Current implemented slice of the new core:

- persistence is in place
- open/read session API is in place
- descriptive operational snapshot is in place

Not implemented yet in the new core:

- turn execution
- thread continuity
- list turns
- list runs
- close session

## WorkSession Phase 1

Phase 1 is the first vertical validation of the new model.

Its goal is to prove:

- a live session over a `Project`
- separation between session, run and repo state
- eventual conversational continuity with Codex

Current status note:

- Phase 1 has not yet validated real Codex turn execution inside the `WorkSession` flow
- real conversational continuity is still pending until the later slices that introduce turns and Codex execution

### Completed slices

#### Slice 1. Persistence

Implemented:

- `work_session`
- `session_turn`
- `agent_run`

Constraints implemented:

- one `OPEN` session per project
- one `RUNNING` run per session

#### Slice 2. Open and read session

Implemented endpoints:

- `POST /api/projects/{projectId}/sessions`
- `GET /api/sessions/{sessionId}`

Implemented behavior:

- validate project
- validate repoPath
- set `baseBranch` from request or current repo branch
- persist session as `OPEN`
- keep `workspaceBranch = null`
- keep `externalThreadId = null`

#### Slice 3. Descriptive session snapshot

Implemented on `WorkSessionResponse`:

- `repoValid`
- `workingTreeClean`
- `currentBranch`
- `runInProgress`

Important rule:

- this snapshot is descriptive only
- it is not a workflow engine

### Pending slices in Phase 1

Still pending:

1. create turns inside a session
2. execute Codex turns inside a session
3. reuse the same external thread between turns
4. list session turns
5. list session runs
6. close session explicitly

## Legacy roadmap status

The legacy task workflow is still functional, but it is no longer the roadmap center.

Its role during transition is:

- keep current operators unblocked
- preserve branch-safe repository work
- coexist while the `WorkSession` core becomes complete

It should not define the future web or mobile surface.

## Near-term target

The next meaningful product milestone is not “more task workflow”.

It is:

- complete `WorkSession Phase 1`
- prove real session continuity with Codex
- move the product center away from `Task`

## V1 exit direction

`V1` should not be declared by the mere existence of the legacy task flow.

The stable baseline should require at least:

1. a usable `WorkSession` core
2. clear coexistence rules between legacy task flow and new session flow
3. stable API contracts for the new frontend direction
4. documentation aligned with the real operating model

## Atenea Web direction

The first frontend aligned with the future product should target `WorkSession`, not `Task`.

That means the web surface should eventually revolve around:

- selecting a project
- opening a session
- consulting session state
- sending turns
- reviewing the evolving session

Not around:

- creating task
- forcing review/PR semantics from the first interaction

That frontend direction is still blocked until the remaining pending slices of `WorkSession Phase 1` are implemented.
