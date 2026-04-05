# Atenea Core Development Operator Surface

## Purpose

This document records the implementation block that turned `Atenea Core` into the operator surface for the `development` domain.

Its goal is to make explicit how Atenea evolved from:

- an initial core foundation slice above `development`

into:

- a real operator surface for the full `development` workflow
- project-aware and session-aware natural-language interaction
- text and voice as operator channels over the same typed core contract

This document should be read together with:

- `docs/atenea-core.md`
- `docs/atenea-core-foundation-design.md`
- `docs/roadmap.md`
- `docs/worksession-phase1.md`
- `docs/worksession-target-flow.md`
- `docs/mobile-full-operation.md`

## Current repository status

This block is now implemented in backend form and partially integrated in the native client.

Implemented runtime outcomes:

- project-aware `development` capabilities through `Atenea Core`
- persisted operator context through `core_operator_context`
- clarification outcome through `NEEDS_CLARIFICATION`
- confirmation roundtrip on sensitive `development` mutations
- voice-ready responses through `speakableMessage` and per-command speech audio
- command timeline reads and SSE through core-command events
- core-first operator console in the native app
- project/session active context kept in app state and sent to core
- main `development` mutations routed through `Atenea Core` from mobile
- app-level playback from backend-generated speech audio for `speakableMessage`
- app-level voice capture in the `Core` tab with server-side transcription

What still remains outside this implemented slice:

- voice capture outside the `Core` tab
- full migration of every remaining client action to a core-first operator UX
- non-`development` domains

## Why this was the right step

The repository already has:

- a real `development` workflow through `WorkSession`
- a first `Atenea Core Foundation` slice
- core traceability through `core_command`
- a hybrid intent interpreter
- confirmation roundtrip plumbing
- a mobile/native shell already operating the session-first backend

The main product gap is now no longer "introduce a core layer".

The main gap is:

- make `Atenea Core` good enough to operate any project in the `development` domain conversationally

That means the next step is not yet:

- add `operations`
- add `communications`
- push voice deeper into domain logic

The result of this step is:

- `development` is now operable through `Atenea Core`

## Product objective

The next product objective should be understood as:

- the operator can ask Atenea for the status of all projects
- the operator can ask for the state of one specific project
- the operator can set the current project context conversationally
- the operator can open or continue a `WorkSession` through `Atenea Core`
- the operator can send work prompts through that same top-level conversation
- the operator can publish, sync pull request state, generate deliverables and close the session through `Atenea Core`
- the operator can do the same by text or by voice
- Atenea can answer in text and with a voice-ready response

In short:

- `Atenea Core` becomes the operator surface for the whole `development` workflow
- `WorkSession` remains the execution workflow underneath

## Repository reality to preserve

This next phase must preserve these constraints:

- `WorkSession` already implements the real repository workflow
- `Atenea Core` must orchestrate that workflow, not duplicate it
- voice should remain a channel concern, not a new domain model
- operator safety requires clarification and confirmation before sensitive actions
- structured backend reads must remain the factual source of truth

The implementation should therefore reuse existing services such as:

- `ProjectOverviewService`
- `WorkSessionService`
- `SessionTurnService`
- `WorkSessionGitHubService`
- `SessionDeliverableService`
- `SessionDeliverableGenerationService`

## Scope of the development operator surface

The implemented runtime block adds these capabilities under `development`.

### Project-aware read capabilities

- `list_projects_overview`
- `get_project_overview`
- `get_active_project_context`

These capabilities should support requests like:

- "how are all projects doing?"
- "how is Atenea Core?"
- "what needs attention?"

### Conversational context capabilities

- `activate_project_context`
- `activate_work_session_context`
- `clear_active_context`

These capabilities should support requests like:

- "we are going to work on Atenea"
- "switch to the mobile project"
- "continue with the session from yesterday"

### WorkSession lifecycle capabilities

- `create_work_session`
- `continue_work_session`
- `publish_work_session`
- `sync_work_session_pull_request`
- `get_session_summary`
- `get_session_deliverables`
- `generate_session_deliverable`
- `close_work_session`

These capabilities should support requests like:

- "open a session on Atenea"
- "continue working on this project and fix the pending bug"
- "publish it"
- "sync the PR"
- "generate the price estimate"
- "close the session"

## Required conversational behavior

This phase is not only about adding more capabilities.

It also requires better conversational control.

### 1. Persistent operator context

The operator must be able to rely on implicit references such as:

- "that project"
- "that session"
- "publish it"
- "continue with this"

To support that, the core layer needs a persisted operator context root.

Recommended persisted shape:

- `operatorKey`
- `activeProjectId`
- `activeWorkSessionId`
- `activeCommandId`
- `updatedAt`

Recommended new persistence root:

- `core_operator_context`

This state should be separate from `core_command`.

`core_command` is the execution trace.

`core_operator_context` should be the active conversational state.

### 2. Clarification before guessing

The current core layer can reject or confirm.

This next phase must also support explicit clarification.

Required new outcome:

- `NEEDS_CLARIFICATION`

Examples:

- "open Atenea"
  - if two projects match, the system must ask which one
- "publish it"
  - if there is no active session, the system must ask what session to use

This is required for trust and operator safety.

### 3. Confirmation for sensitive mutations

The confirmation path now exists technically.

This next phase must use it on real `development` capabilities.

Recommended first sensitive capabilities:

- `publish_work_session`
- `close_work_session`

Optional depending on operator policy:

- `generate_session_deliverable`

Recommended initial risk policy:

- `list_projects_overview`: `READ`
- `get_project_overview`: `READ`
- `activate_project_context`: `READ`
- `create_work_session`: `READ`
- `continue_work_session`: `READ`
- `get_session_summary`: `READ`
- `get_session_deliverables`: `READ`
- `sync_work_session_pull_request`: `READ`
- `generate_session_deliverable`: `SAFE_WRITE`
- `publish_work_session`: `SAFE_WRITE`
- `close_work_session`: `SAFE_WRITE`

## Voice model

Voice should not be implemented as new domain logic inside `development`.

It should be treated as a channel over the same core contract.

Recommended model:

1. the app captures audio
2. speech-to-text produces the transcript
3. the transcript is sent to `POST /api/core/commands`
4. `channel=VOICE` is persisted on `core_command`
5. the core returns:
   - `operatorMessage`
   - `speakableMessage`
6. the app plays backend-generated speech audio for `speakableMessage`

This means:

- `Atenea Core` remains modality-aware
- the `development` domain remains modality-agnostic

The backend should not couple repository logic to audio transport.

## Streaming and operator experience

If `Atenea Core` becomes the primary operator surface, the operator should not have to poll manually for long-running actions.

This next phase should therefore introduce a core-level event stream.

Recommended additive endpoint:

- `GET /api/core/commands/{commandId}/events/stream`
- `GET /api/core/commands/{commandId}/speech`

Recommended event phases:

- `INTERPRETING`
- `RESOLVING_CONTEXT`
- `NEEDS_CLARIFICATION`
- `NEEDS_CONFIRMATION`
- `EXECUTING`
- `SUCCEEDED`
- `FAILED`

This stream should be especially useful for:

- `continue_work_session`
- `generate_session_deliverable`
- `publish_work_session`
- `close_work_session`

## Recommended typed capability mapping

The implementation keeps `Atenea Core` thin by mapping typed capabilities to services that already exist.

Recommended mappings:

- `list_projects_overview`
  - `ProjectOverviewService.getOverview(...)`
- `get_project_overview`
  - `ProjectOverviewService.getOverview(...)` plus project selection
- `create_work_session`
  - `WorkSessionService.resolveSessionConversationView(...)`
- `continue_work_session`
  - `SessionTurnService.createTurn(...)`
  - then `WorkSessionService.getSessionConversationView(...)`
- `publish_work_session`
  - `WorkSessionGitHubService.publishSessionConversationView(...)`
- `sync_work_session_pull_request`
  - `WorkSessionGitHubService.syncPullRequestConversationView(...)`
- `get_session_summary`
  - `MobileSessionService.getSessionSummary(...)` or an equivalent domain read service
- `get_session_deliverables`
  - `SessionDeliverableService.getDeliverablesView(...)`
- `generate_session_deliverable`
  - `SessionDeliverableGenerationService.generateDeliverable(...)`
- `close_work_session`
  - `WorkSessionService.closeSessionConversationView(...)`

## Required contract evolution

The current core contract is a good start, but this phase needs a broader public response model.

The core response envelope should evolve to support:

- result payload
- clarification requests
- confirmation requests
- voice-ready response text
- operator-facing execution phase

Minimum public additions recommended:

- `NEEDS_CLARIFICATION` core status
- `clarification` response block
- `speakableMessage`
- command event streaming

## Implementation phases

### Phase 1. Project-aware development reads and context

Goal:

- let the operator ask about projects and choose one conversationally

Outputs:

- `list_projects_overview`
- `get_project_overview`
- `activate_project_context`
- `core_operator_context`
- project resolution from natural language
- clarification when project references are ambiguous

### Phase 2. Full WorkSession operation through Core

Goal:

- move the whole primary `development` workflow behind `Atenea Core`

Outputs:

- `create_work_session`
- `continue_work_session`
- `publish_work_session`
- `sync_work_session_pull_request`
- `get_session_summary`
- `get_session_deliverables`
- `generate_session_deliverable`
- `close_work_session`

### Phase 3. Safe operator conversation

Goal:

- make conversational control trustworthy for real operator work

Outputs:

- `NEEDS_CLARIFICATION`
- confirmation policies on sensitive mutations
- better target resolution
- explicit context activation and persistence

### Phase 4. Voice and streaming

Goal:

- make the operator surface multimodal without mixing audio concerns into domain logic

Outputs:

- `channel=VOICE` as a first-class path
- `speakableMessage`
- speech-to-text and text-to-speech integration in the app
- `core` command event stream for progressive UI and voice UX

## Next recommended coding target

The next coding target for the repository should be:

- harden the operator-facing app on top of this implemented core surface and extend voice capture cleanly

That should include:

- using `channel=VOICE` through `POST /api/core/commands`
- keeping `speakableMessage` as the canonical speech source for backend-generated audio
- extending speech-to-text capture beyond the `Core` tab
- using command-event reads and SSE in the client
- migrating the remaining non-core client mutations

## Summary

This implemented block now makes it possible for Atenea to operate any project in the `development` domain through:

- typed capabilities
- persistent operator context
- natural-language interpretation
- clarification and confirmation
- text and voice over the same core contract

Current reality:

- text input and voice-ready output already run through the implemented core contract
- the remaining multimodal gap is extending and hardening voice capture beyond the current `Core` entrypoint
