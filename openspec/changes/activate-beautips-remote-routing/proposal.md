## Why

Beautips has passed complete disposable onboarding and AX42 now has an
independent restore-tested backup, but production Atenea still defaults every
new Beautips WorkSession to local execution. Enabling the existing flags alone
is unsafe because the control plane does not yet create and persist the exact
worker workspace, allocation and project registration required before the
first remote dispatch.

## What Changes

- Add an authenticated, idempotent and project-scoped worker operation that
  provisions one exact Beautips WorkSession from its persisted routing
  identity before dispatch.
- Make Atenea persist the remote session first, then reconcile its AX42
  workspace before accepting the first remote AgentRun.
- Install the worker and control-plane credential boundary without exposing or
  duplicating secret values.
- Enable only the global remote-worker gate and the exact Beautips gate in
  production Atenea; keep the generic project gate and every other project
  disabled.
- Open one real production-control-plane Beautips WorkSession, execute a small
  deterministic turn, verify continuity and private preview, and prove
  disable/rollback/re-enable without touching the administrative slot 1
  runtime or production application data.
- Retain sanitized evidence, update the programme ledger, strictly validate,
  archive, commit and push before declaring Beautips ready for normal use.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `remote-worker-control`: Require exact, durable and idempotent project
  workspace provisioning before a real-project AgentRun can dispatch.
- `beautips-project-onboarding`: Promote accepted Beautips from disabled
  onboarding state to one explicitly enabled production-control-plane route
  with reversible project-scoped activation.

## Impact

- Programme branch: worker provisioning mediator/API, installers, tests,
  OpenSpec artifacts, evidence and programme ledger.
- Atenea application branch: remote worker client, WorkSession/AgentRun
  orchestration, focused tests and production compose configuration.
- AX42: one managed Beautips WorkSession in an admitted slot outside slot 1;
  the administrative runtime remains foreign.
- Atenea production/control plane: one bounded backend rollout enabling only
  exact Beautips remote selection; no endpoint, PostgreSQL schema, deployment
  authority or unrelated project routing change.
- GitHub Beautips: only the normal session branch and reviewed delivery
  produced by the accepted operator turn.
