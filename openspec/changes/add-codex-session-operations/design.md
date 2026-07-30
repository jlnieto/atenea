## Context

Atenea persists WorkSessions, SessionTurns and AgentRuns and routes exact real
projects to AX42. The remote project runner currently accepts a closed workload
containing project identity, message and optional thread identity. It invokes
`codex exec --ignore-user-config`, so neither the control plane nor the worker
records the effective model or reasoning effort.

Codex produces structured execution events, but the remote runner currently
uses them only to recover the thread identity and final response. Atenea's
mobile SSE model exposes lifecycle changes, not a durable normalized progress
timeline. FCM registration, Android receipt and several event-specific sends
already exist, but remote coordinator completion bypasses the existing
`RUN_SUCCEEDED` call and delivery is not represented per device.

The accepted platform contracts already require observable lifecycle,
cancellation, restart reconciliation, actionable interruption and operator
notification. This change implements those requirements as one coherent
operator experience.

## Goals / Non-Goals

**Goals:**

- show the effective Codex profile before and after every run;
- allow safe model/effort selection for future turns without changing history;
- show meaningful progress within seconds on web and Android;
- recover routine stuck states without SSH or duplicate execution;
- notify configured Android devices after terminal/action-required events;
- provide a reusable notification substrate for later Atenea event types;
- make Codex upgrades observable, tested, reversible and administrator-only.

**Non-Goals:**

- exposing raw reasoning, hidden chain-of-thought or unrestricted command logs;
- letting prompts edit Codex, worker, slot, credential or host configuration;
- supporting arbitrary providers, model identifiers or experimental settings;
- automatically upgrading Codex while executions are active;
- treating Max or Ultra multi-agent operation as a reasoning-effort alias;
- replacing key-based break-glass administration.

## Decisions

### Persist one immutable effective execution profile per AgentRun

Configuration precedence is:

1. an explicit authenticated next-turn override;
2. the WorkSession default;
3. the project default;
4. the platform default;
5. the worker's accepted catalog default.

The selected canonical model identifier and supported reasoning effort are
resolved before durable dispatch and copied into the AgentRun with their source
and the selected worker's Codex version. A later settings change affects only
future AgentRuns. Existing turns, results and audit records never change.

The first release accepts only catalog-advertised model identifiers and
supported `low`, `medium`, `high` or `xhigh` effort. The UI may present friendly
labels, but the persisted value is canonical. Max and Ultra remain out of
scope because they are not simple effort values.

### Extend the closed worker contract, not caller command authority

Model and effort become explicit allowlisted workload fields and part of the
request fingerprint. The worker rejects unsupported, missing, conflicting or
ambiguous profile data before Codex starts. The fixed runner translates the
validated profile into reviewed Codex flags; it never accepts an argument
array, provider, endpoint, path, environment value or configuration fragment.

The worker reports the exact installed Codex version used by the execution.
Atenea rejects dispatch when its selected profile is incompatible with the
worker's advertised catalog/version.

### Normalize useful progress without retaining reasoning

The worker maps structured Codex and lifecycle events into a closed taxonomy:

- accepted and queued;
- preparing workspace;
- Codex started;
- inspecting project;
- running a command;
- checking or testing;
- waiting or reconciling;
- finalizing;
- completed, failed or cancelled.

Each event owns a monotonic sequence, timestamp, run identity, category and
short sanitized operator message. Repeated low-value events are coalesced and
each run retains at most 200 normalized events. Raw model reasoning, deltas,
command arguments, command output, environment values and secret-bearing
payloads are neither published nor stored.

Atenea persists a newer event before publishing it through the existing mobile
stream and web event path. Clients reconnect with the last sequence and receive
the durable gap before live events. The current state and required next action
remain visible even if detailed events age out with the owning AgentRun.

### Use explicit recovery semantics

Routine authenticated operators may:

- cancel their exact non-terminal AgentRun;
- retry a terminal failed run as a new AgentRun linked by `retryOfRunId`;
- request reconciliation of an exact reconciling/unreachable run;
- obtain a sanitized diagnostic summary.

Reconciliation observes the same dispatch and never creates a replacement.
Retry is allowed only after the worker proves the previous dispatch is terminal
or absent and no non-terminal run exists for the WorkSession. Every command has
an idempotency key, persisted state and actionable terminal outcome.

Privileged operators may request a mediated restart of the exact worker
execution service or project App Server only when policy permits. Platform
administrators alone may stage, activate or roll back a Codex version. No API
accepts an arbitrary service, host, shell command, slot or resource identity.

### Generalize notifications through an outbox

The transaction that persists a terminal/action-required state also writes one
immutable notification event. A dispatcher expands it into one delivery per
active configured device. Unique `(event_id, device_id, channel)` ownership
prevents duplicate delivery while allowing independent retry and diagnostics.

Notification categories initially include run completion, run failure and
operator action required. Event type, category, safe title/body template,
entity identity and deep link are versioned independently of FCM so future
Atenea events can reuse the same outbox. Full prompts, final answers, secrets
and internal worker details never enter notification payloads.

Operators can enable categories per device. Completion and failure are enabled
by default for active Android devices. Exponential retry is finite; expired or
permanently invalid device tokens are disabled without affecting other
devices. Tapping a notification opens the exact WorkSession conversation. When
the app is foregrounded it updates the in-app conversation and suppresses a
duplicate local presentation where Android permits.

### Manage Codex versions as a staged platform operation

AX42 advertises installed Codex version, accepted catalog revision and
compatibility state. Update discovery produces a read-only plan. Activation
requires separate administrator authorization, zero active executions, a
verified release artifact, generated App Server/CLI schema comparison, focused
contract tests and one canary run.

The installer retains the current and previous verified release and switches
one canonical `current` link atomically. A failed health, schema or canary gate
restores the previous link and restarts only the exact Codex/worker boundary.
Project runtimes, production, Beautips resources and unrelated slots are not
restarted. Routine sessions can inspect version state but cannot initiate an
update.

### Keep the operator UI state-first

The conversation header shows current state, model, effort, Codex version and
elapsed time without scrolling. During execution, one concise progress card
shows the latest event and next action. The primary action is context-specific:
cancel while active, retry after a safe failure, or request reconciliation
when unreachable. Advanced history and administrator operations do not compete
with the primary action.

Web and Android consume the same API contracts. UI acceptance requires data,
DOM and inspected visual checks at desktop `1440x900` and mobile `390x844`,
plus a real background/closed Android push exercise when a configured device
is available.

## Risks / Trade-offs

- Too many progress events create noise and storage growth; closed categories,
  coalescing and a 200-event bound keep the timeline useful.
- Model availability changes remotely; persisted catalog revision and
  fail-closed selection prevent silent substitution.
- Changing model inside one WorkSession can affect response style; the chosen
  profile is visible and immutable per turn.
- A retry can duplicate work if the old process is merely unreachable; retry
  therefore requires terminal/absent worker proof.
- FCM may accept a message without proving user presentation; per-device state
  distinguishes accepted delivery from confirmed app open.
- Updating Codex may change its event schema; staged schema generation,
  contract tests, canary and previous-version rollback gate activation.

## Migration Plan

1. Capture current schema, API, worker protocol, FCM, installed versions,
   routing and production fingerprints.
2. Add expand-only persistence for profiles, progress, recovery operations,
   notification events/preferences/deliveries and worker inventory.
3. Deploy backend and worker readers/writers with all new controls disabled.
4. Enable profile persistence and safe progress for one synthetic session.
5. Enable web and Android settings/progress/recovery surfaces.
6. Adapt existing push events to the outbox, then enable remote terminal
   notifications for one configured device.
7. Exercise cancellation, reconciliation, failed retry, disconnect and backend
   restart without duplicate execution or notification.
8. Exercise update planning and rollback synthetically. Perform a real AX42
   Codex update only after separate explicit administrator authorization.
9. Run complete backend, worker, Android and Playwright validation, seal
   sanitized evidence and archive the change.

Rollback disables new settings, progress publication, recovery actions and
notification dispatch first. Additive records remain for audit and
reconciliation. It does not down-migrate production history, delete devices,
remove WorkSessions or change existing routing.

## Open Questions

None. Model/effort allowlists, role boundaries, event taxonomy, event bound,
notification defaults and update authorization are fixed by this design.
