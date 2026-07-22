## ADDED Requirements

### Requirement: Reproducible hardened worker baseline
Worker users, packages, SSH policy, firewall, private networking, runtime services and monitoring SHALL be defined by versioned automation and validated for drift.

#### Scenario: Fresh worker is provisioned
- **WHEN** the baseline automation is applied to a supported Ubuntu host
- **THEN** it produces the declared security and service state without depending on undocumented manual shell history

### Requirement: Private service exposure
Worker API, Codex App Server, runtime control endpoints, databases and previews MUST bind only to loopback, isolated runtime networks or approved private interfaces.

#### Scenario: Public interface is scanned
- **WHEN** an unauthenticated Internet client scans the worker
- **THEN** only explicitly approved bootstrap or break-glass services are reachable

### Requirement: Least-privilege execution
Routine Codex and project workloads SHALL run without host root authority and without credentials or capabilities unrelated to their session.

#### Scenario: Runtime command is compromised
- **WHEN** a session process attempts a privileged host operation
- **THEN** OS and runtime controls prevent it from gaining host root or controlling another session

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
Critical configuration, encrypted secrets, authoritative metadata and non-recreatable artifacts MUST be backed up outside the worker with defined retention and tested restoration; caches and regenerable dependencies MUST be excluded.

#### Scenario: Worker is lost completely
- **WHEN** a replacement host is provisioned
- **THEN** documented automation plus Git and backup data can restore the worker contract without relying on either failed RAID member

### Requirement: Operational observability
Operators SHALL be able to inspect worker health, slot use, queue depth, run age, CPU, memory, disk, runtime failures, preview state and cleanup failures from Atenea or linked diagnostics.

#### Scenario: Run makes no progress
- **WHEN** a run exceeds its workload-specific progress threshold
- **THEN** Atenea surfaces a stale warning with worker, session and recovery action rather than silently waiting forever

### Requirement: Safe garbage collection
The worker SHALL identify and clean orphaned containers, worktrees, ports and temporary artifacts only after proving they are not owned by an active or recoverable session.

#### Scenario: Worker restarts with orphaned resources
- **WHEN** reconciliation finds resources without a valid active lease
- **THEN** they are quarantined or removed according to retention policy and the action is audited

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
