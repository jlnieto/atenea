# Atenea WorkSession Target Flow

## Purpose

This document defines the target near-term product flow for Atenea.

It is intentionally not a vague vision note.

Its purpose is to make the following explicit:

- what Atenea should become next
- what the canonical operator flow should be
- what is already implemented
- what is still missing
- how `WorkSession` and legacy `Task` / `TaskExecution` relate during the transition

This document should be used together with:

- `docs/roadmap.md`
- `docs/atenea-v1-architecture.md`
- `docs/worksession-phase1.md`
- `docs/task-branch-workflow.md`

## Product direction

The next major Atenea objective is:

- make `WorkSession` the canonical unit of real repository work

That means a session should no longer be only:

- open conversation
- run Codex
- show turns

The intended next product meaning of `WorkSession` is:

- open a controlled line of work over a repository
- own the working branch for that line of work
- persist the full conversation and execution trace
- publish the resulting work through a pull request
- track the session until the repository is ready for the next one

## Canonical operator flow

The target happy path is this:

1. operator opens or resolves a `WorkSession`
2. Atenea validates repository safety
3. Atenea creates or reuses the session working branch
4. operator sends prompts to Atenea
5. Atenea sends each prompt to Codex
6. Atenea shows Codex responses
7. operator continues the conversation until the work is ready
8. operator closes and publishes the session
9. Atenea:
   - stages repository changes
   - creates the commit
   - pushes the branch with upstream
   - creates the pull request
10. Atenea persists the resulting delivery metadata on the session
11. later, Atenea detects that the pull request was merged
12. Atenea reconciles the repository back to the base branch and updates local state
13. the session ends fully closed and the project is ready for another session

## Scope of the first powerful product slice

The first powerful slice of Atenea should include only the minimum needed to complete real work safely.

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
- post-merge repository reconciliation
- project ready for next session

Not required for this first slice:

- automatic ticket generation
- detailed customer-facing report generation
- pricing estimation
- advanced billing outputs

Those may be added later, but they are not the foundation of the session-first workflow.

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
- session close at model level
- aggregated reads:
  - session view
  - conversation view
- stale running-run reconciliation

### Implemented in legacy, not yet in `WorkSession`

- safe task branch creation and checkout
- project lock through active branch workflow
- review-pending transition
- pull request creation and synchronization
- branch closure after merged review flow
- derived operational guidance fields

### Not yet implemented in the target model

- publish-on-close flow for `WorkSession`
- session commit and push lifecycle
- PR metadata persisted directly as part of session delivery state
- post-merge session reconciliation to base branch
- project-ready-for-next-session end state in the `WorkSession` model

## Canonical frontend contract

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

## Required domain evolution

To reach the target flow, `WorkSession` must evolve from conversational session to delivery session.

Minimum domain additions expected:

- publish lifecycle state on the session
- pull request metadata on the session
- merge and reconciliation state on the session

Possible future fields may include:

- `workspaceBranch`
- `pullRequestUrl`
- `pullRequestStatus`
- `finalCommitSha`
- `publishedAt`
- `mergedAt`
- `reconciledAt`

These names are indicative, not yet final API commitments.

## Required service evolution

The next major backend additions should likely include:

- a session branch service
  - prepares or recovers the working branch for the session
- a session publish service
  - stages, commits, pushes and opens the pull request
- richer git operations in `GitRepositoryService`
  - add
  - commit
  - push
  - fetch
  - pull
- session delivery state resolver
  - exposes next action, blocking reason and publish readiness

## Invariants for the target flow

The target product flow should keep these invariants:

- only one active work line per project at a time
- a session owns its working branch
- a session may only recover its workspace branch from its own `workspaceBranch` or from its `baseBranch` with a clean worktree
- publish is blocked when repository state is unsafe
- a session cannot be finalized while execution is still running
- post-merge reconciliation must leave the repo on the base branch in a clean state
- the project must be ready for a new session only after reconciliation succeeds

## Relationship with legacy

`Task` / `TaskExecution` remain relevant until the target session-first slice is complete.

Current rule:

- legacy remains operational
- new product work should center on `WorkSession`
- useful branch and PR rules from legacy should be absorbed into the session-first model
- new frontend flows should not be built around legacy as the long-term foundation

Legacy can begin to be de-emphasized only when `WorkSession` supports:

- publish to PR
- merge tracking
- repository reconciliation

without needing task-centered workflow to finish the job.

## What should be asked in future repo reviews

When reviewing Atenea progress in future sessions, the key questions should be:

1. is `WorkSession` already the canonical delivery workflow
2. what parts of the target flow are implemented in `WorkSession`
3. what still depends on legacy `Task` / `TaskExecution`
4. does the frontend contract already use conversation-view as the primary surface
5. can a session go from open to merged-and-reconciled without falling back to legacy

## Summary

The target near-term product is not:

- just a conversational wrapper over Codex

The target near-term product is:

- a session-first repository delivery workflow

In short:

- `WorkSession` should become the root of branch, conversation, publish and reconciliation
- legacy should remain only until that workflow is complete
- the first strong product milestone is a full session-to-PR happy path
