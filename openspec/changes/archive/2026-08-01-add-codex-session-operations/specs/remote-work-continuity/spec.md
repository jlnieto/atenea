## MODIFIED Requirements

### Requirement: Operator notification

Atenea SHALL persist a generic immutable notification event in the same
transaction that commits a run completion, failure or operator-action-required
state. It SHALL expand the event into preference-aware per-device deliveries
with unique event/device/channel ownership, finite retry, expiration and
invalid-device handling. Payloads SHALL use versioned safe templates and exact
application deep links and SHALL NOT contain full prompts, final answers,
credentials or worker-internal detail.

The initial category identifiers SHALL be `RUN_COMPLETED`, `RUN_FAILED` and
`ACTION_REQUIRED`. All three SHALL default enabled for an active Android device
with no explicit preference; an explicit per-device preference SHALL survive
re-registration and application upgrade. Intermediate progress SHALL remain
in-app/SSE only and SHALL NOT create push notifications.

#### Scenario: Run finishes while all clients are closed

- **WHEN** the worker reports a terminal result for the latest submitted run
- **THEN** Atenea persists it and sends no more than one applicable completion notification to each configured device according to preference

#### Scenario: Run fails or requires action

- **WHEN** a run reaches a terminal failure or a persisted operator-action-required state
- **THEN** configured devices receive one concise notification whose deep link opens the exact WorkSession and actionable state

#### Scenario: Notification event is delivered again

- **WHEN** backend restart, dispatcher retry or provider timeout repeats the same event/device/channel delivery
- **THEN** Atenea reuses its delivery identity and never creates a second applicable user notification

#### Scenario: Device token is permanently invalid

- **WHEN** the provider reports that one device token is expired or invalid
- **THEN** Atenea disables that device without exposing its token or blocking delivery to other devices

#### Scenario: Application is already foregrounded

- **WHEN** a terminal event arrives while the exact conversation is visible
- **THEN** Android updates the in-app state and suppresses duplicate local presentation where the platform permits

#### Scenario: Future notification category is added

- **WHEN** Atenea introduces a later preference-controlled event type
- **THEN** it reuses the same event, template, preference, delivery and deep-link contracts without changing AgentRun ownership
