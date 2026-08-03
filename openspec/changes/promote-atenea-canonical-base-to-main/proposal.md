## Why

The canonical Atenea project still opens WorkSessions from
`feature/actualizar-conversacion-en-web` even though GitHub already declares
`main` as the default branch. The feature branch contains the accumulated
platform work and the real-attachment candidate adds one further descendant
layer. Leaving those accepted changes outside `main` makes every new session
start from an obsolete project policy and keeps source authority split across
three refs.

## What Changes

- Integrate the published feature branch into GitHub `main` without squash.
- Publish and integrate the descendant real-attachment branch into the
  resulting `main`, also without squash.
- Reconcile the canonical Atenea checkout, mirror, project default and AX42
  project registry to the exact resulting `main` commit.
- Publish one bounded source-identity successor that changes Atenea's
  application identity, runtime manifest and the complete reviewed AX42
  request/runner/validation authority from the retained feature branch to
  `main`, without changing product behavior.
- Reconcile the exact stale active worker ownership retained by the already
  closed previous Atenea canary before admitting its main-based replacement.
- Confirm that the one historical non-runnable draft is already quarantined
  through the accepted retained-draft contract and preserve it unchanged,
  without adopting, closing or deleting its reviewable state.
- Create one clean WorkSession proving that new Atenea work now starts from
  `main` while routing, production, preview, Beautips and unrelated worker
  resources remain unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `atenea-project-onboarding`: make the reviewed GitHub `main` history the
  single canonical source and default base for new Atenea WorkSessions.
- `remote-work-continuity`: reconcile persisted project and worker source
  identity without rewriting retained WorkSession history.

## Impact

- GitHub Atenea repository: the two ordered history pull requests plus one
  bounded identity-only successor; no force push, squash, history rewrite or
  branch deletion.
- Atenea control plane: the reviewed identity-only backend successor is
  deployed, then the project default base changes from the retained feature
  branch to `main`; no schema or production routing policy changes.
- AX42: the complete reviewed Atenea worker identity advances to `main`, the
  exact closed-canary registration/admission residue is released, and the
  canonical mirror/registry pin advances to the resulting commit; no runtime
  or unrelated slot is started, reassigned or rebuilt.
- Programme repository: contract, worker identity sources, tests, evidence
  and ledger.
