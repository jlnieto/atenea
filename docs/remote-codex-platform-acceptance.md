# Remote Codex Platform Acceptance and Rollback

## Laptop parity matrix

| Capability | Laptop baseline | Worker acceptance evidence |
|---|---|---|
| Send prompt | Codex CLI from project cwd | Atenea accepts once, dispatches once and associates the correct WorkSession/worktree. |
| Continue after client closes | not available for local-only work | Run progresses and finishes after laptop network loss. |
| Resume conversation | local Codex session | Web and Android show the same turns, run, required action and artifacts. |
| Git edits | direct project checkout | Changes exist only in the session worktree/branch and publish uses existing delivery invariants. |
| Project control | `dev` commands | Compatible commands and `--json` control only the explicit session runtime. |
| Logs | journal/Compose | Live and retained session logs identify workload, timestamp and failure without secrets. |
| Manual browser | `localhost:<port>` | Private URL works; generated localhost tunnel works where declared. |
| Automated browser | local Playwright/Chromium | Worker validates DOM and inspected desktop/mobile screenshots. |
| Screenshot language | `/home/jose/Imágenes` mtime | latest/previous/N resolves ordered session artifacts. |
| Tooling | local JDKs, Maven, Node, Docker, skills | Manifest/context versions prove required tools and approved skills. |
| Publish/close | Atenea session workflow | PR publish, merge sync, reconciled close and cleanup pass unchanged. |

Every row is classified `equivalent`, `intentionally changed`, or `blocked`; default cutover requires no blocked row for the active project.

## Pilot end-to-end flow

1. Register the reviewed project remote/commit and validate its manifest.
2. Resolve a new WorkSession and confirm worker/workspace affinity.
3. Submit a bounded code/UI prompt from the laptop.
4. Confirm one AgentRun and one worker dispatch.
5. Close the laptop network connection after durable acceptance.
6. Observe progress and terminal state from Android.
7. Reopen the laptop and continue the same Codex thread/session.
8. Start the project runtime and inspect its private preview manually.
9. Run project tests/build and Playwright desktop/mobile evidence.
10. Ask for latest and last N screenshots and verify session ordering.
11. Publish the session, merge the PR and synchronize merge state.
12. Close the WorkSession and prove repository/runtime cleanup.
13. Confirm retained artifacts remain available after preview teardown.

## Capacity thresholds

The first production defaults are accepted only when representative tests meet all of the following:

- exactly four normal sessions can be active without a fifth starting outside the queue;
- no more than two declared heavy operations execute simultaneously;
- worker heartbeat age remains below 15 seconds during the test;
- a new SSH `true` command completes within 2 seconds at the 95th percentile and never times out;
- control/cancellation acknowledgement is visible within 5 seconds;
- graceful cancellation finishes within 30 seconds or forced cleanup and terminal explanation finish within 60 seconds;
- lightweight status reads complete within 3 seconds at the 95th percentile;
- at least 8 GiB memory remains available to the host under the representative four-session workload;
- no host OOM, RAID degradation, cross-session write, duplicate dispatch or duplicate terminal turn occurs;
- root filesystem remains below 80% during acceptance and cleanup returns expected temporary usage;
- every unrelated session continues making observable progress while another session builds or cancels.

Thresholds may be tightened after measurement. Relaxing one requires a recorded decision and repeat capacity evidence.

## Recovery matrix

| Failure | Expected state | Acceptance |
|---|---|---|
| Atenea backend restart | remote run reconciling then running/terminal | no duplicate dispatch; observation restored within 90 seconds |
| Worker service restart | lease reconciliation | runtime recovered or failed once with retained workspace/artifacts within 60 seconds |
| Two-minute control-plane/worker partition | `RECONCILING`/`UNREACHABLE` | worker does not start duplicate; terminal delivery is idempotent after reconnect |
| Codex thread missing | explicit stale-thread recovery | one replacement thread; prior SessionTurns retained |
| Cancellation timeout | `CANCELLING` then forced terminal state | session processes removed within 60 seconds; unrelated sessions unaffected |
| Runtime health failure | preview blocked | reason, logs and next action visible; no ready link |
| Disk above 80% | warning and admission review | no silent growth; cleanup candidate list is ownership-checked |
| Disk above 90% | critical/admission stop for disk-heavy work | SSH/control remain responsive and authoritative state is preserved |
| One RAID member fails | degraded critical alert | `[U_]`/`[_U]` detected; service decision and replacement runbook visible |
| Orphan runtime after restart | quarantined/cleaned | active/recoverable ownership checked before removal |
| Complete worker loss | new routing disabled | Git plus external backup restores declared non-recreatable state on replacement host |

## Web and Android state evidence

For each of `QUEUED`, `STARTING`, `RUNNING`, `WAITING_ACTION`, `CANCELLING`, `RECONCILING`, `PREVIEW_READY`, `SUCCEEDED` and `FAILED`:

- the current state is visible without scrolling on the primary session screen;
- the next action or reason is concise and actionable;
- worker/queue technical detail is available without dominating the primary message;
- reconnecting does not create another run;
- Android and web derive state from the same control-plane model;
- applicable completion/failure/action notification is deduplicated;
- UI changes are verified with DOM assertions and inspected 1440x900 and 390x844 screenshots.

## Rollback matrix

| Rollback point | New sessions | Active worker sessions | Preserved state | Action |
|---|---|---|---|---|
| secure baseline | old executor | none | host audit | disable new services and restore previous known-good ssh/firewall only through verified session |
| runtime contract | old executor | synthetic only | mirrors/worktrees for inspection | stop runner and remove proven-owned dummy resources |
| remote routing | old executor | remain pinned | WorkSession, branch, worktree, turns, run audit | reconcile or explicitly cancel; never silently move |
| private previews | configured executor | unchanged | browser artifacts | disable routes/UI affordance and stop preview only |
| one project onboarding | old executor for that project | pinned until closed/recovered | project branch and session audit | disable project worker eligibility, not the whole worker |
| default cutover | previous executor for newly opened sessions | finish on assigned target | all durable state | flip routing default, observe, then diagnose |

Database changes use expand/contract. Rollback never depends on destructive down migrations or deletion of an active worktree.

## Evidence package per phase

- OpenSpec strict validation;
- code/test/build results using canonical Atenea scripts;
- configuration diff without secrets;
- health and exposure checks;
- desktop/mobile browser evidence when UI changes;
- rollback execution result;
- known limitations and observation duration;
- updated programme ledger and exact next entry gate.
