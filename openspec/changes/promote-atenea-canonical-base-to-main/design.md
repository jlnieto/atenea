## Context

GitHub `main` is commit `7e8afa6c7039a70aea3b330234ddeabdcf2a6587`.
The published branch `feature/actualizar-conversacion-en-web` is commit
`8d5acdf9d593a2b0bafbf00fbef1ab2cc11cad9d`, contains 86 commits beyond
`main`, and has `main` as a direct ancestor. The reviewed attachment candidate
`codex/activate-atenea-real-worksession-attachments` is commit
`57b4123abaa4d66ba335fcb0cf4b64cd9fdd589d`, contains 32 further commits and
has the feature tip as an ancestor. Production already runs artifacts built
from the candidate, but GitHub and new WorkSessions still use the older refs.

Fresh promotion validation found that two candidate-only integration tests
left their synthetic worker and durable activation-barrier fixtures behind.
The bounded successor `d0036e427bae2d6753d81a4725971f2fb91c5add` adds only
exact owner-aware fixture cleanup to those two tests; no application or
runtime source changes. It is the reviewed promotion head after passing the
complete canonical gate.

The promotion changes source authority, not product behavior. The first
reconciliation attempt proved that source authority is compiled into both the
backend and the reviewed AX42 contracts, so a configuration-only switch would
fail closed. The operator explicitly authorized the bounded identity-only
successor, AX42 reconciliation and production rollout on 2026-08-03. Every
accepted commit remains append-only and no runtime or routing-policy change is
permitted.

## Goals / Non-Goals

**Goals:**

- make GitHub `main` contain both accepted layers with inspectable ancestry;
- make every canonical source declaration resolve to the same immutable main
  commit;
- update the complete compiled application/worker identity and manifest as
  one validated transition rather than creating mixed authorities;
- release only the exact stale active worker ownership of the already closed
  previous Atenea canary while retaining its worktree, allocation sidecar,
  Git, logs, attachments and artifacts;
- prove one newly created Atenea WorkSession reports `main` and that its
  workspace is derived from that exact accepted commit;
- retain production, preview, Beautips, backups and unrelated worker state.

**Non-Goals:**

- changing application behavior, UI, attachment policy or routing policy;
- enabling attachments for Beautips or another project;
- migrating the production database;
- rewriting or deleting historical WorkSession, branch or pull-request state;
- starting a development runtime or submitting a Codex prompt.

## Decisions

### Promote the two ancestry layers in order

The feature branch is merged first. Only after its merge is visible in
GitHub `main` is the attachment candidate published and compared against the
new base. This makes the second pull request represent only the 32 functional
descendant commits plus the one validation-only fixture-isolation correction
and prevents reviewers from seeing the first layer twice.

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

### Treat compiled source identity as one authority set

The backend project identity, runtime manifest, AX42 AgentRun worker, project
runner, activation/validation/multi-repository mediators, installer and v1-v3
request schemas all name the canonical branch. They move to `main` together in
one bounded successor. The runtime manifest receives a new immutable SHA-256,
all focused and complete suites run before merge, and the deployed backend and
worker artifacts must match the reviewed successor before the persisted
project default changes.

### Retire only exact closed-canary active ownership

Entry inspection did not decode nested admission state and therefore missed
that closed WorkSession 15 still holds `slot2/heavy1` and the sole enabled
worker registration. Reconciliation may release only that exact registered
session after proving its control-plane `CLOSED` state, zero non-terminal runs,
zero runtime resources, exact registry/allocation hashes and exact admission
identity. The registry entry and active admission are removed through the
reviewed mediated contracts; its worktree, allocation sidecar, Git, logs,
attachments and artifacts remain immutable.

### Roll out identity without changing routing policy

AX42 receives only reviewed identity artifacts and its exact main registry;
only the AgentRun worker is restarted because it embeds the branch constant.
The backend is rebuilt from the merged identity successor and only the
production backend is recreated. Existing global/project routing gates,
attachment policy, database schema, preview and Beautips remain unchanged.

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

### Close activation installation as one verified dependency

The no-run canary exposed that the AgentRun worker installer verified its
service command but not the separately installed Atenea workspace activator.
The reviewed release source already contains the correct `main` activator;
only the installed copy remained at the accepted feature predecessor. The
corrective rollout therefore keeps the dedicated activation installer as the
sole writer, adds an exact whole-bundle preflight that accepts only all-absent,
all-current or the one reviewed predecessor-program plus current dependencies,
and rejects partial, symlinked, foreign or ambiguous state before writing.

The AgentRun installer now treats the complete installed activation bundle as
a mandatory preflight and verification dependency. Its deployment order is
explicit: apply and verify the dedicated activation bundle first, then apply
or verify the AgentRun worker. The correction itself requires no service
restart because each workspace request invokes the mediator by path; if an
unexpected runtime condition requires a restart, authorization is limited to
the AgentRun worker only.

Rollback is not the generic first-install removal action. Before upgrade, the
exact installed mediator, sudoers boundary and dependency bundle are copied to
a checksum-sealed private release directory. Operational rollback may restore
only those same paths after matching their expected post-install identities.

## Rollback

Before each mutation, record old refs, project row, registry and installed
worker file hashes, mirror refs, services and resource inventories. Before
any PR is merged,
rollback is ordinary PR closure and deletion only of the newly published
candidate ref. After merge, history is never rewritten: operational rollback
restores the prior backend image, project/worker base declarations and exact
installed worker artifacts to their recorded old values while leaving GitHub
main as append-only accepted history. The closed canary's released active
ownership is not reintroduced automatically. A newly created canary may be
closed through the normal endpoint but is never deleted.

Any failed ancestry, Git cleanliness, ownership, backup, RAID, production,
preview, Beautips or unrelated-resource check blocks the current task and
preserves sanitized evidence.

## Migration Plan

1. Seal entry fingerprints, ancestry and rollback values.
2. Prove the historical draft's completed mediated quarantine and preserve it.
3. Validate and merge feature to main with a merge commit.
4. Publish, validate and merge the descendant candidate to main with a merge
   commit.
5. Publish and validate the bounded compiled source-identity successor.
6. Release only the exact closed-canary active ownership.
7. Reconcile and roll out all canonical source declarations to the successor.
8. Correct and reconcile the exact stale installed activation bundle.
9. Create a clean no-run WorkSession and verify `main` end to end.
10. Seal evidence, update the programme ledger, archive and publish this change.

## Open Questions

None.
