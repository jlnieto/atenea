## Context

Phase 4 proved durable remote execution with target affinity and idempotent
reconciliation. Attachments remain global files written by
`MobileUploadService`, with no WorkSession identity, database index, integrity
identity or read authorization. The current API also returns host paths.

The programme requires worker-resident content, Atenea/PostgreSQL indexing,
WorkSession-only screenshot ordering and survival beyond ephemeral preview
resources. External backup remains mandatory before non-recreatable
authoritative artifacts are enabled for real projects.

## Goals / Non-Goals

**Goals:**

- Bind every attachment immutably to one WorkSession and project.
- Optionally bind an attachment to an AgentRun from the same WorkSession.
- Store content on AX42 without exposing arbitrary worker paths.
- Make upload and exact retrieval authenticated, bounded and integrity checked.
- Make latest/previous/N screenshot resolution deterministic.
- Prove retained content survives disconnect, attachment-service restart and
  simulated preview teardown.

**Non-Goals:**

- Enable production attachment routing or store real-project authoritative
  artifacts.
- Implement preview allocation, public URLs or localhost compatibility.
- Implement terminal cleanup or external backup credentials.
- Let clients select storage paths, execute commands or browse AX42.
- Delete legacy retained uploads or perform a destructive schema rollback.

## Decisions

### 1. PostgreSQL owns metadata; AX42 owns content bytes

Flyway V47 creates `work_session_attachment`. Atenea assigns one UUID and stores
the WorkSession, project, optional AgentRun, source, kind, original filename,
media type, byte count, retention class, creation time, SHA-256 digest, worker
and opaque storage identity. The content service stores bytes beneath an
ownership-derived root and accepts no client path.

The worker response is accepted only when the returned session, attachment,
size and digest match the control-plane request and streamed bytes.

### 2. Use a narrow authenticated attachment protocol

The private `worksession-attachment/v1` service accepts create, metadata,
content and exact synthetic-fixture deletion. Requests use the same
root-owned-secret pattern and Tailscale/UFW boundary as the AgentRun worker.
Create is idempotent by attachment UUID and rejects conflicting reuse.

No endpoint lists filesystem directories, accepts commands or exposes absolute
paths. Deletion requires the exact synthetic acceptance namespace plus
WorkSession and attachment identities.

### 3. Enforce safe defaults before content reaches retained storage

The default per-file limit is 16 MiB and the retained-session quota is 256 MiB.
Allowed content types are PNG, JPEG, WebP, plain text, JSON, PDF and ZIP trace
bundles. Empty content, executable/archive types outside the allowlist,
ambiguous sniffed types and mismatched declared content types fail closed.

Filenames are display metadata only, normalized and length bounded. The worker
streams through a temporary owned file, computes SHA-256 and atomically renames
only after validation.

### 4. Define explicit retention classes without premature deletion

`TRANSIENT` means 24 hours, `SESSION` means 30 days and `EVIDENCE` means 180
days. Phase 5 records `retainUntil` but does not run general deletion.
Non-terminal session attachments are never eligible. Rollback disables new
uploads and preserves indexed content.

Only exact synthetic fixtures created by acceptance may be deleted during
cleanup. Production defaults and external backup activation remain blocked
until representative Phase 5/6 measurements and a restore-tested independent
backup target exist.

### 5. Order screenshots by immutable metadata

Within one WorkSession and optional source, screenshots sort by `createdAt`
descending and attachment UUID descending as the stable tie-breaker. `latest`
is offset zero, `previous` is offset one and `last N` is a bounded prefix.
Neither project-global folders nor file mtimes participate.

### 6. Authorize through the WorkSession boundary

The existing authenticated operator boundary must resolve the requested
WorkSession. AgentRun registration additionally proves the run belongs to that
session. Responses expose opaque IDs and download metadata, never storage
paths. Missing, foreign and cross-session identities return an actionable
not-found/forbidden state without revealing whether foreign bytes exist.

### 7. Replace the global upload affordance compatibly

The new web/mobile flow requires an explicit WorkSession. The legacy global
upload endpoint remains unavailable when the Phase 5 feature is enabled, so a
client cannot bypass ownership. The operator UI shows the current session,
limit, accepted types, upload state and retained attachments in one compact
surface.

### 8. Keep activation synthetic and default-off

Configuration defaults attachment routing to false and requires an exact
synthetic project allowlist plus a compatible healthy worker. Phase 5
acceptance uses a disposable control-plane database and synthetic session.
Production is fingerprinted only and remains unchanged.

## Risks / Trade-offs

- [Metadata exists without content] → upload to AX42 first, verify identity,
  then index; compensate exact unindexed synthetic content on failure.
- [Content exists without metadata after a timeout] → idempotent attachment UUID
  and reconciliation by exact identity.
- [Cross-session disclosure] → session-scoped queries and opaque storage IDs.
- [MIME spoofing] → declared-type allowlist plus signature/text validation.
- [Quota race] → transactional metadata quota check and worker-side byte limit.
- [Rollback loses evidence] → disable create only; preserve list/download and
  retained content.
- [RAID mistaken for backup] → prohibit real authoritative activation until an
  external restore-tested target is configured.

## Migration Plan

1. Retain entry fingerprints and approve the storage, access, limits and
   retention contract.
2. Commit and validate this OpenSpec change with production activation off.
3. Add V47, persistence, authorization, ordering and quota tests.
4. Implement and test the narrow AX42 attachment service.
5. Add the Atenea client, create/list/download and screenshot-resolution APIs.
6. Replace the global web/mobile upload affordance with WorkSession scope.
7. Install the private service on AX42 without starting any project runtime.
8. Exercise a complete synthetic session, restart and teardown survival,
   negative authorization/limit/type cases and exact cleanup.
9. Execute rollback twice, compare non-impact fingerprints, retain checksummed
   evidence and archive the change.

## Rollback

Disable creation for all projects. Keep metadata and exact download available
for retained attachments. Do not delete V47, retained content or audit
evidence. Stop/disable the attachment service only after no pending create or
reconciliation exists. Remove acceptance fixtures only by their recorded exact
synthetic identities.
