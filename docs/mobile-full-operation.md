# Atenea Mobile Full Operation

## Purpose

This document defines the current strategic objective of making Atenea operable end-to-end from a mobile device.

It exists to make the following explicit:

- what "mobile" means for Atenea
- what is already implemented in the backend and can be reused
- what still needs to be built to support real operator work from mobile
- which phases should guide upcoming implementation work

This document should be used together with:

- `docs/atenea-core.md`
- `docs/atenea-core-development-operator-surface.md`
- `docs/roadmap.md`
- `docs/worksession-target-flow.md`
- `docs/worksession-phase1.md`

## Strategic objective

The current product objective is not merely:

- a responsive UI
- mobile monitoring
- a lightweight chat shell

The target is:

- full operation of Atenea from mobile

## Native app decision

The current repository direction is now explicit:

- client format: native app
- stack choice: React Native
- bootstrap baseline: Expo

This decision fits Atenea's expected operator surface:

- session-first reads
- compact navigation
- long-lived authenticated operator usage
- future support for push notifications, secure storage and stronger confirmation flows

This means the repository is no longer planning mobile only as:

- responsive web
- PWA-first execution surface

Those can still exist later, but the primary product direction is now a native operator app.

For the current repository, `full operation` means that an operator can complete the real `WorkSession` lifecycle from a phone:

- open or resolve a session
- continue the Codex conversation
- inspect turns, runs and operational state
- publish the session
- synchronize pull request state
- understand and recover from close-block situations
- close the session
- generate and approve deliverables
- operate the billing queue

## Product meaning of mobile full operation

Mobile is not a secondary presentation layer over desktop.

The intended product meaning is:

- Atenea should evolve toward `Atenea Core` as the single conversational entrypoint
- `WorkSession` remains the canonical unit of repository work inside the `development` domain
- mobile becomes a first-class operator surface over that same model
- mobile should eventually operate projects and sessions primarily through `Atenea Core`
- voice should be a first-class channel of that same top-level interaction
- mobile operator UX must support both:
  - active execution
  - asynchronous follow-up work

That means mobile must support not only reading state, but safely executing actions against the real backend contract.

## What the backend already provides

The current backend already provides a strong foundation for mobile:

- session-first orchestration through `WorkSession`
- `conversation-view` as the richest session contract
- conversation-oriented action responses for:
  - resolve
  - create turn
  - publish
  - pull-request sync
  - close
- close-block diagnostics through:
  - `closeBlockedState`
  - `closeBlockedReason`
  - `closeBlockedAction`
  - `closeRetryable`
- generated and approved deliverables
- approved pricing summaries
- global billing queue and billing summary

This means the backend does not need a second `development` orchestration model for mobile.

Current boundary:

- today the mobile client is already hybrid:
  - `Atenea Core` is the primary operator entrypoint
  - `/api/mobile/*` remains the compact read layer
- the `Core` tab already supports voice capture with backend transcription and backend-generated Spanish voice playback from `speakableMessage`
- the backend now also provides a project-aware `Atenea Core` surface for the full `development` workflow
- direct mobile mutation aliases still exist in backend for compatibility, but they are no longer the preferred operator path
- the remaining target is to harden the operator UX on top of that split and extend voice capture across the operator UX

## Current repository status for the native client

The repository now already contains a first native mobile shell under `mobile/`.

Current client status:

- React Native project bootstrapped with Expo
- operator shell with tabs for:
  - core
  - inbox
  - projects
  - session
  - billing
- core-first operator console with command input, command history and command-event follow-up
- voice recording in the `Core` tab through `expo-audio`
- backend transcription through `POST /api/core/voice/commands`
- dedicated conversation workspace reached from the session view
- terminal-style conversation workspace optimized for Codex-like mobile operation
- live reads wired to the mobile backend surface
- periodic auto-refresh for the main read surfaces
- session event feed visible in the native client
- direct SSE consumption in the conversation workspace with polling fallback
- native login screen for operator auth
- in-memory authenticated app session with access-token refresh
- secure local session persistence with `expo-secure-store`
- Expo push-token registration baseline
- backend Expo push dispatch baseline for key operator events
- in-app notification capture and recent notification rail
- local persistence of the recent notification rail across app restarts per operator session
- persisted pending-action recovery hints for interrupted mobile mutations
- push-open routing into `Session` and `Billing`
- backend-generated `speakableMessage` playback through remote audio, with local `expo-speech` fallback
- TypeScript validation passing for the current client code

Current client intent:

- operate the implemented `development` core surface from a native runtime
- keep dense read models while consolidating operator mutations behind `Core`
- avoid premature domain duplication in the mobile layer

Current client limitations:

- no persisted notification inbox or richer background action UX yet
- no dedicated confirmation UX coverage for every sensitive action yet
- no interruption/resume UX around long-running mutations yet
- no true resume semantics for interrupted long-running mutations yet
- no voice capture yet outside the `Core` tab
- Expo Go cannot exercise real remote push delivery and therefore runs with push initialization disabled

## Principles

The mobile objective should follow these principles:

- `WorkSessionConversationViewResponse` remains the canonical session contract
- mobile should not fork the business domain away from `WorkSession`
- mobile UX should optimize for compactness, resumability and low-friction actions
- long-running and asynchronous actions must remain safe under unstable mobile connectivity
- mobile should provide strong operational visibility before it provides visual polish
- mobile must use explicit confirmations for sensitive actions

## Mobile full-operation scope

The target operator scope from mobile is:

- project overview
- session inbox and active work queue
- full session conversation in a dedicated focused workspace
- delivery workflow
- deliverables workflow
- billing workflow

Concretely, a mobile operator should be able to:

1. open or resolve a session
2. send turns and follow Codex output
3. inspect the latest run and session state
4. publish a session to a pull request
5. sync pull request state
6. inspect close-block guidance
7. retry and complete close
8. generate deliverables
9. approve deliverables
10. review and operate billing items

## What is still missing for full mobile operation

The main gaps are no longer only in operator experience and transport behavior.

The repository no longer lacks the backend core surface for project-aware `development` work.

The remaining gap is finishing the migration of the mobile/operator experience onto that core surface cleanly.

The main missing pieces are now concentrated in hardening and operator safety:

- mobile migration toward project-aware operation through `Atenea Core`
- extension and hardening of speech-to-text over the implemented core contract
- stronger UI handling for clarification and confirmation outcomes
- dedicated confirmation UX for sensitive actions
- stronger support for asynchronous operator workflows
- richer notification behavior outside currently open screens
- richer persisted notification workflow beyond the local recent rail
- recovery UX that remembers the last in-flight action and routes the operator back to the right context
- interruption-safe action handling and resumability
- more explicit mobile safety patterns for sensitive actions

In practical terms, the repository still needs:

- UX-oriented action flows for:
  - publish
  - close
  - deliverable approval
  - billing actions
- richer notification inbox and background follow-up behavior
- stronger transport and retry semantics around mobile connectivity changes

## Recommended backend direction

### 1. Keep `conversation-view` as the session core

The canonical mobile session contract should continue to anchor on:

- `POST /api/projects/{projectId}/sessions/resolve/conversation-view`
- `GET /api/sessions/{sessionId}/conversation-view`
- `POST /api/sessions/{sessionId}/turns/conversation-view`
- `POST /api/sessions/{sessionId}/publish/conversation-view`
- `POST /api/sessions/{sessionId}/pull-request/sync/conversation-view`
- `POST /api/sessions/{sessionId}/close/conversation-view`

Inside the next `Atenea Core` phase, these contracts should remain the execution-layer source under the `development` domain, even when mobile starts entering through core.

### 2. Add mobile-oriented aggregate reads

The next backend additions should likely be:

- a mobile inbox
- mobile session summaries
- notification/event feeds for session state changes

These should be read models over the existing domain, not a second domain model.

### 3. Support asynchronous mobile execution

Full mobile operation requires better handling of long-running or delayed state changes.

The backend should likely evolve toward:

- event feed or streaming updates for sessions
- operator notifications for important lifecycle changes
- cheap incremental refresh based on `updatedAt` or equivalent cursors

### 4. Keep sensitive actions explicit

The mobile action model should preserve explicit confirmations around:

- publish
- close
- deliverable approval
- mark billed

The backend should remain idempotent and safe to retry.

### 5. Prepare voice as a channel over core

Voice should not fork the mobile domain model.

The recommended direction is:

- speech-to-text in the client or channel layer
- `POST /api/core/commands` with `channel=VOICE`
- voice-ready text returned by core
- text-to-speech in the client

This keeps the mobile app aligned with the same operator contract used for text.

## Recommended product phases

### Phase 1. Mobile backend contract consolidation

Goal:

- make the backend explicitly ready to support a first-class mobile operator surface

Outputs:

- this document and related roadmap alignment
- explicit commitment to `conversation-view` as the session core
- mobile-oriented aggregate reads identified and prioritized
- action semantics clarified for mobile-safe retries and confirmations

Current repository status:

- implemented

### Phase 2. Mobile operator inbox and event model

Goal:

- let an operator understand what needs attention from mobile without drilling into each session manually

Recommended outputs:

- `mobile inbox` endpoint
- session alert buckets such as:
  - run in progress
  - close blocked
  - PR awaiting merge or sync
  - deliverables pending approval
  - billing items `READY`
- event/notification strategy for:
  - turn completion
  - pull request merge
  - close blocked
  - deliverable readiness
  - billing readiness

Current repository status:

- partially implemented
- implemented now:
  - `GET /api/mobile/inbox`
  - `GET /api/mobile/sessions/{sessionId}/events`
  - `GET /api/mobile/inbox/stream`
  - `GET /api/mobile/sessions/{sessionId}/events/stream`
- still open:
  - mobile push notifications beyond active streams
  - explicit operator notification delivery outside open screens

### Phase 3. Full mobile session operation

Goal:

- support the core `WorkSession` lifecycle from mobile

Recommended outputs:

- mobile-first session screen using `conversation-view`
- action flows for:
  - send turn
  - publish
  - pull-request sync
  - close
- compact delivery and close-block presentation
- resumable state refresh around long-running actions

Current repository status:

- implemented in backend contract form through:
  - `GET /api/mobile/sessions/{sessionId}/summary`
  - `POST /api/mobile/projects/{projectId}/sessions/resolve`
  - `GET /api/mobile/sessions/{sessionId}/conversation`
  - `POST /api/mobile/sessions/{sessionId}/turns`
  - `POST /api/mobile/sessions/{sessionId}/publish`
  - `POST /api/mobile/sessions/{sessionId}/pull-request/sync`
  - `POST /api/mobile/sessions/{sessionId}/close`
- partially implemented in native client through:
  - tab-based operator shell in `mobile/`
  - session summary read/control screen
  - dedicated terminal-like conversation workspace
  - inbox and projects navigation into a selected session
  - native login screen
  - session resolve from project cards
  - publish, sync and close actions from session screen
  - turn creation from the dedicated conversation workspace
  - periodic refresh with direct SSE consumption for conversation updates
  - session event feed rendering
- mobile auth baseline now implemented through:
  - backend login, refresh, logout and `me`
  - operator bootstrap configuration for dev/test
  - bearer-token consumption from the native client
  - secure local session persistence
  - Expo push-token registration against protected backend endpoints
  - explicit confirmations now present for:
    - resolve session
    - publish
    - close
    - generate deliverable
    - approve deliverable
    - mark billed
- still open:
  - fuller confirmation UX coverage and polish
  - stronger retry and interruption handling
  - clearer read-only presentation for closed-session historical conversation

### Phase 4. Full mobile deliverables and billing operation

Goal:

- support commercial follow-up from mobile without leaving the session-first model

Recommended outputs:

- mobile deliverables review and approval flows
- mobile billing queue and summary consumption
- billing actions from mobile
- cross-session commercial navigation

Current repository status:

- implemented in backend contract form through:
  - `GET /api/mobile/sessions/{sessionId}/deliverables`
  - `GET /api/mobile/sessions/{sessionId}/deliverables/approved`
  - `POST /api/mobile/sessions/{sessionId}/deliverables/{type}/generate`
  - `POST /api/mobile/sessions/{sessionId}/deliverables/{deliverableId}/approve`
  - `POST /api/mobile/sessions/{sessionId}/deliverables/{deliverableId}/billing/mark-billed`
  - `GET /api/mobile/billing/queue`
  - `GET /api/mobile/billing/queue/summary`
- partially implemented in native client through:
  - billing queue screen
  - approved pricing visibility from session summary
  - generate, approve and mark billed actions from session screen
- still open:
  - richer dedicated deliverable and billing workflows

### Phase 5. Production-grade mobile transport and safety

Goal:

- make the mobile surface robust under real operator conditions

Recommended outputs:

- push notifications or equivalent server-driven signaling
- stronger mobile auth/session handling
- explicit confirmation UX for sensitive actions
- auditability of operator actions from mobile

Current repository status:

- partially implemented
- baseline SSE transport is now available through:
  - `GET /api/mobile/inbox/stream`
  - `GET /api/mobile/sessions/{sessionId}/events/stream`
- baseline pull-style event feed is now available through:
  - `GET /api/mobile/sessions/{sessionId}/events`
- native client now consumes SSE directly in the conversation workspace and retains periodic refresh as fallback
- backend push dispatch is now available for:
  - `RUN_SUCCEEDED`
  - `CLOSE_BLOCKED`
  - `PULL_REQUEST_MERGED`
  - `BILLING_READY`
- Expo delivery remains disabled by default behind `ATENEA_MOBILE_PUSH_ENABLED`
- Expo Go remains a non-push environment by platform limitation, so push bootstrap is intentionally skipped there
- still open and likely decision-sensitive:
  - final confirmation/audit UX contract
  - richer notification UX and routing rules
  - stronger production hardening around secure token storage

## Explicit non-goals for the current phase

The current direction should not be diluted into these weaker substitutes:

- "just make the current web UI responsive"
- "just expose chat on mobile"
- "just support project monitoring"

Those may be useful intermediate wins, but they are not the target product definition.

## Suggested success criteria

The mobile initiative should be considered on track when the repository can justify these claims:

- `WorkSession` can be operated from mobile as the primary work unit
- asynchronous state changes are visible quickly enough for mobile workflows
- operators can complete publish, sync, close, deliverable and billing flows from mobile
- mobile-specific read models reduce drill-down cost across sessions and projects
- backend contracts for mobile are explicit in repository documentation

## Current decision

The current repository direction should therefore be read as:

- Atenea is already session-first in backend runtime
- commercial baseline and billing queue are already present in backend form
- a first mobile backend contract now exists over the same session-first backend
- a first native React Native shell now exists in `mobile/`
- a first mobile auth baseline now exists for protected `/api/mobile/*`
- the backend now already includes the `Atenea Core` development operator surface
- the remaining mobile work is concentrated on stronger mutation-oriented UX, migration toward core-first interaction, voice transport and notification/product hardening
