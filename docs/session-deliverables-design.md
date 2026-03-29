# Session Deliverables Design

## Purpose

This document defines a professional-grade design for session-level deliverables that must be persisted and auditable after repository work is completed in a `WorkSession`.

The design is driven by these operator needs:

- justify completed work to clients
- retain a reusable delivery record after a session
- support later reporting and client-facing summaries
- support commercial estimation with explicit pricing assumptions

This document is intentionally about:

- persisted product design
- backend contract design
- generation workflow design

It now also records which parts of the design are already implemented in the repository.

## Why this is separate from session close

`WorkSession` close already owns repository reconciliation and safety rules.

It should remain responsible for:

- merge-aware close validation
- repository reconciliation
- local and remote branch cleanup
- leaving the project ready for the next session

Session deliverables should not be added to the technical close path as a first implementation because that would couple:

- Git reconciliation
- GitHub merge validation
- multiple Codex generations
- commercial output generation

That coupling would make close slower, less recoverable and harder to reason about.

Recommended product direction:

- keep technical close separate
- add a dedicated deliverables workflow attached to the same `WorkSession`
- allow the operator to generate, review, approve and consult deliverables independently

## Deliverables to support

The first professional deliverables should be:

1. `WORK_TICKET`
   - concise Markdown artifact describing what was done in the session
   - suitable for internal tracking or customer-facing work summary
2. `WORK_BREAKDOWN`
   - structured and human-readable breakdown of all work performed
   - intended as the basis for a later client report
3. `PRICE_ESTIMATE`
   - realistic fixed-price estimate for the client
   - must be grounded in explicit pricing policy, not free-form model opinion

## Core design principles

### 1. Deliverables are first-class persisted artifacts

They should not live only as transient model output inside a turn.

They must be queryable later by session.

### 2. Deliverables are versioned

The operator may want to regenerate one deliverable after:

- another merge status change
- a prompt adjustment
- pricing-policy changes
- manual review

Older versions should remain auditable.

### 3. Human-readable and machine-readable outputs must coexist

A Markdown artifact is not enough for professional reporting and billing reuse.

Each deliverable should support:

- `contentMarkdown`
- `contentJson`

Markdown is the operator-facing artifact.

JSON is the structured substrate for:

- future reports
- billing exports
- dashboards
- downstream automations

### 4. Inputs must be snapshotted

Professional traceability requires knowing what evidence the model used.

Each generated deliverable should retain an immutable input snapshot describing:

- session metadata
- visible turns
- runs
- latest successful outputs
- branch and PR state
- publish metadata
- close status if any
- pricing policy inputs when relevant

### 5. Pricing must be policy-driven

The `PRICE_ESTIMATE` deliverable must not rely only on a generic model judgment.

It should be generated from:

- a pricing policy configured by Atenea
- explicit assumptions
- an operator-visible rationale

## Recommended persistence model

### `SessionDeliverable`

One row per deliverable version.

Recommended fields:

- `id`
- `sessionId`
- `type`
- `status`
- `version`
- `title`
- `contentMarkdown`
- `contentJson`
- `inputSnapshotJson`
- `generationNotes`
- `errorMessage`
- `model`
- `promptVersion`
- `approved`
- `approvedAt`
- `createdAt`
- `updatedAt`

Recommended enums:

- `SessionDeliverableType`
  - `WORK_TICKET`
  - `WORK_BREAKDOWN`
  - `PRICE_ESTIMATE`
- `SessionDeliverableStatus`
  - `PENDING`
  - `RUNNING`
  - `SUCCEEDED`
  - `FAILED`
  - `SUPERSEDED`

### `SessionPricingPolicySnapshot`

This can be a separate entity or part of `inputSnapshotJson`.

For a first strong implementation, embedding it in the input snapshot is enough.

Required pricing-policy inputs:

- market
- currency
- freelance seniority assumption
- baseline hourly/day rate
- orchestration overhead multiplier
- communication/reporting overhead
- delivery risk buffer
- minimum fixed-price floor
- margin policy

## Recommended API surface

### Generate all core deliverables

- `POST /api/sessions/{sessionId}/deliverables/generate`

Status:

- not implemented
- generation currently happens one deliverable type at a time

### Generate one deliverable

- `POST /api/sessions/{sessionId}/deliverables/{type}/generate`

Purpose:

- regenerate only one artifact when needed

### List deliverables for a session

- `GET /api/sessions/{sessionId}/deliverables`

Purpose:

- show latest deliverable versions and statuses

### Read one deliverable version

- `GET /api/sessions/{sessionId}/deliverables/{deliverableId}`

Purpose:

- retrieve the exact persisted version for review or export

### Approve a deliverable

- `POST /api/sessions/{sessionId}/deliverables/{deliverableId}/approve`

Purpose:

- mark a deliverable version as operator-approved

### Read latest approved deliverables

- `GET /api/sessions/{sessionId}/deliverables/approved`

Purpose:

- return the approved set for downstream reporting or billing

### Read deliverable history by type

- `GET /api/sessions/{sessionId}/deliverables/types/{type}/history`

Purpose:

- inspect all versions of one deliverable type
- compare latest generated vs latest approved

### Read approved pricing summary for one session

- `GET /api/sessions/{sessionId}/deliverables/price-estimate/approved-summary`

Purpose:

- expose the approved `PRICE_ESTIMATE` as a stable billing-oriented read model

### Read approved pricing summaries for one project

- `GET /api/projects/{projectId}/approved-price-estimates`

Purpose:

- list approved pricing across the project's sessions without opening each session

## Recommended response model

Primary response object:

- `SessionDeliverablesViewResponse`

Suggested contents:

- `sessionId`
- `deliverables`
- `allCoreDeliverablesPresent`
- `allCoreDeliverablesApproved`
- `lastGeneratedAt`

Each deliverable summary should expose:

- `id`
- `type`
- `status`
- `version`
- `title`
- `approved`
- `approvedAt`
- `updatedAt`
- `preview`
- `latestApprovedDeliverableId`

Detailed reads should expose:

- summary fields
- `contentMarkdown`
- `contentJson`
- `inputSnapshotJson`
- `model`
- `promptVersion`
- `errorMessage`

## Generation workflow

### Step 1. Build an evidence snapshot

Before generating anything, Atenea should create a stable session evidence snapshot from:

- `WorkSession`
- `SessionTurn`
- `AgentRun`
- PR metadata
- publish metadata
- repository state snapshot

This avoids regenerations drifting because live session state changed mid-process.

### Step 2. Generate deliverables independently

Each deliverable should run as an independent generation unit.

That means:

- one failure does not invalidate the others
- retries can target one deliverable only
- operator review can happen incrementally
- version history remains available after regeneration

### Step 3. Persist final outputs with model metadata

Every successful output should persist:

- rendered Markdown
- structured JSON
- model identity
- prompt version
- snapshot reference

### Step 4. Require explicit approval for commercial use

For professional use, especially billing, the operator should approve deliverables before treating them as final.

## Deliverable-specific requirements

### `WORK_TICKET`

Goal:

- produce a clean final work summary

Expected Markdown structure:

- session title
- project and branch context
- summary of completed work
- main repository changes
- PR reference when available
- outcome / next state

Expected JSON structure:

- `summary`
- `workItems`
- `repositoryChanges`
- `pullRequest`
- `sessionOutcome`

### `WORK_BREAKDOWN`

Goal:

- produce a detailed operational and client-facing breakdown

Expected Markdown structure:

- executive summary
- detailed work items
- architectural or implementation changes
- validation performed
- remaining risks or follow-ups

Expected JSON structure:

- `executiveSummary`
- `completedItems`
- `technicalChanges`
- `validation`
- `openRisks`
- `followUps`

### `PRICE_ESTIMATE`

Goal:

- produce a realistic fixed-price commercial estimate for Spain-based freelance delivery

Important constraint:

- this must be policy-constrained and assumption-explicit

Expected Markdown structure:

- scope summary
- pricing assumptions
- fixed-price proposal
- rationale
- exclusions
- confidence / uncertainty note

Expected JSON structure:

- `currency`
- `baseHourlyRate`
- `equivalentHours`
- `minimumPrice`
- `recommendedPrice`
- `maximumPrice`
- `commercialPositioning`
- `riskLevel`
- `confidence`
- `assumptions`
- `exclusions`

## Pricing policy guidance

The `PRICE_ESTIMATE` deliverable should use an explicit pricing policy tuned for Spain.

That policy should be operator-controlled, not hidden in the prompt.

Recommended policy inputs:

- `market = ES`
- `currency = EUR`
- `baseHourlyRate`
- `commercialPositioning`
- `riskLevel`
- `minimumProjectPrice`
- `roundingPolicy`

Current implemented pricing-policy baseline in the repository:

- `market = ES`
- `currency = EUR`
- `baseHourlyRate = 43.0`
- `commercialPositioning = competitive`
- `riskLevel = low`
- `vatIncluded = false`
- `minimumBillableBase = null`
- `roundingPolicy = none`
- `orchestrationSurcharge = false`
- `includeEquivalentHoursInternally = true`
- `outputAudience = internal`
- `justificationDepth = medium`
- assumptions required
- exclusions required
- manual approval required before commercial use

The model should not answer only with a number.

It should always provide:

- recommended final price
- price range
- equivalent-hours rationale
- assumptions
- exclusions
- confidence notes

## Relationship with session close

Recommended product rule:

- deliverables generation is separate from technical close

Optional later product rule:

- operator-facing workflow may require approved deliverables before marking a session as commercially complete

This suggests a future distinction between:

- technical session close
- commercial/session reporting completion

If that distinction becomes important, a later model could add:

- `deliveryCompletionState`

But that should not be forced into `WorkSessionStatus` immediately.

## Suggested implementation phases

## Current implementation snapshot

Implemented today:

- `SessionDeliverable` persistence
- versioned deliverables per `sessionId + type`
- latest generated view by session
- latest approved view by session
- history by type
- explicit generation for:
  - `WORK_TICKET`
  - `WORK_BREAKDOWN`
  - `PRICE_ESTIMATE`
- manual approval of one version
- `SUPERSEDED` handling for regenerated or replaced versions
- structured `PRICE_ESTIMATE` validation before `SUCCEEDED`
- approved pricing read model per session
- approved pricing list per project
- frontend support for:
  - generate
  - review
  - approve
  - inspect version history
  - inspect approved pricing baseline

Still not implemented:

- `POST /api/sessions/{sessionId}/deliverables/generate`
- invoice creation
- billing status persistence such as `READY` / `BILLED`
- global billing queue endpoint

## Suggested implementation phases

### Phase 1. Persistence and read model

Implement:

- `SessionDeliverable` persistence
- enums
- repository
- read/list endpoints

Status:

- implemented

### Phase 2. Single-deliverable generation

Status:

- implemented and extended beyond `WORK_TICKET`

### Phase 3. Work breakdown generation

Status:

- `WORK_BREAKDOWN` generation implemented
- approval flow implemented
- structured JSON remains strongest today for `PRICE_ESTIMATE`

### Phase 4. Pricing estimate with explicit policy

Status:

- implemented

### Phase 5. Aggregated operator UX

Status:

- implemented in session UI
- plus project-level approved pricing panel

## Future agent-based evolution

This design is compatible with future specialized agents.

A later multi-agent architecture could assign:

- ticket agent
- breakdown agent
- pricing agent

That would improve:

- specialization
- consistency
- independent retries
- parallel generation

But that agent layer should sit on top of the same persisted deliverables model.

The persistence model should not depend on whether generation is performed by:

- one general Codex call
- multiple specialized agents

## Non-goals for the first iteration

The first iteration should not try to solve:

- final customer PDF generation
- invoice creation
- accounting integration
- cross-session portfolio analytics
- pricing automation without operator approval

## Summary

The most professional approach for Atenea is:

- persist deliverables as first-class artifacts on `WorkSession`
- generate them outside the technical close path
- version them
- snapshot their inputs
- keep Markdown plus structured JSON
- make pricing policy-driven and approval-based
- leave room for future specialized agents without changing the core model
