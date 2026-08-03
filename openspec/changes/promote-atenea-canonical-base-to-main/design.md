## Context

GitHub `main` is commit `7e8afa6c7039a70aea3b330234ddeabdcf2a6587`.
The published branch `feature/actualizar-conversacion-en-web` is commit
`8d5acdf9d593a2b0bafbf00fbef1ab2cc11cad9d`, contains 86 commits beyond
`main`, and has `main` as a direct ancestor. The reviewed attachment candidate
`codex/activate-atenea-real-worksession-attachments` is commit
`57b4123abaa4d66ba335fcb0cf4b64cd9fdd589d`, contains 32 further commits and
has the feature tip as an ancestor. Production already runs artifacts built
from the candidate, but GitHub and new WorkSessions still use the older refs.

The promotion changes source authority, not application behavior. It must
therefore preserve every accepted commit and must not trigger a production
deploy, routing change or runtime reconstruction.

## Goals / Non-Goals

**Goals:**

- make GitHub `main` contain both accepted layers with inspectable ancestry;
- make every canonical source declaration resolve to the same immutable main
  commit;
- prove one newly created Atenea WorkSession reports `main` and that its
  workspace is derived from that exact accepted commit;
- retain production, preview, Beautips, backups and unrelated worker state.

**Non-Goals:**

- changing application code, UI, attachment policy or routing;
- enabling attachments for Beautips or another project;
- deploying new images or migrating the production database;
- rewriting or deleting historical WorkSession, branch or pull-request state;
- starting a development runtime or submitting a Codex prompt.

## Decisions

### Promote the two ancestry layers in order

The feature branch is merged first. Only after its merge is visible in
GitHub `main` is the attachment candidate published and compared against the
new base. This makes the second pull request represent only the 32 descendant
commits and prevents reviewers from seeing the first layer twice.

### Forbid squash and force updates

Both pull requests use GitHub's normal merge-commit method. Fast-forward would
also preserve ancestry, but GitHub pull-request merges create an auditable
merge commit. Squash, rebase rewriting, force push and base-branch replacement
are forbidden because they would detach the candidate from its accepted
ancestor identity.

### Reconcile declarations only after GitHub is authoritative

The canonical checkout, `origin/HEAD`, project default base, AX42 mirror and
worker registry are updated only after both pull requests are merged and the
resulting main commit has both accepted tips as ancestors. Every mutation is
preceded by an exact fingerprint and has an inverse operation recorded.

### Treat retained sessions as immutable history

Closed WorkSessions keep their original base, branch, commit, delivery and
artifacts. Inspection established that the historical `DRAFT_BLOCKED` session
is not a stale open lock: V51 deliberately requires it to remain non-closed,
fingerprinted and linked to its clean replacement. Its replacement is already
closed, its active allocation marker is retired and it does not block later
sessions. Calling the ordinary close endpoint would violate the state
contract, so promotion preserves the retained draft byte-for-byte and records
its disposition rather than editing database state directly.

### Validate with a no-run canary

One clean Atenea WorkSession is created after reconciliation. Acceptance
requires persisted `baseBranch=main`, exact AX42 workspace ownership derived
from the new source pin, zero AgentRuns and no runtime start. This isolates
base-branch validation from application or Codex behavior.

## Rollback

Before each mutation, record old refs, project row, registry file hash,
mirror refs, services and resource inventories. Before either PR is merged,
rollback is ordinary PR closure and deletion only of the newly published
candidate ref. After merge, history is never rewritten: operational rollback
restores the project/worker base declarations to their recorded old values
while leaving GitHub main as an append-only accepted history. A newly created
canary may be closed through the normal endpoint but is never deleted.

Any failed ancestry, Git cleanliness, ownership, backup, RAID, production,
preview, Beautips or unrelated-resource check blocks the current task and
preserves sanitized evidence.

## Migration Plan

1. Seal entry fingerprints, ancestry and rollback values.
2. Prove the historical draft's completed mediated quarantine and preserve it.
3. Validate and merge feature to main with a merge commit.
4. Publish, validate and merge the descendant candidate to main with a merge
   commit.
5. Reconcile all canonical source declarations to the resulting main commit.
6. Create a clean no-run WorkSession and verify `main` end to end.
7. Seal evidence, update the programme ledger, archive and publish this change.

## Open Questions

None.
