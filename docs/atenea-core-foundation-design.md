# Atenea Core Foundation Design

## Purpose

This document defines the first implementable technical contract for `Atenea Core Foundation`.

It is intentionally narrower than the full product vision of `Atenea Core`.

It should now be read as:

- the design of the already-implemented foundation slice
- not the design of the current next major product block

Its goal is to specify the minimum backend slice that:

- introduces a real top-level core entrypoint
- keeps the current `WorkSession` runtime intact
- routes only to the `development` domain in the first implementation
- establishes the contract shape for future `operations` and `communications` domains

This document should be read together with:

- `docs/atenea-core.md`
- `docs/atenea-core-development-operator-surface.md`
- `docs/atenea-v1-architecture.md`
- `docs/roadmap.md`
- `docs/worksession-phase1.md`

## Current repository boundary

This design must respect the current repository reality:

- `WorkSession` is the only implemented domain workflow
- `SessionTurn` and `AgentRun` already provide execution trace inside `development`
- the public backend still exposes `WorkSession` directly
- the repository now exposes an initial top-level `Atenea Core` runtime layer

Therefore, the foundation implementation must:

- not replace `WorkSession` endpoints yet
- not invent `operations` or `communications` runtime behavior that does not exist in code

## Foundation scope

`Atenea Core Foundation` should include only these capabilities:

1. a first top-level API entrypoint for operator commands
2. a typed request-to-intent normalization flow
3. a capability registry with risk and confirmation metadata
4. a domain router
5. one real domain adapter:
   - `development -> WorkSession`
6. minimum persisted traceability for core-level decisions
7. a uniform core response envelope
8. a minimal read API for core-command traceability

`Atenea Core Foundation` should not include yet:

- real `operations` execution
- real `communications` execution
- cross-domain context memory
- voice transport
- autonomous planning
- arbitrary command execution
- multi-step workflow engines

## Design principles

### 1. The first implementation must be thin

The core layer should orchestrate.

It should not duplicate business logic that already exists in:

- `WorkSessionService`
- `SessionTurnService`
- related `worksession` services

### 2. The contract must be typed before execution

Free-form operator input may enter the system, but execution must occur only after it has been normalized into:

- domain
- capability
- parameters
- risk level
- confirmation requirement

### 3. Traceability must exist at core level

`WorkSession` already traces development execution.

`Atenea Core Foundation` must add the missing trace for:

- the original top-level command
- the interpreted intent
- the routing decision
- the selected capability

### 4. Compatibility is mandatory

The current API surface under:

- `/api/projects/*`
- `/api/sessions/*`
- `/api/mobile/*`

must remain valid during the first core rollout.

The new core API is additive.

## First API contract

### Endpoint

The first explicit core endpoint is:

- `POST /api/core/commands`

The current repository also exposes:

- `POST /api/core/commands/{commandId}/confirm`
- `GET /api/core/commands`
- `GET /api/core/commands/{commandId}`

### Request shape

Recommended request object:

```json
{
  "input": "continua la sesion activa de Atenea y prepara el siguiente cambio",
  "channel": "TEXT",
  "context": {
    "projectId": 12,
    "workSessionId": 44
  },
  "confirmation": {
    "confirmed": false,
    "confirmationToken": null
  }
}
```

Recommended fields:

- `input`
  - original operator instruction
- `channel`
  - `TEXT` in the first implementation
  - `VOICE` reserved for future use
- `context.projectId`
  - optional explicit project scope
- `context.workSessionId`
  - optional explicit active session scope
- `confirmation.confirmed`
  - whether the operator explicitly confirms a sensitive action
- `confirmation.confirmationToken`
  - reserved for later confirmation flows

Recommended DTO:

- `com.atenea.api.core.CreateCoreCommandRequest`

### Response shape

Recommended uniform response:

```json
{
  "commandId": 101,
  "status": "SUCCEEDED",
  "intent": {
    "domain": "development",
    "capability": "continue_work_session",
    "riskLevel": "READ",
    "requiresConfirmation": false,
    "confidence": 0.96
  },
  "result": {
    "type": "WORK_SESSION_CONVERSATION_VIEW",
    "targetType": "WORK_SESSION",
    "targetId": 44,
    "payload": {}
  },
  "operatorMessage": "He continuado la WorkSession activa correctamente."
}
```

Recommended DTOs:

- `com.atenea.api.core.CoreCommandResponse`
- `com.atenea.api.core.CoreIntentResponse`
- `com.atenea.api.core.CoreCommandResultResponse`

### Core statuses

Recommended initial statuses:

- `SUCCEEDED`
- `NEEDS_CONFIRMATION`
- `REJECTED`
- `FAILED`

Recommended enum:

- `com.atenea.persistence.core.CoreCommandStatus`

### Result types

For the first implementation, the result type can remain small:

- `WORK_SESSION`
- `WORK_SESSION_VIEW`
- `WORK_SESSION_CONVERSATION_VIEW`

Recommended enum:

- `com.atenea.persistence.core.CoreResultType`

## Typed intent contract

The core execution path must normalize input into a typed envelope before routing.

Recommended internal model:

```json
{
  "intent": "CONTINUE_WORK_SESSION",
  "domain": "development",
  "capability": "continue_work_session",
  "parameters": {
    "projectId": 12,
    "workSessionId": 44,
    "message": "continua la sesion activa de Atenea y prepara el siguiente cambio"
  },
  "confidence": 0.96,
  "riskLevel": "READ",
  "requiresConfirmation": false
}
```

Recommended Java model:

- `com.atenea.service.core.CoreIntentEnvelope`

Recommended fields:

- `intent`
- `domain`
- `capability`
- `parametersJson`
- `confidence`
- `riskLevel`
- `requiresConfirmation`

The first implementation may persist `parametersJson` as a plain JSON string rather than introducing a typed per-capability parameter class hierarchy.

That keeps the foundation small while preserving auditability.

## Capability contract

The core layer must not route directly from free-form intent strings to service methods without a registry.

It should resolve a `CapabilityDefinition`.

Recommended Java model:

```java
public record CapabilityDefinition(
        CoreDomain domain,
        String capability,
        CoreRiskLevel riskLevel,
        boolean requiresConfirmation,
        boolean enabled
) {
}
```

Recommended initial registry entries:

- `development:create_work_session`
- `development:continue_work_session`

Reserved but not implemented yet:

- `operations:check_service`
- `operations:restart_service`
- `communications:read_latest_email`
- `communications:draft_email`
- `communications:send_email`

Recommended package:

- `com.atenea.service.core`

Recommended service:

- `CoreCapabilityRegistry`

## Domain router contract

The core layer should route by domain, not by direct `if` branches on every capability in the controller.

Recommended interface:

```java
public interface CoreDomainHandler {
    CoreDomain domain();
    CoreCommandExecutionResult execute(CoreIntentEnvelope intent, CoreExecutionContext context);
}
```

Recommended router service:

- `CoreDomainRouter`

First real handler:

- `DevelopmentCoreDomainHandler`

This first handler should own only:

- capability dispatch for `development`
- translation from core intent into existing `WorkSession` calls

## Development adapter contract

The first domain adapter must reuse the current implemented services.

It should map capabilities like this:

### `create_work_session`

Expected behavior:

- require `projectId`
- resolve or create a `WorkSession`
- return a conversation-oriented result when possible

Recommended target integration:

- `WorkSessionService.resolveSessionConversationView(...)`

### `continue_work_session`

Expected behavior:

- require `workSessionId`
- require a message
- create a new operator turn
- start Codex execution through the existing `worksession` flow
- return a conversation-oriented result

Recommended target integration:

- `SessionTurnService.createTurn(...)`
- followed by `WorkSessionService.getSessionConversationView(...)`

The core adapter should not replicate:

- repository validation
- branch preparation rules
- Codex execution handling
- stale run reconciliation

Those responsibilities already belong to the current `worksession` services.

## Error contract

The core API should expose a stable set of core-level error categories even when the underlying domain throws more specific exceptions.

Recommended codes:

- `UNKNOWN_INTENT`
- `UNSUPPORTED_DOMAIN`
- `CAPABILITY_DISABLED`
- `TARGET_NOT_FOUND`
- `CONFIRMATION_REQUIRED`
- `EXECUTION_REJECTED`
- `EXECUTION_FAILED`

Recommended mapping approach:

- keep the current `ApiErrorResponse`
- extend `ApiExceptionHandler` with core-specific exceptions
- preserve the existing HTTP behavior style already used by the repo

Suggested HTTP semantics:

- `400`
  - invalid core request
  - unknown or unparseable intent
- `404`
  - target project or session not found
- `409`
  - execution rejected by current state
  - confirmation required
- `422`
  - target exists but the domain blocks execution operationally
- `502`
  - downstream execution failed

## Persistence design

The first implementation should persist one new root table:

- `core_command`

One table is enough for foundation.

It avoids premature multi-attempt or multi-step execution modeling while still preserving the core-level audit trail.

### Recommended table

```sql
CREATE TABLE core_command (
    id BIGSERIAL PRIMARY KEY,
    raw_input TEXT NOT NULL,
    channel VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    domain VARCHAR(32),
    intent VARCHAR(80),
    capability VARCHAR(80),
    risk_level VARCHAR(32),
    requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
    confirmation_token VARCHAR(120),
    confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    confidence NUMERIC(5,4),
    request_context_json TEXT,
    parameters_json TEXT,
    interpreted_intent_json TEXT,
    result_type VARCHAR(64),
    target_type VARCHAR(64),
    target_id BIGINT,
    result_summary TEXT,
    error_code VARCHAR(64),
    error_message TEXT,
    operator_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);
```

Recommended indexes:

- `idx_core_command_created_at_desc`
- `idx_core_command_status_created_at_desc`
- `idx_core_command_domain_created_at_desc`
- `idx_core_command_target_type_target_id`

The current repository also persists interpretation telemetry on that same root:

- `interpreter_source`
- `interpreter_detail`

This is intended to make these cases visible:

- deterministic interpretation
- LLM interpretation
- deterministic fallback after an LLM attempt fails

### Why one table first

One-table persistence is enough because the first slice has:

- one request
- one interpreted intent
- one routed capability
- one execution result

If later the product needs:

- retries
- resumable confirmations
- multi-step plans

then a child execution-attempt table can be added safely.

### Recommended entity

- `com.atenea.persistence.core.CoreCommandEntity`

Recommended repository:

- `com.atenea.persistence.core.CoreCommandRepository`

### JSON storage policy

The repository already persists JSON documents as `TEXT` in places like:

- `session_deliverable.content_json`
- `session_deliverable.input_snapshot_json`

The first core implementation should follow the same pattern and store:

- `request_context_json`
- `parameters_json`
- `interpreted_intent_json`

as `TEXT`.

That keeps persistence simple and consistent with the current codebase.

## Package layout

Recommended new package structure:

```text
src/main/java/com/atenea/api/core
src/main/java/com/atenea/persistence/core
src/main/java/com/atenea/service/core
```

Recommended first files:

### `api/core`

- `CoreController`
- `CreateCoreCommandRequest`
- `CoreCommandResponse`
- `CoreIntentResponse`
- `CoreCommandResultResponse`

### `persistence/core`

- `CoreCommandEntity`
- `CoreCommandRepository`
- `CoreChannel`
- `CoreCommandStatus`
- `CoreDomain`
- `CoreRiskLevel`

### `service/core`

- `CoreCommandService`
- `CoreIntentInterpreter`
- `CoreCapabilityRegistry`
- `CoreDomainRouter`
- `CoreDomainHandler`
- `CoreExecutionContext`
- `CoreIntentEnvelope`
- `CoreCommandExecutionResult`
- `DevelopmentCoreDomainHandler`
- core-specific exceptions

## Execution flow

The first implementation should follow this exact flow:

1. receive `POST /api/core/commands`
2. validate request shape
3. persist a new `core_command` row in a provisional status
4. interpret input into `CoreIntentEnvelope`
5. resolve capability metadata from the registry
6. apply confirmation and governance checks
7. route to the matching domain handler
8. execute through the domain adapter
9. persist:
   - domain
   - capability
   - intent
   - result target
   - result summary
   - final status
10. return `CoreCommandResponse`

Recommended provisional status:

- `RECEIVED`

Recommended persisted final statuses:

- `SUCCEEDED`
- `NEEDS_CONFIRMATION`
- `REJECTED`
- `FAILED`

If the team wants fewer stored states initially, `RECEIVED` can be internal-only and omitted from the public response.

## Interpretation strategy for the first slice

The first foundation implementation should keep LLM usage narrow and bounded.

A pragmatic first slice is:

- hybrid interpretation for `development`
- deterministic routing when explicit context already exists
- LLM-assisted interpretation only when explicit context is missing or insufficient
- persisted telemetry about which interpreter path was used
- simple capability scope:
  - create session
  - continue session

Examples:

- if `context.workSessionId` is present and `input` is non-empty:
  - interpret as `continue_work_session`
- if `context.projectId` is present and no active session id exists:
  - interpret as `create_work_session`

This keeps the first slice testable and bounded even when LLM assistance is enabled.

The important rule is:

- LLM may help interpret
- LLM does not bypass the capability registry or the domain router

## Confirmation model

The first development-only slice now already includes a real confirmation roundtrip in the core contract.

However, the contract should already reserve the fields for it:

- `requiresConfirmation`
- `confirmationToken`
- `confirmed`

This allows addition of:

- `POST /api/core/commands/{id}/confirm`

without redesigning the root model.

For the first implementation:

- `development:create_work_session`
  - `READ`
  - no confirmation
- `development:continue_work_session`
  - `READ`
  - no confirmation

## Mobile and frontend compatibility

`Atenea Core Foundation` should not force immediate frontend migration.

Expected compatibility rule:

- existing frontend and mobile flows may continue using current `WorkSession` endpoints
- the new core endpoint is introduced in parallel
- core-command read APIs may also evolve in parallel without forcing frontend migration

This is important because the repository already has working surfaces in:

- web/static frontend
- mobile backend aliases
- native mobile client

## Recommended implementation order

The first coding phase should happen in this order:

1. add persistence:
   - `V28__create_core_command.sql`
2. add core enums, entity and repository
3. add request/response DTOs under `api/core`
4. add `CoreCommandService`, registry and router
5. add `DevelopmentCoreDomainHandler`
6. add `CoreController`
7. extend `ApiExceptionHandler` with core exceptions
8. add tests for:
   - create session routing
   - continue session routing
   - unsupported capability
   - confirmation-required response shape
   - trace persistence

## What this design intentionally leaves open

This document does not settle yet:

- the final LLM/provider strategy for intent interpretation
- the project-aware operator context model for choosing and reusing the active project or session
- how `operations` targets will be modeled
- how `communications` threads and send flows will be modeled
- how voice should be layered over the core contract
- how clarification should be represented in the public core response envelope

Those decisions are now the focus of the next implementation block after backend foundation, described in:

- `docs/atenea-core-development-operator-surface.md`

## Summary

`Atenea Core Foundation` should be implemented as:

- one new additive API endpoint
- one new additive persistence root
- one typed intent normalization flow
- one capability registry
- one domain router
- one real adapter to the current `development` workflow

The key constraint is:

- build the new top-level core without duplicating or destabilizing the already implemented `WorkSession` runtime
