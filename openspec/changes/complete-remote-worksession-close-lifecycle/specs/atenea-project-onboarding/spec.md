## ADDED Requirements

### Requirement: Consecutive canonical Atenea WorkSession lifecycle

Canonical Atenea SHALL support consecutive `main`-based WorkSessions without
manual AX42 repair. A normal remote close SHALL release exact active worker
ownership before the control plane records `CLOSED`, and the next WorkSession
SHALL acquire only the released canonical slot through the reviewed mediator.
Activation of this successor SHALL remain restricted to Atenea until separate
project acceptance exists.

#### Scenario: Canonical Atenea session closes normally

- **WHEN** its runs are terminal, delivery and Git are reconciled and exact
  remote finalization succeeds
- **THEN** the session becomes `CLOSED/RELEASED` while its source, conversation,
  attachments, logs and artifacts remain reviewable

#### Scenario: Next Atenea session starts

- **WHEN** a clean newly opened session uses accepted `main` after the prior
  release receipt is durable
- **THEN** it obtains the canonical Atenea workspace, allocation, admission and
  registration without a host-side repair or foreign resource change

#### Scenario: Next Atenea session replaces stale pre-dispatch source

- **WHEN** the open session's failed pre-dispatch run is pinned behind current
  canonical `main` and the operator explicitly starts fresh
- **THEN** Atenea SHALL close that session normally and open exactly one clean
  successor whose source is admitted only when the operator later sends a new
  turn
- **AND** it SHALL NOT copy or resend the stale turn, prompt, AgentRun or
  attachment binding

#### Scenario: Retained legacy Atenea owner is reconciled

- **WHEN** a platform administrator separately confirms the exact closed
  predecessor and its zero-resource ownership proof
- **THEN** the product-mediated release frees only that predecessor's active
  ownership and leaves the blocked successor and its failed prompt unchanged

#### Scenario: Another project is inspected during rollout

- **WHEN** Beautips or any non-Atenea project, slot, route or resource is
  fingerprinted before and after
- **THEN** it remains ineligible for the new gate and unchanged apart from
  ordinary time-varying health metadata
