## Why

Remote AgentRun continuity is proven, but Atenea still has one global mobile
upload directory and a mutable `latest.json`. That model can mix projects,
expose worker paths and cannot prove which WorkSession or AgentRun owns an
operator upload, screenshot, trace or report.

Phase 5 must establish a narrow attachment boundary before private previews or
real-project onboarding can retain browser evidence on AX42.

## What Changes

- Add immutable WorkSession attachment metadata indexed by Atenea/PostgreSQL
  and content stored beneath an ownership-scoped AX42 root.
- Register operator uploads, screenshots, traces and reports against one
  WorkSession and, when applicable, one AgentRun.
- Store source, creation time, media type, size, retention class, SHA-256
  identity and storage identity without returning arbitrary filesystem paths.
- Add authenticated upload, ordered listing and exact download APIs usable by
  the Atenea web and mobile surfaces.
- Resolve latest, previous and last-N screenshots deterministically inside one
  WorkSession and optional source.
- Reject unauthorized, unsupported and oversized inputs before indexing them,
  and preserve retained attachments across disconnect, service restart and
  preview teardown.
- Keep production attachment activation disabled until external backup for
  authoritative non-Git artifacts is configured and restore-tested.

## Capabilities

### New Capabilities

- `worksession-attachments`: Defines attachment ownership, storage, indexing,
  ordering, access control, limits, retention and integrity semantics.

### Modified Capabilities

- `remote-work-continuity`: Makes retained attachment retrieval and service
  restart continuity concrete.
- `private-development-preview`: Makes browser artifact registration consume
  the WorkSession attachment contract without enabling previews in Phase 5.

## Impact

- Atenea repository: additive Flyway migration, attachment persistence/service
  and authenticated API, replacement of the global mobile-upload affordance,
  web/mobile read models and tests.
- Programme repository: private AX42 attachment service, install/verify tools,
  synthetic acceptance harness, rollback guide and checksummed evidence.
- AX42: one ownership-scoped retained attachment root and one private,
  authenticated service; no project runtime or preview is started.
- Production: no deployment, endpoint, PostgreSQL, routing, secret or
  configuration change. The new capability remains disabled there.
