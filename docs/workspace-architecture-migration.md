# Atenea Workspace Architecture Migration

## Purpose

This document records the architectural decisions that shaped the current Atenea workspace layout.

It remains in the repository as migration history and as a compact explanation of why the filesystem now looks the way it does.

## Decision model

### Platform

`platform` means Atenea running as a system.

It includes:

- deployment stacks
- runtime services
- Codex runtime homes
- operational tooling

It does **not** mean the Atenea source repository itself.

### Workspace

`workspace` means the repositories that Atenea can orchestrate and operate on.

It includes:

- internal repositories
- client repositories
- sandbox repositories
- the Atenea source repository itself

## Final repository placement

Canonical Atenea source path:

- `/srv/atenea/workspace/repos/internal/atenea`

The temporary duplicate at `/srv/atenea/apps/atenea` has been retired.

## Final filesystem model

```text
/srv/atenea
  /workspace
    /repos
      /internal
        /atenea
        /wab
        /fms
      /clients
        /isc
        /rmc
        /edi
      /sandboxes
        /smoke
        /internal
    /context
      /AGENTS.md
      /system-map.yaml
      /docs
    /codex-home
      /prod
      /preview
  /platform
    /stacks
      /prod
      /preview
```

## Runtime/container model

Managed Atenea platform services use:

- repos: `/workspace/repos`
- context: `/workspace/context`
- Codex runtime home: `/workspace/codex-home`

`prod` and `preview` use separate stack directories, databases and Codex homes.

## Domain decision

### `repoPath`

`repoPath` remains in `Project` and identifies the repository Atenea operates on.

Examples:

- `/workspace/repos/internal/atenea`
- `/workspace/repos/internal/wab`
- `/workspace/repos/sandboxes/internal/atenea-preview`

### `workspaceRoot`

`workspaceRoot` is no longer persisted in `Project`.

It is a platform-level configuration exposed through:

- `ATENEA_WORKSPACE_ROOT`

## Completed migration points

Completed:

1. Atenea works from `/srv/atenea/workspace/repos/internal/atenea`
2. `workspace/context` and dedicated `codex-home` directories exist
3. `workspaceRoot` moved out of project persistence
4. Atenea platform stacks were split into `prod` and `preview`
5. the old `/srv/atenea/apps/atenea` duplicate was removed

## Remaining follow-up

Remaining work is no longer the Atenea workspace migration itself. It is mainly:

- documentation hygiene for the whole host
- migration or retirement of any still-running legacy containers outside the new managed platform stacks
