# Atenea WorkSession Target Flow

## Purpose

This document defines the near-term canonical product flow for Atenea.

Its purpose is to make the following explicit:

- what Atenea should optimize next
- what the canonical operator flow should be
- what is already implemented
- what is still open

This document should be used together with:

- `docs/atenea-core.md`
- `docs/atenea-core-development-operator-surface.md`
- `docs/roadmap.md`
- `docs/atenea-v1-architecture.md`
- `docs/worksession-phase1.md`
- `docs/mobile-full-operation.md`

## Product direction

Within the current repository, the next major `WorkSession` objective is:

- keep `WorkSession` as the canonical unit of repository work inside the `development` domain
- make that same contract operable end-to-end through `Atenea Core`
- make that same contract operable end-to-end from mobile over that top-level core surface

At system level, the broader architectural objective is now:

- introduce `Atenea Core` as the top-level conversational orchestrator above `WorkSession`

That means a session is not only:

- open conversation
- run Codex
- show turns

The intended product meaning of `WorkSession` remains:

- open a controlled line of work over a repository
- own the working branch for that line of work
- persist the full conversation and execution trace
- publish the resulting work through a pull request
- track the session until the repository is ready for the next one

But this document should now be read with one boundary in mind:

- `WorkSession` is the target workflow for `development`
- it is no longer the whole future system story by itself

## Canonical operator flow

The target happy path is this:

1. operator opens or resolves a `WorkSession`
2. Atenea validates repository safety
3. Atenea creates or reuses the session working branch
4. operator sends prompts to Atenea
5. Atenea sends each prompt to Codex
6. Atenea shows Codex responses
7. operator continues the conversation until the work is ready
8. operator publishes the session
9. Atenea:
   - stages repository changes
   - creates the commit
   - pushes the branch with upstream
   - creates the pull request
10. Atenea persists the resulting delivery metadata on the session
11. operator generates session deliverables when the work is commercially ready
12. operator reviews and approves the deliverables that should become the reporting and billing baseline
13. later, Atenea detects that the pull request was merged
14. operator closes the session
15. Atenea reconciles the repository back to the base branch and updates local state
16. the session ends fully closed and the project is ready for another session

## Scope of the current strong product slice

The current strong slice of Atenea includes the minimum needed to complete real work safely.

Included in this slice:

- session open or resolve
- controlled branch ownership at session level
- conversation loop with Codex
- safe publish flow:
  - stage
  - commit
  - push
  - pull request creation
- pull request status visibility
- session deliverables generation and approval
- structured pricing output for internal billing use
- post-merge repository reconciliation
- project ready for next session

## Current implementation status against target flow

### Already implemented

- `WorkSession` persistence
- `SessionTurn` persistence
- `AgentRun` persistence
- `Project.defaultBaseBranch`
- one open session per project
- one running run per session
- session turn execution with Codex
- session-owned `workspaceBranch`
- strict branch preparation from `baseBranch` or existing `workspaceBranch`
- continuity through `externalThreadId`
- session turns history
- session runs history
- session publish flow
- session pull request synchronization
- delivery metadata on the session model
- merge-aware close flow
- post-merge repository reconciliation
- close-block diagnostics on the session model
- aggregated reads:
  - session view
  - conversation view
- conversation-oriented action responses for:
  - resolve
  - create turn
  - publish
  - pull-request sync
  - close
- session-first project overview
- stale running-run reconciliation
- generated and approved session deliverables
- deliverable history by type
- approved pricing read model by session
- approved pricing read model by project

### Still to consolidate

- stronger end-to-end validation of publish, merge and reconciled close happy path
- clearer operator flows for blocked close recovery
- global commercial workflow above project-level approved pricing
- tighter documentation governance so architecture docs do not lag behind code

## Canonical frontend contract for the current `development` workflow

The canonical frontend and operator contract should be:

- `WorkSessionConversationViewResponse`

Reason:

- it is the richest current session read
- it already contains the session state needed for a conversation-first UI
- it can evolve without splitting the operator surface across multiple session endpoints

Supporting reads may still exist:

- `WorkSessionViewResponse`
- `WorkSessionResponse`

But they should be treated as support or lower-level reads, not as the primary product surface.

Primary session actions should also prefer conversation-oriented responses when exposed to the operator UI, so the main flow does not need to recompose state from lower-level payloads after publish, pull-request sync or close.

The current canonical frontend flow should therefore anchor on:

- `POST /api/projects/{projectId}/sessions/resolve/conversation-view`
- `GET /api/sessions/{sessionId}/conversation-view`
- `POST /api/sessions/{sessionId}/turns/conversation-view`
- `POST /api/sessions/{sessionId}/publish/conversation-view`
- `POST /api/sessions/{sessionId}/pull-request/sync/conversation-view`
- `POST /api/sessions/{sessionId}/close/conversation-view`

Supporting endpoints may still exist for lower-level reads, compatibility or operational tooling, but they should not be treated as the primary frontend contract.

## Deliverables and billing contract

The operator/frontend contract now also includes a deliverables workflow attached to the same `WorkSession`.

Implemented session-level contract:

- `GET /api/sessions/{sessionId}/deliverables`
- `GET /api/sessions/{sessionId}/deliverables/approved`
- `GET /api/sessions/{sessionId}/deliverables/types/{type}/history`
- `GET /api/sessions/{sessionId}/deliverables/{deliverableId}`
- `POST /api/sessions/{sessionId}/deliverables/{type}/generate`
- `POST /api/sessions/{sessionId}/deliverables/{deliverableId}/approve`

Implemented pricing-specific contract:

- `GET /api/sessions/{sessionId}/deliverables/price-estimate/approved-summary`
- `GET /api/projects/{projectId}/approved-price-estimates`
- `POST /api/sessions/{sessionId}/deliverables/{deliverableId}/billing/mark-billed`
- `GET /api/billing/queue`
- `GET /api/billing/queue/summary`

Current operator meaning:

- deliverables are generated explicitly, not implicitly during `close`
- versions remain auditable
- approval marks the commercial baseline to use
- `PRICE_ESTIMATE` is available both as Markdown and as structured JSON-backed summaries
- approved pricing now persists minimal commercial state:
  - `READY`
  - `BILLED`
- `billingReference` and `billedAt` are stored once the approved baseline is marked billed
- project-level pricing reads are already usable as a pre-billing consultation surface and lightweight billing tracking surface
- a first global billing queue already exists as a cross-project operator read model

## Close-block guidance

When `close` cannot complete, the operator-facing contract should rely on these fields from the session payload or the `409` API error:

- `closeBlockedState`
- `closeBlockedReason`
- `closeBlockedAction`
- `closeRetryable`

The intended operator meaning is:

- `closeBlockedState`
  - stable machine-readable category for UI decisions and diagnostics
- `closeBlockedReason`
  - short explanation of why close stopped
- `closeBlockedAction`
  - next operator step to unblock or recover
- `closeRetryable`
  - whether retrying close is expected after resolving the reported condition

Typical close-block categories currently exposed by the backend include:

- `running_run`
- `dirty_worktree`
- `pull_request_not_merged`
- `unexpected_branch`
- `unpublished_commits`
- `repo_unavailable`

Operator/frontend UX should therefore:

- surface `closeBlockedReason` prominently
- show `closeBlockedAction` as the immediate next step
- distinguish retryable blocks from manual recovery cases
- keep the session visible as `CLOSING` until reconciliation can finish

## Required service and product consolidation

The next major work should likely focus on:

- keeping `WorkSession` clearly positioned as the `development` workflow under `Atenea Core`
- expanding `Atenea Core` so it can operate project status, project selection and the full `WorkSession` lifecycle
- frontend/operator consolidation around `conversation-view`
- stronger test coverage for publish, merge detection, remote branch cleanup and reconciled close
- explicit operator guidance for close-block states and manual recovery paths
- continued documentation alignment around the session-first model
- decide the next commercial surface above project pricing baselines:
  - global billing queue
  - billing aggregation across projects
  - richer invoicing workflow beyond baseline `billingReference`
- mobile full operation over the same session-first contract:
  - mobile inbox/navigation
  - mobile-safe async updates
  - mobile operator action flows

## Invariants for the target flow

The target product flow should keep these invariants:

- only one active work line per project at a time
- a session owns its working branch
- a session may only recover its workspace branch from its own `workspaceBranch` or from its `baseBranch` with a clean worktree
- publish is blocked when repository state is unsafe
- a session cannot be finalized while execution is still running
- post-merge reconciliation must leave the repo on the base branch in a clean state
- the project must be ready for a new session only after reconciliation succeeds

## Historical note

The former `Task` / `TaskExecution` workflow is no longer part of the runtime backend.

If historical branch-workflow context is needed, use:

- `docs/task-branch-workflow.md`

That document should be treated as background history, not as current product contract.

## What should be asked in future repo reviews

When reviewing Atenea progress in future sessions, the key questions should be:

1. does `Atenea Core` already exist as a real runtime layer above direct `WorkSession` usage
2. is `WorkSession` clearly positioned as the `development` workflow under that core
3. does the frontend contract already use `conversation-view` as the primary development surface
4. can a session go from open to merged-and-reconciled without manual backend workaround
5. are blocked close scenarios understandable and actionable for operators
6. does the documentation still match code and tests

## Summary

The target near-term `development` product is:

- a session-first repository delivery workflow

In short:

- `WorkSession` is already the root of branch, conversation, publish and reconciliation in backend terms
- `Atenea Core` is the next architectural layer that should sit above that workflow
- the next milestone for this document's scope is to consolidate the `development` workflow as the canonical operator and frontend surface under that future core
