## MODIFIED Requirements

### Requirement: Private preview by default

Every session preview SHALL be reachable only through an opaque route bound to
an approved private interface or an authenticated control-plane proxy. Phase 6
MUST NOT create public shares, Funnel routes or public-interface listeners.

#### Scenario: Internet client probes a development port

- **WHEN** a client outside the approved private network scans worker preview,
  runtime or Codex ports
- **THEN** no development service or preview content is reachable

#### Scenario: Public sharing is requested

- **WHEN** a Phase 6 client requests a public preview route
- **THEN** the request fails closed without creating a listener or changing the
  private route

### Requirement: Session-scoped preview identity

Atenea SHALL persist one immutable preview identity under the exact
WorkSession, project and worker allocation, with an optional AgentRun owned by
that WorkSession, monotonic revision, truthful readiness state, expiry and
actionable next step. Only `READY` SHALL expose its stable private URL.

#### Scenario: Preview becomes healthy

- **WHEN** the exact allocation-derived runtime health check passes and the
  worker confirms the expected ownership revision
- **THEN** Atenea marks that preview `READY` and offers its private URL on web
  and supported mobile surfaces

#### Scenario: Preview is not ready

- **WHEN** build, start, ownership, route or health validation fails
- **THEN** Atenea shows `BLOCKED` with a sanitized reason and next action
  instead of an apparently usable link

#### Scenario: Foreign runtime is presented

- **WHEN** a request names a runtime allocation not owned by the WorkSession
- **THEN** preview activation fails without exposing or changing the foreign
  allocation

### Requirement: Localhost compatibility tunnel

The platform SHALL provide a generated key-authenticated SSH local-forward
command only for a manifest declaring localhost compatibility. The command
SHALL target the exact private ingress projection, contain no credential and
bind only a caller-chosen local loopback port.

#### Scenario: Project requires localhost origin

- **WHEN** the exact session manifest declares localhost compatibility and its
  preview is ready
- **THEN** the operator receives a bounded tunnel command and local URL that
  reach the same preview without publishing a worker runtime port

#### Scenario: Project does not declare localhost compatibility

- **WHEN** a client asks for a tunnel for a session without that declaration
- **THEN** the platform returns an actionable unsupported state and no forward
  is started

### Requirement: Worker-side browser verification

The worker SHALL run only the project-declared Playwright/Chromium check inside
the exact WorkSession runtime boundary with finite timeouts. It SHALL validate
rendered DOM and visual usability at `1440x900` and `390x844`, close all browser
processes and register accepted evidence through the WorkSession attachment
boundary.

#### Scenario: Responsive UI change is verified

- **WHEN** the exact synthetic preview reaches `READY`
- **THEN** critical DOM assertions and inspected desktop/mobile screenshots are
  retained under the originating WorkSession and optional AgentRun before the
  browser exits

#### Scenario: Browser evidence cannot be indexed

- **WHEN** a screenshot exists only in temporary browser storage or attachment
  registration fails
- **THEN** acceptance remains blocked and teardown removes the unretained
  temporary file

### Requirement: Mobile preview access

An authenticated mobile operator on the approved private network SHALL receive
the same preview identity, state, next action, expiry and ready URL as the web
operator without requiring the laptop to remain online.

#### Scenario: Laptop is offline

- **WHEN** a worker preview and its indexed artifacts are ready while the
  laptop is disconnected
- **THEN** the Android operator can inspect the state, open the tailnet-only URL
  and retrieve the exact WorkSession evidence through Atenea

#### Scenario: Route has expired

- **WHEN** Android refreshes a preview after its route lease or hard lifetime
  expires
- **THEN** it sees `EXPIRED` and a start-again action, and no stale open action
  remains

## ADDED Requirements

### Requirement: Bounded preview lease and restart reconciliation

A ready route SHALL have a five-minute renewable lease, an eight-hour hard
lifetime and removal within 60 seconds of expiry or explicit stop. Restart
reconciliation SHALL use persisted exact ownership, SHALL NOT invent or
reassign allocation, and SHALL preserve 30-day audit metadata plus attachments.

#### Scenario: Preview coordinator restarts

- **WHEN** the worker service restarts with one unexpired persisted preview
- **THEN** it marks the route reconciling and restores only the exact owned
  projection or records it blocked without selecting another runtime

#### Scenario: Preview lease expires

- **WHEN** renewal stops for longer than the five-minute lease
- **THEN** the private listener is removed within 60 seconds while the
  WorkSession, Git state, runtime allocation and retained evidence remain
