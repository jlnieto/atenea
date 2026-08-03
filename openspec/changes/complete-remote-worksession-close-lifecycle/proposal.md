## Why

Atenea can currently mark a remote WorkSession `CLOSED` after reconciling Git
without finalizing the ownership that the same WorkSession still holds on
AX42. The first real Atenea session closed through the product retained its
project registration, `slot2`/`heavy1` admission and active allocation. The
next real session therefore could create its clean workspace but could not
acquire `slot2`.

The worker returned an authenticated HTTP 409 with a deterministic activation
failure, but `RemoteWorkerClient` discarded the structured response body and
`RemoteAgentRunCoordinator` treated every `RemoteWorkerException` as a worker
outage. The same impossible activation was attempted 81 times during the
two-minute reconciliation window and finally appeared to the operator as
“Remote worker remained unavailable”. A generic retry cannot resolve the
persisted owner and will repeat the same failure.

This leaves normal remote development dependent on manual host repair and
breaks the existing close, actionable-error, capacity and exact-ownership
contracts. The lifecycle must be closed in the product before Atenea can be
used professionally for consecutive real WorkSessions.

## What Changes

- Preserve and strictly validate the worker's bounded error envelope so
  transport unavailability, capacity waits, deterministic validation failures
  and ownership conflicts have distinct control-plane outcomes.
- Persist a remote-close lifecycle for every WorkSession through an additive
  migration; historical remote closures remain explicitly unverified rather
  than being assumed clean.
- Add an authenticated, closed-schema and idempotent worker operation that
  releases only the exact WorkSession's ephemeral runtime ownership, project
  registration and admission, then retires its allocation while retaining
  source, conversation, attachments, logs, artifacts and policy-retained data.
- Make normal WorkSession close reach `CLOSED` only after Git/delivery
  reconciliation and an exact worker release receipt both succeed.
- Reconcile an interrupted `CLOSING` operation after restart without creating,
  adopting or reassigning resources.
- Add a separately confirmed platform-administrator operation for exact legacy
  `CLOSED` sessions that predate the new close contract. It may reconcile only
  a session proven closed, terminal and exact-owned.
- Show the real blocking state and one applicable next action on web and
  Android. A deterministic ownership failure is never presented as a worker
  outage and generic retry is unavailable while the blocker remains.
- Roll out disabled by default, enable only canonical Atenea after a separate
  production authorization, reconcile the retained closed Atenea owner, and
  leave the failed real task preserved for an explicit operator retry.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `remote-worker-control`: add typed worker failures and an exact idempotent
  workspace-release protocol.
- `remote-work-continuity`: make remote close durable and restart-reconcilable.
- `codex-session-operations`: expose action-specific admission recovery and a
  confirmed closed-session reconciliation action.
- `worker-operational-safety`: define monotonic, fail-closed release of session
  ownership with retained evidence.
- `atenea-project-onboarding`: require consecutive canonical Atenea sessions to
  release and reacquire capacity through the product without host repair.

## Impact

- Atenea repository: additive V63 migration, remote worker client error model,
  AgentRun admission classification, WorkSession close/recovery orchestration,
  API/read models, web and Android operator surfaces, tests and runbooks.
- Programme repository: worker protocol, exact release mediator, installer,
  verifier, negative fixtures, rollback automation, evidence and ledger.
- AX42: one reviewed worker/mediator successor and one exact reconciliation of
  the retained canonical Atenea owner; no arbitrary command or resource target.
- Production: a separately authorized V63/backend/web rollout and configuration
  activation restricted to canonical Atenea. Preview, Beautips and every other
  project remain out of scope.
- Current incident: WorkSession 16 may be reconciled only after complete live
  proof. WorkSession 17, AgentRun 96, its operator turn and its attachment stay
  immutable; no prompt is retried automatically.
