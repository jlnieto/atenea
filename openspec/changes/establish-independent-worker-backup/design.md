## Context

AX42 has mirrored local disks, versioned bootstrap automation and canonical Git
remotes, but no external backup client, repository or restore evidence.
Authoritative worker state lives outside Git in exact ownership records,
attachment content and sanitized evidence. The existing
`/srv/atenea/backups-staging` path is local staging only and cannot satisfy
worker-loss recovery.

The programme explicitly keeps real-project retained state and Beautips routing
default-off until this gap is closed. Backup work must not inspect or retain
Codex authentication/history, plaintext credentials, tokens, cookies,
environment dumps, production data or the manual Beautips secret boundary.

## Goals / Non-Goals

**Goals:**

- Back up a reviewed, explicit allowlist of authoritative AX42 state to a
  provider independent from the worker host.
- Encrypt data client-side, isolate repository credentials and prevent secret
  material from entering logs or evidence.
- Define and automate daily backup, integrity verification, bounded retention
  and a repeatable restore to an empty isolated path.
- Produce sanitized, integrity-addressed evidence that is sufficient to lift
  the external-backup gate in a later Beautips activation change.
- Preserve production, administrative Beautips, all WorkSessions, routing and
  rootless slot resources throughout installation and acceptance.

**Non-Goals:**

- Enabling Beautips selection, execution, attachments or global remote routing.
- Backing up production PostgreSQL, the administrative Beautips database,
  manual project secrets, Codex state, Git mirrors/worktrees, caches, images,
  dependencies or ephemeral runtime resources.
- Replacing canonical Git remotes, RAID, monitoring or provider account
  recovery.
- Performing a destructive full-host restore during acceptance.

## Decisions

### Independent target and ownership

Use one private Backblaze B2 bucket in an operator-owned account, restricted to
an AX42-specific prefix and lifecycle policy. Backblaze is a different provider
and administrative failure domain from AX42. A Hetzner Storage Box was rejected
for this gate because it would simplify SFTP operations but would not provide
the same provider independence. Laptop folders were rejected because they are
not an always-available, managed external target.

The operator creates the account, bucket and bucket-scoped application key
outside this repository. No credential is pasted into chat, committed, copied
into evidence or included in the backup source set.

### Backup engine and credential boundary

Use the distribution-supported `restic` client with its exact installed version
recorded in evidence. Restic encrypts repository content client-side and
supports B2 without mounting the bucket. Root-owned mode-`0600` credential and
repository-password files live under `/etc/atenea-backup`; scripts consume them
without tracing or printing their values. They are expressly excluded from the
repository.

The B2 application key is scoped only to the backup bucket/prefix. Provider-side
file-version lifecycle supplies a recovery window for accidental deletion; its
exact policy is captured as normalized non-secret metadata before activation.

### Explicit source policy

A versioned policy builds a deterministic list from:

- authoritative records below `/srv/atenea/worker`, excluding deployed source,
  caches, locks and temporary files;
- `/srv/atenea/attachments-v1`;
- sanitized evidence below `/srv/atenea/artifacts`;
- explicitly approved non-secret worker configuration records.

The policy excludes `/home`, `/root`, Codex directories, repositories,
worktrees, runtime roots, backup credentials, `*.token`, `*.env`, keys,
cookies, dumps, sockets and the manual Beautips boundary. Inputs must be regular
files or declared directories beneath accepted canonical roots; symlinks,
missing roots, special files and path escapes fail closed before restic runs.
No content scan is used to classify credentials.

Each accepted run records only normalized paths, sizes, file counts, source
manifest SHA-256, snapshot ID, duration and exit status. It does not record
file contents, environment variables or command-line credentials.

### Schedule, retention and serialization

A systemd timer runs one daily backup with randomized delay and
`Persistent=true`; a weekly timer runs repository integrity checking. `flock`
serializes backup, check, retention and restore operations. Commands have
finite systemd/runtime timeouts and conservative CPU/I/O priority.

The repository keeps 14 daily, 8 weekly and 12 monthly snapshots, grouped by
the exact AX42 host and policy tags. Forget/prune runs only after a new snapshot
and integrity check pass. Repository deletion never runs from an ambiguous host
or tag set.

### Restore acceptance

Initial activation requires restoring the latest exact-policy snapshot into a
new empty directory beneath `/srv/atenea/backups-staging/restore-tests`. The
harness records the directory's immutable test identity, rejects symlinks or
pre-existing content, restores without overlaying any live root and compares
the restored normalized manifest and SHA-256 identities with the captured
source manifest.

Only after equality and repository check pass may the test directory be
removed by its exact recorded path and identity. Restore evidence is retained
under the programme artifact root with `SHA256SUMS`. A quarterly isolated
restore drill is documented, but automatic live restoration is never enabled.

### Activation and rollback boundary

Installing the client or timer does not lift any routing gate. The later
Beautips activation change must cite the accepted snapshot, check, restore and
provider-policy evidence.

Rollback disables timers first and removes only versioned units/scripts.
Existing remote snapshots and sanitized evidence are preserved. Credentials are
not deleted automatically, and no live worker state, attachment, artifact,
WorkSession or project resource is removed.

## Risks / Trade-offs

- **External account or key is unavailable** → Keep routing disabled; complete
  local tests only and stop at the explicit provisioning gate.
- **An overly broad source rule captures prohibited material** → Exact
  canonical-root allowlists and deny-path tests fail before backup; no
  content-based discovery or broad filesystem traversal is allowed.
- **A compromised AX42 can use its repository key** → Restrict the key to one
  bucket/prefix, retain provider-side file versions and keep account recovery
  authority outside AX42.
- **Retention removes the last usable snapshot** → Run forget/prune only after
  a newer backup and check pass; preserve provider versions and record selected
  snapshot IDs.
- **Restore overlays live state** → Restore accepts only a newly created empty
  isolated target and has no live-root mode.
- **External storage cost grows** → Bounded restic retention plus provider
  version lifecycle; measure repository size before enabling authoritative
  artifacts.

## Migration Plan

1. Capture clean Git, services, storage, routing and source-policy entry
   fingerprints while Beautips remains disabled.
2. Add and test the policy, wrapper, installer, systemd units and rollback in
   disposable local roots.
3. Have the operator provision the private B2 bucket and securely install the
   bucket-scoped key and repository password on AX42.
4. Install restic and the reviewed units without enabling project routing.
5. Initialize the repository, run the first backup and integrity check, then
   execute the empty-target restore comparison.
6. Repeat backup/check idempotently, exercise retention selection and rollback,
   and compare non-impact fingerprints.
7. Seal sanitized evidence, update the programme ledger, strictly validate and
   archive the change.

## Open Questions

The account, bucket name, region and application-key material must be created by
the operator outside Codex. Their values are operational inputs, not OpenSpec or
Git content.
