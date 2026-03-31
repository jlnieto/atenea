# Atenea Roadmap

## Purpose

This document tracks the current product state based on the repository as it exists today.

It distinguishes clearly between:

- what is implemented and validated
- what gaps are still open
- what the next recommended block of work is

This document should be read together with:

- `docs/atenea-core.md`
- `docs/atenea-core-foundation-design.md`
- `docs/worksession-target-flow.md`
- `docs/mobile-full-operation.md`

## Current Version State

Current formal version markers in the repository:

- application artifact version: `0.0.1-SNAPSHOT`
- product stage label still used in documentation: `V0`

This still reads as `V0` because product and documentation consolidation are still in progress, even though the backend is now centered on a single orchestration model.

## Current validated system state

The repository currently contains:

- one real implemented domain workflow in `development`
- one initial top-level `Atenea Core Foundation` runtime slice above that workflow

### Conversational surface

Implemented and validated:

- `POST /api/core/commands`
- `core_command` persistence
- hybrid core intent routing for `development`
- enabled typed capabilities in runtime:
  - `create_work_session`
  - `continue_work_session`
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
- generated, versioned and approved session deliverables
- structured pricing output for `PRICE_ESTIMATE`
- approved pricing reads by session and by project

This means the backend now has:

- an initial top-level core entrypoint
- one real implemented domain workflow under it: `development`

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

Implemented historically, now removed from backend runtime:

- `Task`
- `TaskExecution`
- task launch / relaunch
- branch ownership and project-level locking
- launch-readiness heuristics
- review-pending flow
- pull request metadata and GitHub integration
- explicit review outcome
- strict branch closure rules
- operational guidance fields

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
- `POST /api/sessions/{sessionId}/turns/conversation-view`
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

### Block 6. Session deliverables and commercial baseline

Implemented:

- `SessionDeliverable` persistence
- deliverable types:
  - `WORK_TICKET`
  - `WORK_BREAKDOWN`
  - `PRICE_ESTIMATE`
- versioning per `sessionId + type`
- session deliverable generation:
  - `POST /api/sessions/{sessionId}/deliverables/{type}/generate`
- session deliverable approval:
  - `POST /api/sessions/{sessionId}/deliverables/{deliverableId}/approve`
- latest generated view:
  - `GET /api/sessions/{sessionId}/deliverables`
- latest approved view:
  - `GET /api/sessions/{sessionId}/deliverables/approved`
- history by type:
  - `GET /api/sessions/{sessionId}/deliverables/types/{type}/history`
- detailed read by version:
  - `GET /api/sessions/{sessionId}/deliverables/{deliverableId}`
- `SUPERSEDED` handling for regenerated or replaced versions
- `PRICE_ESTIMATE` structured output validation
- approved pricing read by session:
  - `GET /api/sessions/{sessionId}/deliverables/price-estimate/approved-summary`
- approved pricing read by project:
  - `GET /api/projects/{projectId}/approved-price-estimates`
- frontend support for:
  - generate
  - inspect history
  - approve
  - inspect approved pricing baselines

### Block 7. Mobile backend surface and native operator shell baseline

Implemented:

- mobile backend aggregate reads:
  - `GET /api/mobile/projects/overview`
  - `GET /api/mobile/inbox`
  - `GET /api/mobile/sessions/{sessionId}/summary`
  - `GET /api/mobile/sessions/{sessionId}/events`
- mobile backend action aliases for:
  - resolve session
  - conversation read
  - create turn
  - publish
  - pull request sync
  - close
  - deliverables
  - billing queue
- SSE baseline for:
  - `GET /api/mobile/inbox/stream`
  - `GET /api/mobile/sessions/{sessionId}/events/stream`
- native client bootstrap in `mobile/`
- React Native operator shell for:
  - Inbox
  - Projects
  - Session
  - Billing
- dedicated conversation workspace for session operation
- TypeScript-valid mobile API client against `/api/mobile/*`
- native write flows already wired for:
  - resolve session
  - turn
  - publish
  - pull request sync
  - close
  - generate deliverable
  - approve deliverable
  - mark billed
- periodic refresh and session event feed in the native client
- direct SSE consumption in the conversation workspace with polling fallback
- mobile auth baseline:
  - `POST /api/mobile/auth/login`
  - `POST /api/mobile/auth/refresh`
  - `POST /api/mobile/auth/logout`
  - `GET /api/mobile/auth/me`
  - JWT access token + persisted refresh token rotation
  - native login screen in `mobile/`
  - secure local session persistence with `expo-secure-store`
- Expo notifications registration baseline:
  - `POST /api/mobile/notifications/push-token`
  - `POST /api/mobile/notifications/push-token/unregister`
  - `GET /api/mobile/notifications/push-devices`
  - native Expo push-token acquisition and backend registration
  - backend Expo dispatch baseline for:
    - `RUN_SUCCEEDED`
    - `CLOSE_BLOCKED`
    - `PULL_REQUEST_MERGED`
    - `BILLING_READY`
  - push deduplication log in persistence
  - delivery disabled by default behind `ATENEA_MOBILE_PUSH_ENABLED`
  - in-app notification capture and recent notification rail
  - push-open routing into `Session` and `Billing`
  - Expo Go protection:
    - push initialization disabled intentionally in Expo Go because SDK 53+ does not support remote push there

### Block 4. Session-first project overview

Implemented:

- `GET /api/projects/overview`
- canonical `workSession` block per project

Current conclusion:

- the backend now exposes a session-first overview only

### Block 8. Atenea Core Foundation initial slice

Implemented:

- `POST /api/core/commands`
- `core_command` persistence
- typed core request DTOs and response envelope
- hybrid interpreter for the current development-only slice
- persisted interpreter telemetry:
  - deterministic
  - llm
  - deterministic fallback
- capability registry with runtime-enabled `development` capabilities
- domain router with first adapter:
  - `development -> WorkSession`
- initial traceability for:
  - raw input
  - interpreted intent
  - routed capability
  - execution result summary

Current conclusion:

- `Atenea Core Foundation` exists in runtime, but only for the `development` domain

## Open gaps that are real today

The main open gaps visible in the repository are now these:

### Gap 1. `Atenea Core` exists only as a narrow foundation slice

The repository now has a strong `development` workflow, but it still does not implement:

- runtime execution for `operations`
- runtime execution for `communications`
- app-level voice transport on top of the core contract
- a core-first client/operator flow fully migrated off direct session-first calls
- cross-domain conversational context resolution

This is now the main architectural gap.

### Gap 2. Commercial workflow above approved pricing is not yet consolidated

What is now settled in the repository:

- `conversation-view` is the primary frontend/operator session contract
- create turn now also has a conversation-oriented action response
- session deliverables are generated and approved through the operator surface
- approved pricing can already be consulted by session and by project
- approved pricing already persists minimal billing state:
  - `READY`
  - `BILLED`
- invoice/reference and billed timestamp can already be persisted on the approved baseline

What still needs consolidation:

- a global billing queue
- cross-project billing aggregation and operator workflow
- richer invoicing workflow beyond per-baseline `billingReference`

### Gap 3. Documentation and governance are behind the implementation

The repository recently contained documentation that still described major `WorkSession` capabilities as pending even though they were already implemented.

That means documentation governance is a real gap, not an editorial detail.

### Gap 4. Session-first operator contract still needs cleanup

The code now shows a working session-first delivery workflow in the backend, but the repository still does not fully settle:

- frontend alignment
- operator workflow simplification
- final product contract around session views and commercial follow-up

### Gap 5. Native mobile production concerns are still pending

The repository now has both a mobile backend surface and a native client baseline, but it still does not settle:

- richer mobile notification UX and routing
- stronger mutation-oriented UX with confirmations, retries and interruption handling
- stronger production hardening around secure local credential/token handling

## Pending real work

Based strictly on the repository, the pending work is not:

- basic `WorkSession` turn execution
- basic thread continuity
- basic turns/runs history
- basic session close

Those are already implemented.

The pending real work is instead:

- migrate operator-facing clients toward the implemented `development` core surface
- consolidate documentation around the true current state
- define which API surface should drive the next operator or frontend workflows
- harden the now-implemented session-first repository delivery flow as the primary product contract
- harden the native mobile client from read shell into full production operator app
- evolve the approved pricing baseline into a true billing workflow

## Next recommended phase

The next recommended phase, justified by the current repository, is:

## Core-First Voice App Integration

Goals:

- keep documentation aligned with code and tests
- keep `Atenea Core` as the top-level operator contract for `development`
- move client and channel work onto that contract
- add real app-level speech-to-text and text-to-speech
- expose clarification, confirmation and command events cleanly in the operator UX

Recommended outputs of this phase:

1. extend app-level STT beyond the current `Core` tab
2. hardening of app-level TTS from `speakableMessage`
3. finish migrating the remaining client flows that still bypass `Core`
4. client UX for:
   - `NEEDS_CLARIFICATION`
   - `NEEDS_CONFIRMATION`
   - command event timelines
5. regression and product validation of the operator surface before adding non-`development` domains

Already implemented outputs of this phase:

1. app-level TTS from `speakableMessage`
2. core-first client flows for:
   - project status
   - project selection
   - session lifecycle actions
   - conversation prompts
3. client UX for:
   - `NEEDS_CLARIFICATION`
   - `NEEDS_CONFIRMATION`
   - command event timelines
4. app-level voice capture in the `Core` tab with backend transcription through `POST /api/core/voice/commands`

Current repository reading after recent changes:

- core runtime layer: implemented for the `development` domain
- development workflow through `WorkSession`: implemented
- project-aware development core capabilities: implemented
- active operator context in core: implemented
- clarification outcome in core: implemented
- voice-ready responses and command-event timeline in core: implemented
- mobile backend contract baseline: implemented
- mobile inbox and mobile session event feed: implemented
- mobile operation aliases for session, deliverables and billing: implemented
- conversation-first terminal workspace in native client: implemented
- client-side SSE consumption for session conversation: implemented
- core-first operator console in native client: implemented
- core command history and command-event stream in native client: implemented
- app-level TTS from `speakableMessage`: implemented
- app-level STT in the `Core` tab through backend transcription: implemented
- remaining major mobile gaps after core foundation:
  - extension of app-level speech-to-text beyond the `Core` tab
  - migration of the remaining approval/billing actions to core
  - mobile auth/session hardening
  - stronger confirmation and retry UX
  - richer notification/product workflow

## What remains uncertain

The repository does not yet justify stronger claims than these:

- it does not yet settle the final notification UX model or audit contract for sensitive mobile actions

Those points should therefore remain explicit decisions, not assumptions.

## Summary

Current roadmap reading should be:

- legacy task orchestration: removed from backend runtime
- `Atenea Core Foundation` plus development operator surface: implemented in backend
- first core-first native client slice plus TTS: implemented
- `WorkSession` conversational core plus session-first delivery workflow: implemented in backend as the `development` workflow
- session deliverables and approved pricing baselines: implemented in backend
- global billing queue baseline: implemented in backend
- immediate next major step: extend voice capture cleanly beyond the `Core` tab and finish the remaining client hardening on top of the implemented core contract
- mobile full operation remains important, now over the implemented core contract
- future planning beyond that: still requires explicit human decisions
