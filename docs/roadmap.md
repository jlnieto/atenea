# Atenea Roadmap

## Purpose

This document tracks the current product state based on the repository as it exists today.

It distinguishes clearly between:

- what is implemented and validated
- what remains legacy but active
- what gaps are still open
- what the next recommended block of work is

## Current Version State

Current formal version markers in the repository:

- application artifact version: `0.0.1-SNAPSHOT`
- product stage label still used in documentation: `V0`

This still reads as `V0` because the backend is not a fully consolidated product surface yet.

The reason is not lack of backend capability in `WorkSession`, but coexistence and incomplete consolidation:

- legacy task workflow is still present and operational
- the newer conversational `WorkSession` workflow is also operational
- documentation and product guidance still need consolidation

## Current validated system state

The repository currently contains two real orchestration surfaces.

### Legacy operational surface

Still implemented and active:

- `Project`
- `Task`
- `TaskExecution`
- task branch lifecycle
- review / PR / close workflow
- GitHub-backed pull request creation and synchronization
- derived operational guidance on task and execution responses

This remains the current legacy workflow.

### New conversational surface

Implemented and validated:

- `WorkSession`
- `SessionTurn`
- `AgentRun`
- open session
- read session
- create turn
- execute Codex turn inside the session
- reuse `externalThreadId` across turns
- list turns
- list runs
- close session

This is the implemented conversational core that now represents the intended product direction.

## Completed blocks

### Block 1. Repository and platform foundation

Implemented:

- `Project` with validated `repoPath`
- platform-level `workspaceRoot`
- repository validation through `WorkspaceRepositoryPathValidator`
- Git inspection and branch operations through `GitRepositoryService`
- Flyway-backed persistence foundation
- Codex App Server client integration

### Block 2. Legacy task execution model

Implemented:

- `Task`
- `TaskExecution`
- task launch / relaunch
- branch ownership and project-level locking
- launch-readiness heuristics
- review-pending flow
- pull request metadata and GitHub integration
- explicit review outcome
- strict branch closure rules
- operational guidance fields such as:
  - `projectBlocked`
  - `hasReviewableChanges`
  - `lastExecutionFailed`
  - `launchReady`
  - `launchReadinessReason`
  - `blockingReason`
  - `nextAction`
  - `recoveryAction`

### Block 3. WorkSession Phase 1

Implemented:

- persistence for `work_session`, `session_turn`, `agent_run`
- one `OPEN` session per project
- one `RUNNING` run per session
- `POST /api/projects/{projectId}/sessions`
- `GET /api/sessions/{sessionId}`
- `POST /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/turns`
- `GET /api/sessions/{sessionId}/runs`
- `POST /api/sessions/{sessionId}/close`
- descriptive `repoState` snapshot on session responses
- real Codex execution inside the session flow
- continuity through persisted `externalThreadId`

Current conclusion:

- `WorkSession Phase 1` is functionally complete in the backend

## Open gaps that are real today

The main open gaps visible in the repository are now these:

### Gap 1. Canonical product surface is still split

The backend still exposes both:

- a legacy task-centered workflow
- a newer session-centered workflow

What is not yet fully settled in the repository:

- which surface is canonical for operator-facing flows
- how project overview and other higher-level views should represent `WorkSession` versus legacy `TaskExecution`

### Gap 2. Documentation and governance are behind the implementation

The repository recently contained documentation that still described major `WorkSession` capabilities as pending even though they were already implemented.

That means documentation governance is a real gap, not an editorial detail.

### Gap 3. Product-next work after the now-functional session core is not yet explicitly defined

The code shows a working `WorkSession` core, but the repository does not yet define a precise post-Phase-1 product plan for:

- frontend alignment
- coexistence rules
- operator workflow simplification
- eventual de-emphasis or retirement path for legacy task-centered flows

## Pending real work

Based strictly on the repository, the pending work is not:

- basic `WorkSession` turn execution
- basic thread continuity
- basic turns/runs history
- basic session close

Those are already implemented.

The pending real work is instead:

- consolidate documentation around the true current state
- make the coexistence rules between legacy and new model explicit
- define which API surface should drive the next operator or frontend workflows
- revisit higher-level project views that still summarize legacy execution state only

## Next recommended phase

The next recommended phase, justified by the current repository, is:

## Consolidation And Governance

Goals:

- align documentation with code and tests
- establish a local canonical guide for agents
- make legacy-versus-new-model boundaries explicit
- identify which surface should be treated as canonical for future product work

Recommended outputs of this phase:

1. documentation aligned with implemented `WorkSession`
2. explicit coexistence guidance for `Task` / `TaskExecution` and `WorkSession` / `SessionTurn` / `AgentRun`
3. local agent guidance in the repository itself
4. clarified roadmap after the completion of the current `WorkSession` slice

## What remains uncertain

The repository does not yet justify stronger claims than these:

- it does not define a finalized migration plan away from legacy task flows
- it does not define whether `TaskExecution` will be retired, reduced, or kept long-term
- it does not define the final frontend contract beyond the fact that `WorkSession` is the intended future-centered model

Those points should therefore remain explicit decisions, not assumptions.

## Summary

Current roadmap reading should be:

- legacy task orchestration: implemented and still active
- `WorkSession` conversational core: implemented and operational
- immediate need: consolidation, documentation alignment and product-surface clarity
- future planning beyond that: still requires explicit human decisions
