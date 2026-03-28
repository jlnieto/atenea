# Atenea V1 Architecture

## Purpose

This document defines the architectural direction that is guiding Atenea and the current backend shape that already exists in the repository.

It distinguishes clearly between:

- the implemented backend architecture
- the product contract that is already stable enough to build around
- the historical workflow that has been retired

The core architectural decision is:

- the product core revolves around `WorkSession`
- `Project` remains the repository anchor
- `SessionTurn` remains the persisted visible conversation history
- `AgentRun` remains the persisted trace of a concrete Codex execution inside a session

The current near-term target flow for that direction is documented in:

- [`docs/worksession-target-flow.md`](./worksession-target-flow.md)

## System role

Atenea is a remote orchestration backend for repository work on this VPS.

That means:

- Atenea operates on registered repositories
- Atenea protects repository state
- Atenea coordinates Codex execution
- Atenea persists operational and conversational state
- Atenea reduces the need for the operator to work directly in the shell for normal flows

The system is best understood as:

- register a project
- open or resolve a session
- interact with Codex through session turns
- publish the session work to a pull request
- synchronize merge state
- close the session only after repository reconciliation is safe

## Current backend reality

The current backend contains one real orchestration surface:

- `Project`
- `WorkSession`
- `SessionTurn`
- `AgentRun`

The former `Task` / `TaskExecution` workflow is retired from runtime. It should only be referenced as historical context.

## Current implemented backend architecture

### Stable platform and repository model

Implemented and active:

- `Project` with validated `repoPath`
- `Project.defaultBaseBranch`
- workspace-root validation through platform configuration
- Git inspection and branch operations through `GitRepositoryService`
- Codex App Server integration
- GitHub integration for session publish and pull request synchronization

### `Project`

Architectural role:

- stable anchor for repository work
- owner of repository identity and default base-branch policy

Current responsibility:

- identify a repository Atenea can operate on
- persist canonical `repoPath`
- persist `defaultBaseBranch`

### `WorkSession`

Architectural role:

- root of the operator-facing workflow
- owner of the session working branch
- owner of session-level continuity
- owner of session-level delivery state

Current implementation status:

- persistence implemented
- open/read/close API implemented
- resolve-or-create API implemented
- aggregated session view API implemented
- session-owned branch setup implemented
- turn execution implemented
- thread continuity implemented
- descriptive operational snapshot implemented
- turns history implemented
- runs history implemented
- publish-to-PR implemented
- pull request sync implemented
- merge-aware close implemented
- stale-run reconciliation implemented

### `SessionTurn`

Architectural role:

- persist visible conversation history for the session

Current implementation status:

- persistence exists
- public API flow uses it
- visible operator and Codex turns are returned through session history
- internal technical turns exist but are filtered from the public history

### `AgentRun`

Architectural role:

- trace one Codex execution inside a `WorkSession`

Current implementation status:

- persistence exists
- public and service flows use it
- one running run per session is enforced
- each run persists execution status and external turn traceability

### Codex App Server in the current architecture

Codex App Server integration is part of the implemented backend.

Current role:

- Atenea opens or reuses external Codex threads
- Atenea starts turns against Codex App Server
- Atenea persists external thread continuity at session level
- Atenea persists external turn traceability at run level
- Atenea stores summarized output or error state on runs

Architectural meaning:

- external Codex execution is part of the current `WorkSession` runtime model
- `AgentRun` is the internal trace of that execution
- `SessionTurn` is the conversation-facing trace of that execution

## Implemented runtime flows

### Session lifecycle

Implemented:

- open session
- resolve or create session per project
- project-level single active-session policy
- branch preparation from `baseBranch` or existing `workspaceBranch`
- protection against opening or recovering from an unrelated branch

### Conversation and execution

Implemented:

- operator turn creation
- Codex execution inside the session
- persisted operator and Codex turns
- persisted run lifecycle
- reuse of `externalThreadId` across turns

### Delivery lifecycle

Implemented:

- publish session changes
- stage, commit and push through `GitRepositoryService`
- GitHub pull request creation
- pull request state synchronization
- merge-aware close flow
- repository reconciliation back to base branch
- session branch cleanup, including remote cleanup when it applies

### Read models

Implemented:

- base session read
- aggregated session view
- aggregated conversation view
- project overview with a session-first `workSession` block

## Core architectural decisions

### 1. `WorkSession` is the root of the product workflow

The operator flow is:

- open or resolve a session on a project
- inspect state
- ask Codex to work
- refine through conversation turns
- publish when ready
- close only when repository and pull request state allow safe reconciliation

### 2. Session, conversation, run and repo state stay separated

The architecture keeps these concerns separated:

- session state
- conversation turns
- execution runs
- repository state

The current backend implements that separation at the main model level.

### 3. Repository snapshot remains descriptive

The `WorkSession` operational surface is descriptive.

Current `repoState` exposes repository facts such as:

- `repoValid`
- `workingTreeClean`
- `currentBranch`
- `runInProgress`

It does not try to reintroduce legacy task-style workflow advice.

### 4. Historical task workflow is not part of the current architecture

The retired `Task` / `TaskExecution` model should not guide new design decisions.

Its historical branch workflow may still be useful as background context, but it is no longer a runtime concern for the backend.

## Current architectural gaps

The remaining architecture gaps are not about missing session fundamentals.

They are:

- deciding the primary frontend/operator session contract
- tightening end-to-end validation around publish, merge detection and reconciled close
- keeping documentation and product language aligned with the codebase

## What the backend is not claiming yet

The backend should still avoid stronger claims than the repository supports.

It does not yet define:

- the final frontend contract beyond the currently available session reads
- the final long-term operator UX around blocked close recovery
- every future reporting or higher-level product surface that may be built on top of `WorkSession`
