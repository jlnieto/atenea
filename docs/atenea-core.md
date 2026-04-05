# Atenea Core

## Purpose

This document defines what `Atenea Core` means in product and architectural terms.

It exists to make these points explicit:

- `Atenea Core` is the intended top-level operator surface for the product
- the current runtime only implements an initial `Atenea Core Foundation` slice
- `WorkSession` remains important, but as a workflow of the `development` domain
- future implementation work should follow a clear order instead of mixing domains ad hoc

This document should be read together with:

- `docs/atenea-core-foundation-design.md`
- `docs/atenea-v1-architecture.md`
- `docs/roadmap.md`
- `docs/worksession-phase1.md`
- `docs/worksession-target-flow.md`

## Current repository reality

As of the current repository state, `Atenea Core` now exists as a real runtime layer for the `development` domain.

What is implemented today:

- `POST /api/core/commands`
- `POST /api/core/commands/{commandId}/confirm`
- `POST /api/core/voice/commands`
- `GET /api/core/commands/{commandId}/speech`
- `GET /api/core/commands`
- `GET /api/core/commands/{commandId}`
- `GET /api/core/commands/{commandId}/events`
- `GET /api/core/commands/{commandId}/events/stream`
- `core_command` persistence
- `core_command_event` persistence
- `core_operator_context` persistence
- hybrid top-level interpretation for `development`
- typed capabilities enabled in runtime:
  - `list_projects_overview`
  - `get_project_overview`
  - `activate_project_context`
  - `create_work_session`
  - `continue_work_session`
  - `publish_work_session`
  - `sync_work_session_pull_request`
  - `get_session_summary`
  - `get_session_deliverables`
  - `generate_session_deliverable`
  - `close_work_session`
- clarification outcome through `NEEDS_CLARIFICATION`
- confirmation roundtrip for sensitive `development` capabilities
- voice-ready core responses through `speakableMessage` and per-command speech audio
- server-side voice transcription path into `Core` for the app channel
- `Project`
- `WorkSession`
- `SessionTurn`
- `AgentRun`
- session-first repository work for the `development` domain
- mobile and billing surfaces built around that same session-first backend

What is not implemented today:

- runtime execution for `operations`
- runtime execution for `communications`
- cross-domain conversational context resolution
- full voice capture across every app surface
- a full client migration of every remaining operator action away from direct session-first calls

This distinction must remain explicit in all repository documentation:

- current runtime: core layer plus development-domain workflow centered on `WorkSession`
- target architecture: `Atenea Core` above domain workflows

## Definition

`Atenea Core` is the high-level conversational orchestrator of Atenea.

Its role is to act as the single conversational interface between the operator and the capabilities of the system.

`Atenea Core` should not directly execute domain logic or technical operations.

Its job is to:

- interpret operator language
- determine intent
- decide the target domain or workflow
- apply governance and confirmation rules
- coordinate execution
- return a coherent response to the operator

In short:

- `Atenea Core` is an orchestrator of capabilities
- it is not itself the domain executor
- it is not an autonomous agent that invents actions

## Core principles

### Separation of responsibilities

`Atenea Core` should not:

- restart services directly
- encode development-, operations- or communications-specific business rules
- collapse all product behavior into `WorkSession`

`Atenea Core` should:

- interpret intent
- route to the right domain
- invoke typed capabilities or workflows
- manage the conversational flow

### Intention-first orchestration

Every operator request should be transformed into a typed structure before execution.

Illustrative shape:

```json
{
  "intent": "RESTART_SERVICE",
  "domain": "operations",
  "parameters": {
    "host": "legacy-prod",
    "service": "apache"
  },
  "confidence": 0.95,
  "requiresConfirmation": true
}
```

The exact payload can evolve, but the architectural rule should remain:

- free-form operator input in
- typed intent envelope out

### Decoupled domains

`Atenea Core` should route toward independent domains.

Initial target domains:

- `development`
- `operations`
- `communications`

Current repository reality:

- only `development` has a real implemented workflow today
- that implemented workflow is `WorkSession`

### Typed capabilities

Atenea should not execute arbitrary commands as the normal product contract.

Execution should happen through typed capabilities such as:

- `create_work_session`
- `continue_work_session`
- `check_service`
- `restart_service`
- `read_latest_email`
- `draft_email`
- `send_email`

Typed capabilities are required for:

- governance
- auditability
- validation
- risk control

### Mandatory governance

Before execution, `Atenea Core` should:

- validate the target entity
- validate that the capability is allowed
- classify execution risk
- determine whether explicit confirmation is required
- block dangerous actions when confirmation is missing

## Target high-level flow

The intended high-level product flow is:

```text
Input (text or voice)
  -> speech-to-text when needed
  -> intent interpretation
  -> typed intent envelope
  -> Atenea Core
     -> validation
     -> routing
     -> governance
  -> domain capability or workflow
  -> execution
  -> structured result
  -> operator-facing response
```

## Conversational model

`Atenea Core` should own the primary operator conversation.

Within that primary conversation it may activate secondary contexts such as:

- a `WorkSession`
- an operations incident context
- an email thread

This means the long-term product conversation is not identical to a `WorkSession`.

Examples of references that `Atenea Core` should eventually resolve:

- "reply to that" -> latest active communication context
- "restart it" -> latest active service context
- "continue with that" -> latest active domain context

This context-resolution behavior is not implemented in the current repository.

## Relationship with `WorkSession`

`WorkSession` remains a first-class workflow, but only inside the `development` domain.

Architectural meaning:

- `WorkSession` is not the top-level system entrypoint in the target model
- `WorkSession` is the execution workflow that `Atenea Core` should use for development work
- `Atenea Core` should decide whether to create, resolve or continue a `WorkSession`

Current repository reality:

- the backend now exposes a first additive top-level core entrypoint
- the backend still also exposes `WorkSession` directly as the primary implemented domain runtime surface
- therefore `WorkSession` is both:
  - the current real operator/backend surface
  - the future `development` domain workflow under `Atenea Core`

Documentation must keep both truths explicit to avoid confusion.

## Risk and confirmation model

The intended execution classes are:

- `READ`
- `SAFE_WRITE`
- `DESTRUCTIVE`

Intended operator policy:

- `READ`: no confirmation by default
- `SAFE_WRITE`: confirmation recommended
- `DESTRUCTIVE`: confirmation required

This risk model is not yet implemented as a general cross-domain core policy in the current repository.

## Traceability model

The target architecture requires a persisted trace for every operator interaction:

- original input
- interpreted intent
- routing decision
- executed capability
- result
- evidence

Current repository reality:

- development-domain conversation and execution trace already exist through:
  - `WorkSession`
  - `SessionTurn`
  - `AgentRun`
- a first core-level trace now exists through `core_command`
- no full cross-domain trace exists yet because only `development` is implemented

## Initial MVP scope for Atenea Core

The first meaningful `Atenea Core` MVP should support:

### Domains

- `development`
- `operations`
- `communications`

### Minimum capabilities

`development`

- `create_work_session`
- `continue_work_session`

`operations`

- `check_service`
- `restart_service`

`communications`

- `read_latest_email`
- `draft_email`
- `send_email`

Important current constraint:

- the repository only implements the `development` side today
- the `operations` and `communications` capabilities described here are target scope, not present runtime features

## Non-goals for the first implementation

The initial `Atenea Core` effort should not attempt:

- autonomous decision-making without operator direction
- arbitrary command execution as the product contract
- production automation without explicit confirmation rules
- multi-domain execution chains hidden behind one opaque prompt

## Recommended next implementation block

The next recommended block of work for this repository is:

## Core-First Voice App Integration

The `development` operator surface is now implemented in backend form.

The next step should focus on using it as the real operator surface from the client/channel side.

Recommended outputs:

1. app-level speech-to-text feeding `POST /api/core/commands` with `channel=VOICE`
2. app-level playback consuming backend speech audio generated from `speakableMessage`
3. migration of project/session operator flows from direct session-first calls toward the core contract
4. client UX for `NEEDS_CLARIFICATION`, `NEEDS_CONFIRMATION` and command-event timelines
5. only after that, evaluation of the first non-`development` domain

## Summary

`Atenea Core` should be understood as:

- the future top-level conversational orchestrator of Atenea
- a capability router with governance and traceability
- a layer above `development`, `operations` and `communications`

The current repository should be understood as:

- a real backend for the `development` domain
- centered on `WorkSession`
- still missing the top-level `Atenea Core` layer

The next step should therefore be:

- migrate the operator-facing app and voice channel onto the now-implemented `development` core surface before opening new domains
