# Atenea WorkSession Target Flow

## Purpose

This document defines the near-term canonical product flow for Atenea.

Its purpose is to make the following explicit:

- what Atenea should optimize next
- what the canonical operator flow should be
- what is already implemented
- what is still open

This document should be used together with:

- `docs/roadmap.md`
- `docs/atenea-v1-architecture.md`
- `docs/worksession-phase1.md`

## Product direction

The next major Atenea objective is:

- make `WorkSession` the canonical unit of real repository work and the canonical operator/frontend contract

That means a session is not only:

- open conversation
- run Codex
- show turns

The intended product meaning of `WorkSession` is:

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
8. operator publishes the session
9. Atenea:
   - stages repository changes
   - creates the commit
   - pushes the branch with upstream
   - creates the pull request
10. Atenea persists the resulting delivery metadata on the session
11. later, Atenea detects that the pull request was merged
12. operator closes the session
13. Atenea reconciles the repository back to the base branch and updates local state
14. the session ends fully closed and the project is ready for another session

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
- session-first project overview
- stale running-run reconciliation

### Still to consolidate

- explicit frontend decision that `conversation-view` is the primary session contract
- stronger end-to-end validation of publish, merge and reconciled close happy path
- clearer operator flows for blocked close recovery
- tighter documentation governance so architecture docs do not lag behind code

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

## Required service and product consolidation

The next major work should likely focus on:

- frontend/operator consolidation around `conversation-view`
- stronger test coverage for publish, merge detection, remote branch cleanup and reconciled close
- explicit operator guidance for close-block states and manual recovery paths
- continued documentation alignment around the session-first model

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

1. is `WorkSession` already the canonical delivery workflow
2. does the frontend contract already use `conversation-view` as the primary surface
3. can a session go from open to merged-and-reconciled without manual backend workaround
4. are blocked close scenarios understandable and actionable for operators
5. does the documentation still match code and tests

## Summary

The target near-term product is:

- a session-first repository delivery workflow

In short:

- `WorkSession` is already the root of branch, conversation, publish and reconciliation in backend terms
- the next milestone is to consolidate that workflow as the canonical operator and frontend surface
