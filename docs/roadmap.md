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
- publish session to pull request
- synchronize pull request state
- delivery metadata on the session model
- strong close semantics with repository reconciliation
- `CLOSING` transitional state with persisted close-block diagnostics
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
- `POST /api/sessions/{sessionId}/publish`
- `POST /api/sessions/{sessionId}/pull-request/sync`
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
- persisted delivery metadata:
  - `pullRequestUrl`
  - `pullRequestStatus`
  - `finalCommitSha`
  - `publishedAt`
- strong close lifecycle:
  - `OPEN`
  - `CLOSING`
  - `CLOSED`
- persisted close-block metadata:
  - `closeBlockedState`
  - `closeBlockedReason`
  - `closeBlockedAction`
  - `closeRetryable`

### Block 5. Session-first delivery workflow

Implemented:

- `WorkSession` publish flow:
  - stage
  - commit
  - push
  - GitHub pull request creation
- pull request metadata persisted directly on the session
- pull request state synchronization
- merge-aware close flow
- post-merge repository reconciliation to the project main/base branch
- local session branch deletion on successful close
- remote session branch deletion when it applies
- blocking close semantics when repository or GitHub state is unsafe
- operator-facing close diagnostics in:
  - session reads
  - project overview
  - `409` close responses

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

### Gap 3. Session-first operator contract is not yet fully consolidated

The code now shows a working session-first delivery workflow in the backend, but the repository still does not fully settle:

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
- harden the now-implemented session-first repository delivery flow as the primary product contract

## Next recommended phase

The next recommended phase, justified by the current repository, is:

## Session-First Operator Consolidation

Goals:

- keep documentation aligned with code and tests
- make `WorkSession` the canonical operator-facing unit of real repository work
- establish `conversation-view` as the primary operator-facing session contract
- clarify the long-term role of mixed project overview during coexistence
- formalize how frontend and operator UX consume publish, merge and close-block states

Recommended outputs of this phase:

1. explicit decision that frontend should anchor primarily on:
   - `GET /api/sessions/{id}/conversation-view`
   with `view` and base session reads kept as support surfaces
2. clarified role of mixed `GET /api/projects/overview` during coexistence
3. documentation aligned with the target flow defined in `docs/worksession-target-flow.md`
4. stronger end-to-end validation of:
   - publish
   - merge detection
   - remote branch cleanup
   - reconciled close
5. clearer operator guidance for blocked close and manual recovery paths

## What remains uncertain

The repository does not yet justify stronger claims than these:

- it does not define a finalized migration plan away from legacy task flows
- it does not define whether `TaskExecution` will be retired, reduced, or kept long-term
- it does not define the final frontend contract beyond the fact that `WorkSession` is the intended future-centered model

Those points should therefore remain explicit decisions, not assumptions.

## Summary

Current roadmap reading should be:

- legacy task orchestration: implemented and still active
- `WorkSession` conversational core plus session-first delivery workflow: implemented in backend
- immediate next major step: operator/frontend consolidation around the session-first model
- future planning beyond that: still requires explicit human decisions
