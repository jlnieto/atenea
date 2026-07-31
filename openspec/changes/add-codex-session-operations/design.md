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

- prevent a stale or ambiguous WorkSession from modifying an outdated project
  snapshot;
- make build, test, OpenSpec and visual validation available through closed
  mediated operations;
- distinguish Codex process completion from validated and integration-ready
  work;
- show the effective Codex profile before and after every run;
- allow safe model/effort selection for future turns without changing history;
- show meaningful progress within seconds on web and Android;
- recover routine stuck states without SSH or duplicate execution;
- notify configured Android devices after terminal/action-required events;
- provide a reusable notification substrate for later Atenea event types;
- make Codex upgrades observable, tested, reversible and administrator-only.

**Non-Goals:**

- granting a WorkSession a Docker socket, shell, arbitrary repository or
  secret directory;
- automatically rebasing, merging, discarding or committing a dirty stale
  draft;
- exposing raw reasoning, hidden chain-of-thought or unrestricted command logs;
- letting prompts edit Codex, worker, slot, credential or host configuration;
- supporting arbitrary providers, model identifiers or experimental settings;
- automatically upgrading Codex while executions are active;
- treating Pro or Ultra operation as a model identifier or reasoning-effort
  alias;
- replacing key-based break-glass administration.

## Decisions

### Admit writes only from an exact canonical source

Before the first write-capable AgentRun, Atenea resolves and persists the
selected repository, branch, canonical commit and mirror observation. The
worker admits the run only when the clean WorkSession HEAD equals that commit.
An ancestor relationship is insufficient for a new implementation.

The canonical commit is runtime state, not a compile-time constant in the
repository being observed. Embedding a branch HEAD in that repository would
be self-invalidating because the commit that changes the constant creates a
new HEAD. A fixed control-plane mediator observes the configured remote branch,
persists the commit and observation identity, and copies that value into the
WorkSession and AgentRun. AX42 independently resolves the same fixed ref in its
mirror immediately before admission. The observed control-plane commit,
worker-mirror commit, WorkSession HEAD and workload commit must all be equal.
Missing refs, a moved branch or unequal observations fail closed.

If an existing dirty WorkSession is stale, Atenea marks it `DRAFT_BLOCKED`,
retains a sanitized fingerprint and makes no automatic rebase, merge, reset,
commit or copy. Recovery creates a new clean WorkSession from the accepted
canonical commit. An operator may later request a reviewed file-by-file port
from the retained draft; conflicts and overlapping canonical changes remain
explicit.

### Validate through closed mediated operations

Codex does not receive a Docker socket, privileged shell, secret directory or
host toolchain. Atenea instead exposes reviewed symbolic validation operations
bound to an exact WorkSession, repository revision and validator definition:

- backend tests;
- web build;
- Android build;
- Playwright data, DOM and visual acceptance;
- strict OpenSpec validation;
- worker protocol and runner contract suites.

Each operation owns a versioned command definition outside caller input,
finite timeout, isolated service account, permitted mounts, sanitized output,
artifact manifest and exit status. The caller cannot supply a command, image,
compose file, environment, path, host, slot, endpoint or credential. Repeating
the same operation against the same source and definition returns or
reconciles the existing result.

### Separate execution, validation and integration readiness

An AgentRun terminal state reports only the Codex process outcome. WorkSession
readiness is a separate persisted acceptance projection:

1. `DRAFT` — writes exist but are not accepted;
2. `VALIDATING` — required mediated checks are in progress;
3. `BLOCKED` — one or more required checks or authorities are unavailable;
4. `VALIDATED` — every required check passed for the exact source tree;
5. `INTEGRATION_READY` — validated source is current, reviewed and eligible
   for the separately authorized commit/publish operation.

The UI may say that Codex finished, but it MUST NOT say the task is complete
unless its declared acceptance profile is satisfied. Every blocked state
names the failed or missing check and the next permitted action. A changed
tree invalidates prior validation.

### Bind reviewed rules and multi-repository authority

`--ignore-user-config` remains appropriate for preventing ambient host
configuration from changing execution, but `--ignore-rules` cannot silently
discard the project's operating contract. The control plane resolves a
reviewed instruction bundle from platform and repository-owned `AGENTS.md`
sources, persists its fingerprint and injects only that accepted bundle into
the runner.

A WorkSession receives a closed set of repository roles, such as Atenea code,
the remote-platform OpenSpec programme or worker source. Every repository has
an exact URL, branch, commit, mirror and writable/read-only role. A normal code
session cannot modify the programme or installed root-owned worker. A
cross-repository change uses separate owned worktrees, validations and commits
linked by one change identity; partial publication remains visible and
rollbackable.

### Persist one immutable effective execution profile per AgentRun

Configuration precedence is:

1. an explicit authenticated next-turn override;
2. the WorkSession default;
3. the project default;
4. the platform default;
5. the worker's accepted catalog default.

Model and effort resolve independently, so a next-turn effort override does
not erase the WorkSession's model selection. The resulting AgentRun stores
`modelId`, `modelSource`, `reasoningEffort`, `effortSource`, `catalogRevision`
and `codexVersion`. The closed source values are `NEXT_TURN`, `WORK_SESSION`,
`PROJECT`, `PLATFORM` and `WORKER_DEFAULT`. Resolution and compatibility checks
finish before durable dispatch. A later settings change affects only future
AgentRuns; existing turns, results and audit records never change.

The worker catalog has these canonical top-level fields:
`schemaVersion`, `catalogRevision`, `workerId`, `codexVersion`, `generatedAt`
and `models`. Every sorted model entry has `modelId`, `displayName`,
`supportedEfforts`, `defaultEffort` and `availability`. `catalogRevision` is a
digest of the schema version, Codex version and sorted model entries; the
diagnostic generation timestamp is excluded from that digest. The first
release recognizes only the canonical effort values `none`, `low`, `medium`,
`high`, `xhigh` and `max`, and only when the selected model entry advertises
that value and platform/project policy permits it. Friendly labels and model
aliases never enter persistence or dispatch. Pro remains a separately governed
mode, and Ultra multi-agent operation remains outside this profile contract.

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

The worker maps structured Codex and lifecycle events into this exact closed
taxonomy: `ACCEPTED`, `QUEUED`, `PREPARING_WORKSPACE`, `CODEX_STARTED`,
`INSPECTING_PROJECT`, `RUNNING_COMMAND`, `CHECKING`, `WAITING`, `RECONCILING`,
`FINALIZING`, `COMPLETED`, `FAILED` and `CANCELLED`.

Each event owns a monotonic sequence, timestamp, run identity, category and
short sanitized operator message. Consecutive events with the same category
and sanitized message are coalesced before a sequence is allocated. Each run
retains the 200 newest normalized events; inserting event 201 evicts the oldest
retained detail event without renumbering or reusing a sequence. Current state,
latest event, terminal outcome, elapsed time and required next action are
separate projections and therefore never disappear at the retention edge. A
replay cursor below the retained floor receives that projection followed by
the retained gap. Raw model reasoning, deltas, command arguments, command
output, environment values and secret-bearing payloads are neither published
nor stored.

Atenea persists a newer event before publishing it through the existing mobile
stream and web event path. Clients reconnect with the last sequence and receive
the durable gap before live events. The current state and required next action
remain visible even if detailed events age out with the owning AgentRun.

### Use explicit recovery semantics

The authorization matrix is closed:

| Role | Allowed operations |
|---|---|
| `ROUTINE_OPERATOR` | Read the selected worker catalog/version; set project-permitted WorkSession defaults; submit a permitted next-turn override; cancel, retry, request reconciliation or obtain a sanitized diagnostic for the operator's exact WorkSession. |
| `PRIVILEGED_OPERATOR` | Every routine operation plus a policy-permitted mediated restart of the exact owned worker execution service or project App Server. |
| `PLATFORM_ADMINISTRATOR` | Every privileged operation plus create an update plan, stage a verified Codex release, separately authorize activation, and separately authorize an operator-requested rollback. |

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

The initial category identifiers are `RUN_COMPLETED`, `RUN_FAILED` and
`ACTION_REQUIRED`; all three are enabled by default for a new or existing
active Android device that has no explicit preference. Intermediate progress
is in-app/SSE only and does not create push notifications. An explicit
per-device preference always wins and is not reset by application upgrade or
device re-registration. Exponential retry is finite; expired or permanently
invalid device tokens are disabled without affecting other devices. Tapping a
notification opens the exact WorkSession conversation. When the app is
foregrounded it updates the in-app conversation and suppresses a duplicate
local presentation where Android permits.

### Manage Codex versions as a staged platform operation

AX42 advertises installed Codex version, accepted catalog revision and
compatibility state. Routine operators may inspect this state but cannot create
or execute an update operation. A platform administrator may create a
read-only plan and stage a verified candidate without changing the canonical
`current` link. Activation requires a separate, single-use, finite
authorization bound to the exact worker, current version, candidate version,
release digest and plan identity, plus zero active executions, generated App
Server/CLI schema comparison, focused contract tests and one canary run.

The installer retains the current and previous verified release and switches
one canonical `current` link atomically. The activation authorization includes
fail-closed automatic restoration of that exact previous link when a health,
schema or canary gate fails. An operator-requested rollback requires its own
platform-administrator authorization and exact current/previous identities.
Either path restarts only the exact Codex/worker boundary. Project runtimes,
production, Beautips resources and unrelated slots are not restarted.

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

- Freshness gates can block a long-lived WorkSession after canonical advances;
  retained draft quarantine and reviewed porting preserve work without hiding
  conflicts.
- Mediated validators add operational machinery; symbolic definitions and
  exact ownership keep that authority narrower than exposing Docker or shell.
- Multi-repository work may produce partially ready components; linked
  readiness and separate commits make that state explicit rather than
  pretending atomicity across repositories.
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

1. Fingerprint and quarantine the existing stale Atenea draft without
   changing, committing, rebasing or discarding it.
2. Implement canonical-source admission, reviewed rules, mediated validators,
   multi-repository authority and truthful acceptance state.
3. Prove backend, web, Android, Playwright, OpenSpec and worker validation
   operations against exact synthetic identities, including all negative
   authority cases.
4. Create a clean Atenea WorkSession from the accepted canonical HEAD and port
   only reviewed draft changes after overlap analysis.
5. Capture current schema, API, worker protocol, FCM, installed versions,
   routing and production fingerprints.
6. Add expand-only persistence for profiles, progress, recovery operations,
   notification events/preferences/deliveries and worker inventory.
7. Deploy backend and worker readers/writers with all new controls disabled.
8. Enable profile persistence and safe progress for one synthetic session.
9. Enable web and Android settings/progress/recovery surfaces.
10. Adapt existing push events to the outbox, then enable remote terminal
   notifications for one configured device.
11. Exercise cancellation, reconciliation, failed retry, disconnect and backend
   restart without duplicate execution or notification.
12. Exercise update planning and rollback synthetically. Perform a real AX42
   Codex update only after separate explicit administrator authorization.
13. Run complete backend, worker, Android and Playwright validation, seal
   sanitized evidence and archive the change.

Rollback disables new settings, progress publication, recovery actions and
notification dispatch first. Additive records remain for audit and
reconciliation. It does not down-migrate production history, delete devices,
remove WorkSessions or change existing routing.

## Open Questions

None. Model/effort allowlists, role boundaries, event taxonomy, event bound,
notification defaults and update authorization are fixed by this design.
