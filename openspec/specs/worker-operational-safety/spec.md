# worker-operational-safety Specification

## Purpose
TBD - created by archiving change establish-remote-codex-platform-program. Update Purpose after archive.
## Requirements
### Requirement: Reproducible hardened worker baseline
Worker users, packages, SSH policy, firewall, private networking, runtime services and monitoring SHALL be defined by versioned automation and validated for drift.

#### Scenario: Fresh worker is provisioned
- **WHEN** the baseline automation is applied to a supported Ubuntu host
- **THEN** it produces the declared security and service state without depending on undocumented manual shell history

### Requirement: Private service exposure

Worker API, Codex App Server, runtime control endpoints, databases and previews MUST
bind only to loopback, isolated runtime networks or approved private
interfaces; this binding requirement MUST be enforced before activation.
Development databases SHALL have no host firewall admission and
their loopback endpoint SHALL derive only from a persisted WorkSession
allocation. Preview ingress SHALL use a bounded dedicated port range admitted
only on the private interface, SHALL forward only to allocation-derived
loopback targets and SHALL expose no arbitrary proxy operation.

#### Scenario: Public interface is scanned

- **WHEN** an unauthenticated Internet client scans the worker while a
  synthetic database or preview is ready
- **THEN** only explicitly approved bootstrap or break-glass services are
  reachable and neither database nor preview content is exposed

#### Scenario: Arbitrary database endpoint is requested

- **WHEN** a database request supplies a host, port, socket, database name,
  volume or network not derived from persisted WorkSession ownership
- **THEN** the worker rejects it before invoking a client or container command
  and preserves every existing database

### Requirement: Least-privilege execution

Routine Codex and project workloads SHALL run without host root authority or
credentials and capabilities unrelated to their session. Managed runtimes and
real Codex AgentRuns MUST execute in a sandbox that
prevents access to the host container daemon, host root filesystem, unrelated
workspaces, production credentials and production network authority. The real
workload runner SHALL resolve all filesystem/runtime identities from persisted
exact WorkSession ownership and SHALL invoke only reviewed manifest operations.

#### Scenario: Runtime command is compromised

- **WHEN** a session process attempts a privileged host operation
- **THEN** OS and runtime controls prevent it from gaining host root or
  controlling another session

#### Scenario: Real Atenea prompt runs

- **WHEN** an exact onboarded Atenea WorkSession is admitted
- **THEN** its Codex process can change only the intended session worktree and
  declared artifacts while daemon sockets, other workspaces and production
  authority remain unavailable

#### Scenario: Workload requests extra authority

- **WHEN** a request or prompt attempts to supply or reach an arbitrary command,
  path, remote, endpoint, environment, daemon socket or foreign workspace
- **THEN** the worker rejects or sandboxes the operation without changing the
  target or any unrelated resource

### Requirement: Resource protection
Every execution and project runtime SHALL have enforceable CPU, memory, PID and storage policies, and the host SHALL retain recovery headroom.

#### Scenario: Session exhausts its memory allocation
- **WHEN** a workload exceeds its configured memory boundary
- **THEN** that workload is terminated or degraded according to policy while SSH, monitoring and unrelated sessions remain responsive

### Requirement: RAID and disk health monitoring
The worker SHALL monitor RAID membership/resynchronization, NVMe health and filesystem capacity and SHALL alert on degraded arrays, device errors or capacity thresholds.

#### Scenario: One RAID member fails
- **WHEN** a mirrored NVMe member becomes unavailable
- **THEN** the worker reports degraded storage urgently while preserving service on the remaining member where safe

### Requirement: External backup and restore evidence

Critical non-Git configuration MUST be backed up with already-sealed secret
material, authoritative ownership metadata and non-recreatable artifacts client-side
encrypted to a private target whose provider and administrative recovery
boundary are independent from the worker. Retention MUST keep 14 daily, 8
weekly and 12 monthly exact-host snapshots and MUST delete nothing unless a
newer snapshot and repository integrity check have passed.

The source set MUST be an explicit canonical-root allowlist. Plaintext
credentials, tokens, cookies, environment dumps, Codex authentication/history,
production data, manual project secrets, Git-recreatable repositories and
worktrees, caches, dependencies, runtime images and ephemeral resources MUST
be excluded. Repository credentials MUST remain outside Git, backup content,
commands, logs and evidence.

Initial activation and periodic acceptance MUST restore an exact snapshot to a
new empty isolated target, prove normalized manifest and SHA-256 identity, and
retain sanitized integrity evidence. Backup installation or snapshot creation
alone MUST NOT enable real-project retained state or routing.

#### Scenario: Worker is lost completely

- **WHEN** a replacement host is provisioned
- **THEN** documented automation plus canonical Git and the independent
  encrypted repository restore the declared authoritative worker contract
  without relying on either failed RAID member

#### Scenario: Source policy contains a prohibited or ambiguous path

- **WHEN** backup input resolves outside an approved canonical root or names a
  symlink, credential boundary, Codex state, production-like data or
  regenerable runtime state
- **THEN** backup fails before repository mutation and preserves the source,
  existing snapshots and every routing gate

#### Scenario: External target or integrity check is unavailable

- **WHEN** authentication, upload, verification or repository integrity does
  not complete within its finite bound
- **THEN** the run fails actionably, retention does not execute and
  authoritative real-project state remains disabled

#### Scenario: Exact snapshot is restored for acceptance

- **WHEN** the latest exact-host and policy-tagged snapshot is restored to a
  newly created empty isolated path
- **THEN** normalized path, size and SHA-256 identities match the accepted
  source manifest without overlaying or changing live worker state

#### Scenario: Retention ownership is incomplete or ambiguous

- **WHEN** retention cannot prove the exact repository, worker identity, policy
  tag, newer successful snapshot and passing integrity check
- **THEN** no snapshot or repository object is deleted and the ambiguity is
  reported in sanitized evidence

#### Scenario: Backup acceptance passes

- **WHEN** provider policy, encrypted snapshot, repository check, empty-target
  restore, repeated execution, retention selection and rollback evidence all
  pass
- **THEN** the programme may lift only the external-backup prerequisite in a
  separate project-routing change

### Requirement: Operational observability
Operators SHALL be able to inspect worker health, slot use, queue depth, run age, CPU, memory, disk, runtime failures, preview state and cleanup failures from Atenea or linked diagnostics.

#### Scenario: Run makes no progress
- **WHEN** a run exceeds its workload-specific progress threshold
- **THEN** Atenea surfaces a stale warning with worker, session and recovery action rather than silently waiting forever

### Requirement: Safe garbage collection

The worker SHALL identify and clean orphaned containers, worktrees, ports,
database resources, preview projections, real AgentRun processes and temporary
artifacts only after proving they are not owned by an active or recoverable
session. Preview, database and real-project cleanup SHALL require the complete
immutable dispatch, project, workspace, worker, allocation and resource
ownership tuple and SHALL fail closed on absent, partial, foreign,
production-like or ambiguous ownership.

#### Scenario: Exact synthetic database reaches cleanup

- **WHEN** cleanup validates the complete terminal synthetic database record,
  rootless slot, container, network, volume and snapshot identities
- **THEN** it removes only those exact ephemeral resources while retaining Git
  and sanitized evidence

#### Scenario: Foreign database-like resource exists

- **WHEN** cleanup observes an unlabelled, partially labelled, foreign,
  production-like or ambiguously owned database resource
- **THEN** it rejects the candidate unchanged and reports the ownership reason

#### Scenario: Exact closed Atenea session reaches cleanup

- **WHEN** the accepted Atenea WorkSession is terminal, delivery-reconciled and
  every exact runtime/preview/database/process identity validates
- **THEN** cleanup removes only its declared ephemeral resources, releases its
  admission and retains Git, delivery and sanitized evidence

#### Scenario: Foreign project-like resource exists

- **WHEN** cleanup observes an unlabelled, partial, foreign or ambiguous
  project/AgentRun resource
- **THEN** it rejects that candidate unchanged and reports the ownership reason

### Requirement: Break-glass and rollback
The platform SHALL maintain tested key-based break-glass access and a rollback procedure that can stop new worker routing without deleting active Git or audit state.

#### Scenario: Private network control is unavailable
- **WHEN** Tailscale or its coordination path cannot be used
- **THEN** an authorized operator can reach the host through the documented break-glass path and disable or repair worker services

### Requirement: Capacity acceptance gate
The AX42 SHALL NOT become the default executor until representative four-session concurrency, two-heavy-workload concurrency, cancellation, restart and control-plane-disconnect exercises pass defined responsiveness and integrity thresholds.

#### Scenario: Capacity test causes control loss
- **WHEN** the acceptance workload makes SSH, cancellation, heartbeat or unrelated session progress exceed its threshold
- **THEN** default cutover is blocked and limits are reduced or the architecture is corrected

### Requirement: Anti-lockout administrative transition
The bootstrap SHALL create and verify a named key-based sudo administrator in an independent SSH connection before restricting sshd or enabling the firewall.

#### Scenario: Named administrator cannot connect
- **WHEN** the independent key-based login or sudo verification fails
- **THEN** bootstrap stops before changing the active sshd or firewall policy

### Requirement: Key-only SSH baseline
The worker MUST disable password, keyboard-interactive and X11 SSH access, retain public-key authentication, restrict root to public-key break-glass and validate configuration before reload.

#### Scenario: SSH hardening is applied
- **WHEN** the generated drop-in passes `sshd -t` and the named administrator connection is proven
- **THEN** sshd is reloaded and both named administration and root break-glass are re-verified

#### Scenario: Password login is attempted
- **WHEN** a client offers password authentication after hardening
- **THEN** sshd does not accept it for routine or root access

### Requirement: Deny-by-default host firewall activation
The worker SHALL deny unsolicited inbound traffic by default and allow only explicitly approved bootstrap or private-interface services.

#### Scenario: Firewall is enabled
- **WHEN** SSH access has been proven and the staged UFW policy passes verification
- **THEN** the policy is enabled and a fresh SSH connection succeeds before the original recovery session is closed

### Requirement: Controlled private-network enrollment
Tailscale enrollment MUST use an operator-approved production tailnet, tagged worker identity, least-privilege policy and documented recovery ownership; reusable personal enrollment credentials MUST NOT be committed or logged.

#### Scenario: Tailnet ownership is unresolved
- **WHEN** the Tailscale package is installed but ownership or recovery administration has not been approved
- **THEN** the host remains unenrolled and bootstrap reports the explicit gate without weakening public break-glass access

### Requirement: Canonical worker host identity and paths
The worker SHALL use the declared hostname, service account, group and `/srv/atenea` filesystem skeleton with least-privilege ownership before repository or runtime data is introduced.

#### Scenario: Baseline is applied twice
- **WHEN** bootstrap is rerun against a compliant host
- **THEN** identities, permissions and paths remain correct without destroying or taking ownership of unrelated data

### Requirement: Security-update policy
The worker SHALL install supported security updates and enable unattended security patching while keeping automatic reboot disabled until an observed maintenance action.

#### Scenario: Update requires reboot
- **WHEN** the package manager reports a pending reboot
- **THEN** bootstrap records it and does not reboot outside the explicit acceptance step

### Requirement: Local health verification timer
The worker SHALL periodically verify RAID membership, resync/degraded state, NVMe SMART health, filesystem thresholds, time synchronization, sshd syntax, firewall state and required baseline identities.

#### Scenario: RAID becomes degraded
- **WHEN** the periodic check no longer observes both members active
- **THEN** the check fails critically and records an actionable degraded-array result in journald and structured output

### Requirement: Reboot acceptance
The secure baseline MUST pass a controlled reboot test covering host identity, SSH access, firewall, RAID, SMART, time synchronization and health timer state before the phase is accepted.

#### Scenario: Required service does not return after reboot
- **WHEN** post-reboot verification detects a missing or invalid baseline service
- **THEN** the phase remains incomplete and rollback or repair is performed before workloads are introduced

### Requirement: Bootstrap configuration rollback
Before changing sshd, firewall or managed systemd configuration, automation SHALL create a timestamped local rollback copy and document restoration through an active session or Hetzner rescue path.

#### Scenario: New sshd policy fails after reload
- **WHEN** fresh access cannot be proven from the named administrator
- **THEN** the original active recovery session restores the previous configuration and verifies service syntax before another attempt

### Requirement: Authenticated preview control protocol

The AX42 preview coordinator SHALL expose a versioned authenticated control
protocol only to Atenea over the approved private network. Requests SHALL carry
the immutable preview, WorkSession, project, worker, allocation and expected
revision identities; malformed, stale or conflicting requests SHALL mutate
nothing.

#### Scenario: Duplicate activation is retried

- **WHEN** Atenea repeats an identical activation after losing the response
- **THEN** the worker returns the same preview route and revision without
  starting another listener

#### Scenario: Stale revision is submitted

- **WHEN** a client submits an ownership-valid request with an older lifecycle
  revision
- **THEN** the worker returns conflict and leaves the current projection
  unchanged

### Requirement: Non-empty real attachment backup activation

Real Atenea attachment activation SHALL consume the accepted independent
encrypted backup contract only after a non-secret real-project canary exists
under the declared attachment root and a newer exact-policy snapshot,
repository check and empty-target isolated restore prove its sidecar/content
size and SHA-256 identity. Failure MUST disable new upload/bind before any
rollback and MUST NOT run retention, delete the canary or alter existing
snapshots.

#### Scenario: Real attachment canary is protected

- **WHEN** one exact Atenea canary attachment has been accepted and the bounded
  backup/check/restore sequence passes
- **THEN** the external-backup prerequisite is satisfied for the exact project
  gate without retaining its bytes, filename, prompt or answer in evidence

#### Scenario: Backup or restore does not verify

- **WHEN** snapshot, check, restore or normalized identity comparison fails or
  times out
- **THEN** project then global create/bind are disabled, no prune/delete runs
  and existing content plus diagnostic evidence remain intact

#### Scenario: Backup source policy omits attachment content

- **WHEN** the installed policy no longer selects the canonical attachment root
  or selects an undeclared credential/runtime boundary
- **THEN** real activation is blocked before the project gate changes
