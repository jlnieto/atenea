# Invalid manifest corpus

Every JSON document in this directory is expected to fail
`project-runtime-v1.schema.json`.

| Fixture | Expected rejection |
|---|---|
| `absolute-path.json` | repository/runtime paths must not be host-absolute |
| `daemon-socket-mount.json` | arbitrary mounts and daemon authority are unsupported |
| `host-network.json` | host networking is unsupported |
| `literal-secret.json` | secret values are not manifest fields |
| `missing-lifecycle.json` | all lifecycle operations are required |
| `parent-traversal.json` | repository-relative paths cannot escape the worktree |
| `privileged.json` | privileged execution is unsupported |
| `wrong-schema-version.json` | only schema version 1 is accepted |
