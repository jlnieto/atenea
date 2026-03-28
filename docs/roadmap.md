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
- resolve session
- read session
- aggregated session view
- aggregated conversation view
- create turn
- execute Codex turn inside the session
- project-level `defaultBaseBranch`
- session-owned `workspaceBranch`
- strict session branch recovery policy
- reuse `externalThreadId` across turns
- list turns
- list runs
- close session
- reconcile stale running runs on later reads

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
- `defaultBaseBranch` persisted on `Project`
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
- `POST /api/sessions/{sessionId}/close`
- descriptive `repoState` snapshot on session responses
- aggregated session and conversation views
- real Codex execution inside the session flow
- session-owned `workspaceBranch` with branch identity `atenea/session-{id}`
- `baseBranch` fallback order:
  - request value
  - project `defaultBaseBranch`
  - current repo branch
- strict branch preparation policy:
  - allow create / checkout of `workspaceBranch` only from `baseBranch` with clean worktree
  - allow reuse when already on `workspaceBranch`
  - block when the repo is on any third branch
- continuity through persisted `externalThreadId`
- stale `RUNNING` run reconciliation when session state is reloaded

### Block 4. Mixed project overview

Implemented:

- `GET /api/projects/overview`
- canonical `workSession` block per project:
  - current open session when it exists
  - otherwise latest session by `lastActivityAt`
- `legacy` block per project:
  - latest task
  - latest execution

Current conclusion:

- the backend already exposes a mixed overview that represents both orchestration models at once

## Open gaps that are real today

The main open gaps visible in the repository are now these:

### Gap 1. Canonical product surface is still split

The backend still exposes both:

- a legacy task-centered workflow
- a newer session-centered workflow

What is not yet fully settled in the repository:

- which surface is canonical for operator-facing flows
- which of the already-implemented session reads should anchor frontend flows:
  - base session read
  - session view
  - conversation view
- how much of the legacy block should remain prominent in future operator UX

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
- define whether project overview should remain mixed long-term or become session-first with legacy secondary
- implement the remaining session-first repository delivery flow described in `docs/worksession-target-flow.md`

## Next recommended phase

The next recommended phase, justified by the current repository, is:

## Session-First Delivery Workflow

Goals:

- keep documentation aligned with code and tests
- make `WorkSession` the canonical unit of real repository work
- absorb branch and PR workflow capability into the session-first model
- establish `conversation-view` as the primary operator-facing session contract
- define the path from open session to merged-and-reconciled repository state

Recommended outputs of this phase:

1. publish and PR workflow for `WorkSession`
2. pull request metadata and merge tracking on the session model
3. post-merge reconciliation and true session close semantics
4. explicit decision that frontend should anchor primarily on:
   - `GET /api/sessions/{id}/conversation-view`
   with `view` and base session reads kept as support surfaces
5. clarified role of mixed `GET /api/projects/overview` during coexistence
6. documentation aligned with the target flow defined in `docs/worksession-target-flow.md`

## What remains uncertain

The repository does not yet justify stronger claims than these:

- it does not define a finalized migration plan away from legacy task flows
- it does not define whether `TaskExecution` will be retired, reduced, or kept long-term
- it does not define the final frontend contract beyond the fact that `WorkSession` is the intended future-centered model

Those points should therefore remain explicit decisions, not assumptions.

## Summary

Current roadmap reading should be:

- legacy task orchestration: implemented and still active
- `WorkSession` conversational core plus session-owned branch setup: implemented and operational
- immediate next major step: session-first delivery workflow from session branch to PR, merge and repository reconciliation
- future planning beyond that: still requires explicit human decisions
