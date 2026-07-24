## ADDED Requirements

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
