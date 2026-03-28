# Atenea Historical Task Branch Workflow

> Historical note:
> this document describes the retired `Task` / `TaskExecution` workflow that used to exist in Atenea.
> That backend surface and its database tables are no longer present in the repository runtime.
> It is historical reference only and must not be treated as the current API or product contract.

## Purpose

This document defines the intended branch workflow for Atenea tasks.

It now serves one role:

- preserve the historical branch workflow that existed before Atenea became session-first

## Status In The Current Architecture

This document is about the **retired task-centered workflow**.

That means:

- it describes historical backend behavior
- it is no longer relevant to the current runtime API surface
- it does not define the current core of Atenea

The architectural direction now implemented is:

- the product core revolves around `WorkSession`
- `Task` is no longer part of the active backend model
- frontend and operator flows must use `WorkSession`

For the current `WorkSession` implementation status, see [`docs/worksession-phase1.md`](./worksession-phase1.md).

## Why This Exists

Atenea should not launch work directly on the main branch or on an arbitrary repository branch.

Each task must own its own working branch so that:

- execution traceability is preserved
- work for one task cannot accidentally contaminate another
- the operator can validate work through a pull request
- branch lifecycle is controlled by Atenea instead of by convention only

## Core Rule

Every task must execute on a branch that belongs to that task.

That implies:

1. a task has a dedicated branch
2. Atenea only launches work on that branch
3. Atenea must refuse launches from the main branch
4. Atenea must refuse launches from a branch that belongs to another task

## Project-Level Concurrency Rule

Only one task branch may be active at a time for each project.

Practical meaning:

- a project cannot have multiple active task branches in parallel
- if one task branch is still open and pending review, the project is blocked for new task-branch creation
- `retry` / `relaunch` may reuse the same task branch, but must not create a second active task branch for the same project

This is intentionally strict and favors control over parallelism.

## Launch Preconditions

Before Atenea launches a task, it must inspect the repository state.

Minimum checks:

1. current branch
2. whether the task branch already exists
3. whether the repository is on the main branch
4. whether the repository is on the task branch
5. whether the repository is on a different branch
6. whether the working tree is in a safe state
7. whether the project is already locked by another active task branch

## Allowed Launch Cases

### Case 1. Task branch already exists and repo is already on it

Result:

- launch is allowed

### Case 2. Task branch does not exist and repo is in a safe state to create it

Result:

- Atenea may create the branch
- Atenea switches to it
- launch is allowed

### Case 3. Task branch exists but repo is on main and checkout is safe

Result:

- Atenea may switch to the task branch
- launch is allowed

## Rejected Launch Cases

Launch must be rejected when:

1. the repo is on the main branch and Atenea has not switched to the task branch
2. the repo is on a branch that belongs to another task
3. the working tree is not in a safe state for branch creation or checkout
4. another task branch is already active for the same project
5. the task is in a workflow state that no longer permits new launches

## Task Branch Identity

The task branch is part of the task identity and must be persisted.

Minimum planned fields for `Task`:

- `branchName`
- `baseBranch`
- `branchStatus`
- `pullRequestUrl` or external PR id
- `pullRequestStatus`
- `reviewOutcome`
- `reviewNotes`

Current implementation status:

- `branchName`, `baseBranch`, and `branchStatus` are already persisted
- `branchStatus` currently supports `PLANNED`, `ACTIVE`, `REVIEW_PENDING`, and `CLOSED`
- `pullRequestUrl` and `pullRequestStatus` are already persisted
- `pullRequestStatus` currently supports `NOT_CREATED`, `OPEN`, `APPROVED`, `MERGED`, and `DECLINED`
- `reviewOutcome` and `reviewNotes` are already persisted
- `reviewOutcome` currently supports:
  - `PENDING`
  - `APPROVED_FOR_CLOSURE`
  - `CHANGES_REQUESTED`
  - `REJECTED`
  - `CLOSED_WITHOUT_REVIEW`

## Historical Backend Endpoints

The retired backend task workflow used to expose these actions:

- `POST /api/tasks/{taskId}/launch`
- `POST /api/tasks/{taskId}/relaunch`
- `POST /api/tasks/{taskId}/review-pending`
- `POST /api/tasks/{taskId}/abandon`
- `POST /api/tasks/{taskId}/pull-request`
- `POST /api/tasks/{taskId}/pull-request/create`
- `POST /api/tasks/{taskId}/pull-request/sync`
- `POST /api/tasks/{taskId}/review-outcome`
- `POST /api/tasks/{taskId}/close-branch`

Task and execution responses also used to expose derived operational signals:

- `projectBlocked`
- `hasReviewableChanges`
- `lastExecutionFailed`
- `launchReady`
- `launchReadinessReason`
- `blockingReason`
- `nextAction`
- `recoveryAction`

Those fields are not persisted as independent state. They are derived from:

- `branchStatus`
- `pullRequestStatus`
- `reviewOutcome`
- task lifecycle status
- whether the task branch contains reviewable changes
- whether the latest execution failed
- whether the task description is specific enough for safe automatic execution

## Launch Readiness And Prompt Policy

Atenea now performs a task-quality check before `launch` or `relaunch`.

The current rule is:

1. automatic execution requires a non-empty task description
2. the description must describe a concrete repository change
3. descriptions that are only diagnostic, exploratory, or too vague are rejected before execution starts
4. when launch is rejected for ambiguity, Atenea returns a conflict with a clarification message and does not open a new execution

This exists because a task may be operationally valid as a workflow object while still being unsafe to execute automatically.

Current derived readiness fields:

- `launchReady`
  Meaning:
  whether Atenea currently considers the task safe to launch automatically

- `launchReadinessReason`
  Meaning:
  why the task is launch-ready or why it still requires clarification

Prompt policy:

- the execution prompt sent to Codex now includes only the task `description`
- the prompt no longer includes `Task title: ...`
- the prompt no longer includes `Task description: ...`
- task title remains internal Atenea metadata and is still used for cataloging and branch naming, but not as execution scope

Frontend note:

- UI should treat `launchReady = false` as a first-class pre-launch state
- for planned tasks in that state, the preferred CTA is not `Launch`, but `Edit task` or `Clarify task`
- current `nextAction` for this case is `clarify_task`
- current `recoveryAction` for this case is also `clarify_task`

## Empty Branch Handling

A task branch may exist without containing useful work.

That state is not equivalent to valid reviewable output.

Current backend rules:

1. `review-pending` is rejected when the task branch has no reviewable changes
2. `pull-request/create` is rejected when the task branch has no reviewable changes
3. `abandon` is the explicit release path for an empty task branch

Operational interpretation:

- empty branch after execution failure -> preferred recovery action is `retry`
- empty branch without useful changes -> preferred recovery action is `abandon`
- branch with reviewable changes -> continue through review / pull request flow

Frontend note:

- UI must not infer `ready for review` from `branch exists`
- UI should use:
  - `hasReviewableChanges`
  - `lastExecutionFailed`
  - `launchReady`
  - `launchReadinessReason`
  - `blockingReason`
  - `recoveryAction`
  to decide whether to show `Retry task`, `Abandon task`, `Clarify task`, or normal review actions

## Pull Request Workflow

After task work is implemented and validated, Atenea should create a pull request for operator review.

Until that pull request is approved according to the policy we adopt, Atenea must not allow:

1. closing the branch
2. deleting the branch
3. opening a new task branch for that same project

This means the project stays logically locked by the current task until review is resolved.

Current implementation status:

- Atenea already exposes pull request metadata on `Task`
- Atenea already allows recording `pullRequestUrl` and `pullRequestStatus`
- Atenea already distinguishes explicitly between:
  - provider-level pull request state through `pullRequestStatus`
  - Atenea's own closure decision through `reviewOutcome`
- Atenea now uses a strict closure policy where a normal task branch must be merged before it can be closed

## Review Outcome Model

The important rule now is:

- `pullRequestStatus` is external state
- `reviewOutcome` is Atenea's internal decision

Practical meaning:

- `pullRequestStatus` answers: what happened to the PR in the Git provider?
- `reviewOutcome` answers: may Atenea close the branch and release the project?

This prevents Atenea from treating provider state as if it were automatically the final workflow decision.

Examples:

- a PR may be `APPROVED` but Atenea can still keep `reviewOutcome = PENDING`
- a PR may be `APPROVED` and Atenea can still refuse closure until the PR becomes `MERGED`
- a task may reach `CHANGES_REQUESTED` or `REJECTED` even if the PR provider uses a different vocabulary

Current implementation status:

- moving a task to `REVIEW_PENDING` resets or keeps review in the internal pending state
- `CHANGES_REQUESTED` and `REJECTED` require `reviewNotes`
- `close-branch` now depends primarily on `reviewOutcome`, not only on `pullRequestStatus`
- `review-pending` now requires reviewable changes in the task branch
- normal closure requires both:
  - `reviewOutcome = APPROVED_FOR_CLOSURE`
  - `pullRequestStatus = MERGED`

## Implications For Retry / Relaunch

`retry` / `relaunch` must be implemented on top of this branch policy.

That means:

- relaunch reuses the same task branch
- relaunch does not create a new task branch
- relaunch is only allowed if the repo is on the task branch or can safely return to it
- relaunch must respect the project lock owned by the same task

So the branch policy is a prerequisite for a correct `retry` / `relaunch` implementation.

Current implementation status:

- `launch` already validates branch ownership and project-level locking
- `relaunch` already reuses the same task branch
- relaunch is rejected when the task has no previous execution history

## Current Closure Rule

The current branch closure rule is:

1. normal closure requires the task to be `REVIEW_PENDING` and `DONE`
2. cancelled tasks may also release the project
3. normal task closure requires:
   - `reviewOutcome = APPROVED_FOR_CLOSURE`
   - `pullRequestStatus = MERGED`
4. cancelled task closure requires:
   - `reviewOutcome = CLOSED_WITHOUT_REVIEW`
5. if review outcome is still `PENDING`, `CHANGES_REQUESTED`, or `REJECTED`, closure is blocked
6. if the pull request is not `MERGED`, normal closure is blocked

This is intentionally stricter than a pure “look at the PR state” model.

## Operational Visibility

The backend now exposes derived operational guidance so the operator or a UI does not need to infer everything manually.

Current derived fields include:

- `projectBlocked`
  Meaning:
  whether this task still occupies the project's single active branch slot

- `hasReviewableChanges`
  Meaning:
  whether the task branch currently contains diff or local work that is reviewable against its base branch

- `lastExecutionFailed`
  Meaning:
  whether the latest recorded execution for this task failed

- `blockingReason`
  Meaning:
  why this task is currently blocking or appearing as blocked

- `nextAction`
  Meaning:
  the next operational step Atenea considers most likely or most appropriate

- `recoveryAction`
  Meaning:
  the preferred unblock or recovery action when the workflow is in a recoverable but not reviewable state

Examples of current `nextAction` values:

- `clarify_task`
- `launch`
- `review`
- `retry`
- `abandon`
- `create_pull_request`
- `complete_review`
- `merge_pull_request`
- `close_branch`
- `relaunch`
- `resolve_rejection`
- `none`

Examples of current `recoveryAction` values:

- `clarify_task`
- `none`
- `retry`
- `abandon`

Current locking semantics:

- `PLANNED` does not hard-block the project
- `ACTIVE` and `REVIEW_PENDING` occupy the project's active slot
- when a task branch is empty, Atenea now exposes the recovery path explicitly instead of forcing the operator to infer it from Git state

These values are intentionally operational, not domain-pure. They exist to support operators and a future UI.

## Recommended Implementation Order

The next backend changes should be done in this order:

1. validate the complete happy path with a concrete real task: launch -> review -> PR -> merge -> close branch
2. surface current operational fields cleanly in UI list/detail views
3. refine task-readiness heuristics if real usage shows false positives or false negatives
4. freeze the V1 API once those contracts stop moving

## Next Step

The immediate next step for Atenea is:

- validate and stabilize the end-to-end happy path on top of the now-closed branch policy

That next slice should include:

1. operator-facing visibility for `clarify_task`, `retry`, and `abandon`
2. end-to-end validation of GitHub-backed review flow with real diffs
3. refinement of readiness rules if needed after real operator feedback
4. final V1 wording for the review lifecycle and launch-readiness contract

This is the intended continuation point for the next agent or session.
