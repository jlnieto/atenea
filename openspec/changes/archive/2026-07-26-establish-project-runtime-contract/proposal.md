## Why

The AX42 secure baseline is accepted, but it cannot yet execute Codex or project
workloads. The laptop's `dev` workflow depends on fixed local paths, fixed ports,
one user's systemd services and project configuration containing undeclared
secrets. Reproducing that home directory would preserve the laptop's limitations
and weaken the worker boundary.

This phase establishes the runtime contract before Atenea routes any real
AgentRun. It also provides a clearly temporary administrative bridge so the
operator can use Codex on the AX42 while the managed worker protocol is built.

## What Changes

- Define and validate a versioned project runtime manifest for toolchains,
  lifecycle, health, previews, browser checks, artifacts, secrets and workload
  class.
- Replace fixed laptop paths and ports with session-owned worktrees, runtime
  identities and allocations beneath `/srv/atenea`.
- Adapt `dev` to keep the familiar operator commands and add explicit session
  selection and stable JSON output.
- Install pinned host/runtime prerequisites and build dummy Docker and legacy
  Tomcat fixtures.
- Prototype a mediated runtime boundary in which Codex never receives the host
  Docker socket, arbitrary host mounts or access to another session workspace.
- Install Codex independently on the AX42, authenticate it through the supported
  headless flow, and compare the effective non-secret operating context with the
  laptop.
- Provide an administrative SSH/tmux bridge for immediate persistent use. It is
  not an Atenea AgentRun executor and does not weaken the later isolation gate.
- Keep Atenea production routing and all real projects disabled until the phase
  acceptance evidence passes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `isolated-project-runtime`: Make the manifest, allocation, mediated runtime and
  `dev` contracts concrete and testable.
- `codex-environment-parity`: Define sanitized configuration promotion,
  independent authentication and version verification for the worker.

## Impact

- Adds runtime schema, `dev`, broker/sandbox prototype, fixtures, tests and
  operational installation automation to the programme repository.
- Installs selected packages and Codex on the AX42 and creates only
  non-authoritative dummy or administrative state.
- Does not copy laptop secrets or uncommitted project files.
- Does not modify Atenea APIs, database schema, production containers or current
  AgentRun routing.
- Does not make a real project schedulable; project onboarding remains a
  separate acceptance gate.
