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
- Resolve the one historical non-runnable draft through the normal
  WorkSession contract, without adopting or deleting ambiguous state.
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

- GitHub Atenea repository: two ordered pull requests and merge commits; no
  force push, squash, history rewrite or branch deletion.
- Atenea control plane: project default base changes from the retained feature
  branch to `main`; no schema or production routing change.
- AX42: canonical mirror/registry source pin advances to the resulting `main`
  commit; no runtime or unrelated slot is started, reassigned or rebuilt.
- Programme repository: this contract, evidence and ledger only.
