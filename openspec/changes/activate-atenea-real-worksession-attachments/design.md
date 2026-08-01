## Context

The archived attachment phase delivered V47 metadata, authenticated
WorkSession list/upload/download APIs, a bounded AX42 content service and a web
panel, but explicitly excluded real-project activation. The 2026-08-01 audit
found production at zero attachment rows with `ATENEA_ATTACHMENTS_ENABLED`
defaulting false, no attachment credential mounted into the backend and the
AX42 service active against an empty `/srv/atenea/attachments-v1` root.

The current backend admits only project display names in
`syntheticProjectAllowlist` and always sends
`X-Atenea-Synthetic-Fixture: true`. Its public upload accepts caller-selected
source, kind and retention. `CreateSessionTurnRequest` contains only `message`;
no attachment is bound to a turn or AgentRun, `RemoteWorkerClient` dispatches
only text, and the fixed runner never uses the installed Codex CLI 0.145.0
`--image` option. The Android native client has no scoped attachment composer.
Retention dates are indexed but no general deletion exists.

The independent Backblaze B2/restic boundary is now configured, scheduled and
restore-tested. Its exact source policy includes `/srv/atenea/attachments-v1`,
so the external-backup prerequisite can be consumed by this separately gated
activation change. Atenea production, preview, Beautips and every unrelated
project remain outside its mutation scope.

## Goals / Non-Goals

**Goals:**

- Activate real screenshot upload only for new exact Atenea WorkSessions.
- Bind up to four ordered images immutably to one idempotent operator turn and
  its AgentRun.
- Deliver only verified owned image files to both new and resumed Codex turns.
- Preserve current limits, integrity, private storage and download continuity.
- Make readiness, selected images, upload progress and errors obvious in the
  web conversation composer.
- Prove disabled deployment, real canary, backup/restore, restart, rejection,
  rollback and non-impact with sanitized evidence.

**Non-Goals:**

- Enable attachments for Beautips or any project other than Atenea.
- Add the Android native picker/composer in this change.
- Send text, JSON, PDF or ZIP attachments to Codex; they remain retained-file
  formats only.
- Parse natural-language references such as “latest screenshot” to invent a
  binding; the client sends exact selected attachment UUIDs.
- Add public attachment URLs, arbitrary worker paths or caller storage/runtime
  authority.
- Add general destructive retention cleanup or allow operators to delete
  retained files.
- Change production data, deployment authority, preview routing or project
  runtime behavior.

## Decisions

### 1. Combine a global kill switch with a canonical per-project gate

`ATENEA_ATTACHMENTS_ENABLED` remains the global create/bind kill switch. A new
closed `real-project-allowlist` accepts only canonical identities registered in
code; this change registers and enables only `atenea`. Unknown identities make
startup fail rather than becoming display-name authority. Synthetic fixtures
keep their existing separate allowlist.

When a newly resolved remote Atenea WorkSession is eligible, Atenea snapshots
`atenea-real-attachments-v1` on the session. Existing sessions retain `null`
and do not become upload-capable merely because configuration changes. Global
or project disable blocks new upload and new binding but never blocks list or
exact download of already indexed content.

Alternatives rejected: one global enable for every project widens authority;
project display names are mutable; retroactively enabling every open Atenea
session makes rollout ownership ambiguous.

### 2. Derive operator-upload classification on the server

The routine operator route creates only `OPERATOR_UPLOAD` content with
`SESSION` retention. Atenea derives `IMAGE` versus `FILE` from validated media
type. Existing source/kind/retention form fields are accepted only when they
equal those derived values during compatible rollout; any attempt to claim
`BROWSER_SCREENSHOT`, `REPORT`, `TRACE` or `EVIDENCE` through the operator
route fails closed. Mediated preview/evidence producers retain their separate
authority.

The accepted storage types remain PNG, JPEG, WebP, UTF-8 text, JSON, PDF and
ZIP. Only `IMAGE` with PNG/JPEG/WebP is eligible for a Codex turn.

### 3. Use one additive V62 ownership and idempotency migration

V62 adds:

- nullable `work_session.attachment_policy_revision` for the immutable
  new-session eligibility snapshot;
- nullable paired `session_turn.client_request_id` and
  `request_fingerprint_sha256`, with one unique request identity per session;
- `session_turn_attachment(work_session_id, session_turn_id, attachment_id,
  position)` with composite same-session foreign keys, unique position and no
  update/delete application path;
- AgentRun `attachment_count`, `attachment_bytes` and nullable
  `attachment_manifest_sha256`, constrained to either zero/zero/null or one to
  four positive bounded images plus one lowercase SHA-256;
- attachment storage-scope/session/workspace snapshot fields that distinguish
  legacy synthetic content from real-project content without rewriting V47
  history.

Existing rows expand to the legacy zero/null shape. The turn request adds
`clientRequestId` and ordered `attachmentIds`; old clients may omit both, but a
request with attachments requires a request identity. The fingerprint covers
the normalized message and ordered immutable attachment manifest. Repeating
the same request returns the original turn/run; changing message or attachment
identity/order under the same key returns conflict and creates nothing.

Turn, bindings and AgentRun commit in one transaction before any dispatch.
Retry reuses the failed run's origin turn and exact attachment manifest.

### 4. Bound new image selection without preventing safe retry

One turn accepts at most four distinct images and 32 MiB combined, while the
existing 16 MiB per-file and 256 MiB WorkSession limits remain. A new binding
requires the attachment to be indexed, integrity-compatible, unexpired, owned
by the same project/WorkSession/worker and stored under its accepted real
session/workspace identity. The same eligible image may be deliberately reused
by later turns.

An already bound failed run may be retried with the same retained bytes even
after `retainUntil`, because retry does not create new ownership. Missing or
changed content fails actionably; Atenea never silently drops an image and
runs text-only.

### 5. Add an exact additive `project-codex-v3` workload

Runs without images remain on v2. A profiled run with images uses
`project-codex-v3`, whose only additional field is an ordered
`attachments` array. Each element contains exactly attachment UUID, media
type, size and SHA-256. The top-level persisted remote session, project and
workspace remain the authority; filenames, bytes, storage identities, paths,
URLs and arbitrary options are forbidden.

The canonical attachment array participates in the AgentRun manifest and
worker request fingerprint. Duplicate dispatch returns the existing execution;
any attachment difference under one dispatch identity is rejected before a
process starts.

### 6. Extend attachment v1 compatibly instead of stranding rollback

The existing `worksession-attachment/v1` health and public stored-content
response shapes remain byte-compatible. A new authenticated capability endpoint
advertises `real-project-attachment/v1`. Real PUT requests use a remote
WorkSession UUID, `syntheticFixture=false` and fixed project/workspace ownership
headers. The updated service persists those extra values only in its private
sidecar while continuing to return the old public shape.

The backend requires the extension before real upload. The old service returns
404 and therefore fails closed for creation, but can still read and download
extended sidecars because the base protocol and content layout remain
compatible. Real content has no routine delete route; the existing exact
synthetic-only deletion remains unchanged.

### 7. Materialize only verified selected images inside the Codex sandbox

The fixed root-mediated Atenea runner derives every source path from its
configured attachment root plus remote session and attachment UUID. Before
launch it verifies regular non-symlink content and sidecar files, ownership,
mode, project, workspace, UUID, type, size and SHA-256 against the v3 request.

It copies only accepted images into an execution-labelled `0700` directory
beneath `/run/atenea/codex-images`, gives the bounded copies to the unprivileged
`jose` execution as `0600`, read-only binds only those files into Bubblewrap
and adds one fixed `--image` argument per ordered reference before either a new
or resumed Codex turn. The attachment root itself is never mounted into the
sandbox.

Materializations are removed in `finally` on success, rejection, timeout and
cancellation. Startup reconciliation removes a stale directory only when its
exact execution is absent or terminal; partial, foreign, unlabelled or
ambiguous paths remain untouched and block acceptance.

### 8. Put attachment state in the existing composer

An authenticated capability read model reports `READY` or one actionable
blocked reason, accepted types, current/max session bytes, per-turn count/byte
limits and policy revision. The current standalone panel no longer presents an
enabled-looking primary upload when creation will be rejected.

In a ready session, a secondary paperclip action and image clipboard paste use
the same upload path. A successful image is selected automatically for the
next message. Compact thumbnail chips show filename, size, upload/ready/error
state and removal. Send remains the sole primary action, includes the stable
client request UUID and exact ordered IDs, retains selection after a failed or
uncertain request and clears it only after Atenea confirms the accepted turn.
Historical turns display their bound image metadata and offer authenticated
download without placing bytes in conversation JSON.

Playwright must prove data, DOM and inspected visuals at `1440x900` and
`390x844`, including ready, globally blocked, project-blocked, uploading,
selected, over-limit, worker-unavailable and accepted-turn states.

### 9. Treat retention as a minimum keep and eligibility boundary

`TRANSIENT` 24 hours, `SESSION` 30 days and `EVIDENCE` 180 days remain. In this
change `retainUntil` means “do not delete before” and “do not create a new
binding after”; it does not authorize automatic deletion at expiry. This is
safer than adding an unproven destructive reconciler during first real use.
Quota and operator-visible usage bound new creation. General deletion requires
a later separately specified ownership/tombstone/backup policy.

### 10. Consume the external-backup gate with non-empty evidence

The accepted B2 configuration, exact source policy, timers and prior restore
remain prerequisites. After the separately authorized Atenea canary creates
one non-secret real screenshot, the rollout runs a bounded backup and
repository check, restores the exact snapshot to a new empty isolated path and
proves the screenshot sidecar/content size and SHA-256. No token, image content,
prompt, answer or provider credential enters evidence.

Failure disables new create/bind first and preserves the canary, existing
snapshots and metadata for diagnosis; retention/prune does not run from an
unverified state.

### 11. Deploy disabled and roll back disable-first

Before V62 reaches production, a protected current PostgreSQL backup is
restored in an isolated fixture, V62 applies twice, and both the new image and
an exact V62-aware rollback image start against it with all attachment gates
off. The private token is rotated/provisioned out of band, mounted read-only
into only the production backend and never printed or retained in evidence.

Rollout order is migration-compatible backend disabled, worker/storage
extension, runner v3, connectivity/capability checks, global gate, and finally
the exact `atenea` project gate. Existing sessions remain ineligible; the
canary uses a new clean WorkSession.

Rollback disables project then global create/bind, waits for or reconciles only
exact non-terminal v3 runs, restores the compatible backend/worker versions and
repeats idempotently. It never down-migrates V62, deletes attachment bytes,
rewrites bindings, moves sessions, changes routing or touches Beautips.

### 12. Keep acceptance evidence content-free

Evidence may retain commands, exit codes, finite timeouts, durations, counts,
dimensions, media types, sizes, policy/workload revisions and SHA-256 values.
It must not retain screenshot bytes, thumbnails, prompts, answers, filenames
that contain user data, credentials, tokens, storage paths, Codex thread/turn
IDs or provider payloads. UI screenshots use generated non-secret fixtures.

## Risks / Trade-offs

- [Configuration accidentally enables another project] → canonical registry,
  unknown-value startup failure, project gate and new-session snapshot.
- [HTTP response loss duplicates a turn] → client request UUID plus canonical
  message/image fingerprint and transactional replay.
- [Attachment metadata changes before dispatch] → immutable rows, persisted
  manifest and worker-side full identity/hash verification.
- [Codex sees the entire retained store] → copy only selected verified files
  and bind only those read-only into the per-run sandbox.
- [Materialized image survives a forced failure] → execution-labelled `/run`
  root, `finally` cleanup and ownership-safe startup reconciliation.
- [New protocol breaks rollback retrieval] → additive capability endpoint and
  unchanged v1 public/sidecar compatibility.
- [Real bytes exist before backup canary passes] → exact Atenea-only bounded
  canary, immediate backup/check/restore and disable-first preservation on
  failure.
- [Retention appears to promise immediate deletion] → explicitly define it as
  minimum retention/new-binding eligibility until a deletion change exists.
- [Web UI appears usable while backend is blocked] → capability state drives
  the affordance and exposes one actionable next step.

## Migration Plan

1. Capture clean synchronized Git and complete production/AX42/backup/non-impact
   fingerprints with attachment creation disabled and zero retained rows/bytes.
2. Implement V62, canonical eligibility, strict operator classification,
   idempotent turn binding and v3 dispatch behind default-off gates.
3. Implement and test the compatible attachment extension, exact runner
   materialization and cleanup without installing them.
4. Implement and visually validate the state-first web composer using
   synthetic fixtures.
5. Run focused and complete backend/web/worker suites twice from clean source.
6. Restore a protected production backup in isolation and prove new plus
   rollback image compatibility with V62.
7. With separate rollout authorization, deploy backend and worker changes
   disabled, provision the rotated private credential and verify no impact.
8. Enable only newly created Atenea sessions, have the operator upload one
   generated screenshot, prove exact Codex image understanding and same-thread
   continuation, then prove backup/check/isolated restore.
9. Exercise disable-first rollback twice, re-enable only after stable evidence,
   seal sanitized artifacts, validate OpenSpec, archive, commit and push.

## Open Questions

No design decision is left for the implementing agent. Production rollout,
credential provisioning and the operator-assisted web canary remain explicit
execution gates; absence of their separate authorization blocks activation but
not implementation or synthetic acceptance.
