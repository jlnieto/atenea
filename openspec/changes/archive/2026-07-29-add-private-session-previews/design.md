## Context

The runtime contract already allocates loopback ports per immutable
WorkSession, `dev url` resolves the selected allocation, and AX42 has a pinned
Playwright/Chromium toolchain. The archived attachment phase adds immutable,
ordered WorkSession evidence. What is missing is an authoritative preview
registry, a private route whose ownership survives process restart, and one
read model shared by web and Android.

AX42 currently has one hand-built Beautips localhost/tailnet preview. It is
administrative state, not a generic session route and is explicitly outside
this phase's mutation scope. Rootful Docker is masked; project runtimes belong
to four rootless slots. Production/control PostgreSQL and all public
authentication remain on Atenea.

Phase 6 remains default-off and exact-synthetic-only. It does not make a real
project schedulable and does not activate the Phase 4 remote AgentRun switch.

## Goals / Non-Goals

**Goals:**

- make persisted WorkSession ownership the sole authority for preview routes;
- expose one accurate preview state and next action on web and Android;
- provide a tailnet-only route and generated localhost compatibility command;
- reconcile routes idempotently across worker/proxy/control-plane restart;
- attach retained Playwright evidence to the exact WorkSession/AgentRun;
- expire and roll back routes without deleting Git, Codex or attachments.

**Non-Goals:**

- public preview sharing, Internet ingress or anonymous application access;
- onboarding Atenea, Beautips or another real project for remote execution;
- production deployment, production database changes or routing cutover;
- arbitrary TCP proxying, operator-supplied upstreams or filesystem paths;
- general attachment deletion or final production retention defaults.

## Decisions

### Atenea owns preview metadata; AX42 owns live projection

An additive table stores one immutable preview UUID, WorkSession/project/worker
ownership, optional same-session AgentRun, allocation fingerprint, lifecycle
revision, state, timestamps, opaque route identity and sanitized failure/next
action. The control plane never persists a worker filesystem path or accepts a
client-supplied project, worker, runtime namespace or upstream address.

AX42 persists the matching live projection beneath its controlled worker state
root. Every mediated request repeats the complete ownership tuple and expected
revision. Conflicts fail closed; a worker record never teaches Atenea new
ownership.

Alternative considered: derive preview state only from runtime health on every
request. Rejected because restart and expiry need durable intent and because a
transient process cannot be the ownership authority.

### One tailnet ingress port per active synthetic preview

The preview coordinator selects an available port from a bounded dedicated
range, binds only the AX42 Tailscale address and forwards HTTP to the
allocation-derived loopback preview port. The returned URL contains only the
private worker identity, assigned ingress port and manifest-declared path. It
does not disclose the runtime port or accept an arbitrary upstream.

This avoids path-prefix rewriting, which breaks legacy absolute URLs and
cookies, while preserving independent routes for applications that all use
internal port 8080. UFW permits the bounded range only on `tailscale0`; no
public-interface rule is added.

Alternatives considered:

- one path-based reverse proxy: rejected because applications commonly emit
  root-relative paths and redirects;
- direct publication of rootless runtime ports: rejected because it couples
  application allocation to network exposure and bypasses route ownership;
- Tailscale Funnel or another public share: explicitly disabled for Phase 6.

### Renewable lease with a bounded hard lifetime

A ready route receives a five-minute renewable lease. Atenea renews it only
while the persisted preview remains desired and exact ownership still matches.
One activation has an eight-hour hard lifetime; continuing work requires an
explicit new activation. Expiry removes the live route within 60 seconds and
records `EXPIRED`; audit metadata is retained for 30 days. Preview artifacts
retain their independent attachment class.

Alternative considered: keep routes until WorkSession close. Rejected because
abandoned previews would remain reachable indefinitely and make restart
cleanup ambiguous.

### State machine and revision rules

States are `STOPPED`, `STARTING`, `READY`, `BLOCKED`, `RECONCILING` and
`EXPIRED`. Creation and each state transition increment a monotonic revision.
Only `READY` exposes an open action. `STARTING` and `RECONCILING` show bounded
wait guidance; `BLOCKED` exposes a sanitized reason and recovery action;
`STOPPED`/`EXPIRED` expose an explicit start-again action when activation is
allowed.

Duplicate commands with the same preview identity, ownership and expected
revision are idempotent. A stale revision returns conflict and mutates nothing.

### Localhost compatibility uses the private ingress

The generated command forwards a chosen local loopback port through
key-authenticated SSH to the exact tailnet ingress port. It contains no token,
cookie or secret and never forwards a public interface. The local URL uses
`127.0.0.1` plus the manifest path. A project must declare localhost
compatibility; otherwise the API does not offer the command.

Alternative considered: expose the runtime loopback allocation directly.
Rejected because it leaks executor allocation details and bypasses the same
private route tested by web/mobile.

### Browser evidence uses the attachment API, not a global directory

The mediated browser runner receives only the exact WorkSession, optional
same-session AgentRun, preview identity and manifest-defined test command. It
must assert DOM state at `1440x900` and `390x844`, close its browser, then upload
accepted screenshots through the Phase 5 worker/control-plane contract with
`PLAYWRIGHT` source. Failed upload keeps the preview result blocked rather than
silently pointing at an unindexed file.

### Rollback disables creation before removing exact projections

Rollback first disables new preview activation and UI actions. Existing
metadata and attachments remain readable. Live synthetic routes are stopped
only after preview UUID, WorkSession, worker, allocation fingerprint, ingress
port and synthetic marker all match. Repeating rollback must produce no
additional deletion. Runtimes, worktrees and Git are separate resources and
are not stopped unless the exact synthetic acceptance contract explicitly
requests preview-runtime teardown.

## Risks / Trade-offs

- [Tailnet access is network authentication, not application authentication] →
  use opaque bounded routes, least-privilege tailnet/UFW policy and no public
  interface; add control-plane proxy auth later if multi-user access requires
  it.
- [Port-per-preview consumes a bounded host range] → enforce four normal
  previews, deterministic allocation and explicit expiry.
- [A crashed proxy can leave persisted `READY`] → startup reconciliation first
  marks non-terminal rows `RECONCILING`, validates ownership and health, then
  restores the exact projection or records `BLOCKED`.
- [Legacy applications may still reject the tailnet origin] → require a tested
  manifest localhost declaration before onboarding that project.
- [Android validation depends on a real tailnet client] → retain device-side
  route evidence without changing production mobile authentication; if device
  access cannot be proved, the phase does not archive.

## Migration Plan

1. Capture clean Git, production, worker, firewall, slot, attachment and
   Beautips fingerprints; approve the bounded ingress range and synthetic
   identity.
2. Apply the additive preview registry migration in a disposable database and
   add default-off APIs/read models.
3. Implement and test the authenticated worker coordinator/proxy and mediated
   browser evidence path without a live route.
4. Install the worker service and tailnet-only firewall rule, then activate one
   exact synthetic preview.
5. Validate web, Android/private-client, localhost and retained browser
   evidence through teardown, expiry and restart.
6. Disable creation, execute exact rollback twice, compare fingerprints and
   remove only recorded synthetic projections.
7. Strictly validate, archive, commit and push before Phase 7.

Rollback never down-migrates authoritative history. It leaves the additive
table, preview audit rows, attachment metadata/content, worktrees and Git
evidence in place.

## Open Questions

None for synthetic Phase 6. Real-project activation remains gated by each
project's localhost/browser validation, independent backup activation for
authoritative artifacts, and its individual onboarding change.
