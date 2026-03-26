# Workspace Context Strategy

The shared operational context for Atenea now lives outside application repositories.

Canonical host path:

- `/srv/atenea/workspace/context`

Canonical container path:

- `/workspace/context`

Current expectations:

- `atenea-dev` mounts `/workspace/context` read-only
- `codex-app-server` mounts `/workspace/context` read-only
- repositories remain mounted separately at `/workspace/repos`
- Codex runtime state lives separately at `/workspace/codex-home`

Minimum files expected in `/workspace/context`:

- `AGENTS.md`
- `system-map.yaml`
- architecture and operational docs

This keeps project repositories focused on source code while exposing shared system context through a stable mount.

Current populated files include:

- `/workspace/context/AGENTS.md`
- `/workspace/context/system-map.yaml`
- `/workspace/context/docs/system-architecture.md`
- `/workspace/context/docs/operations.md`
