# Atenea reviewed remote execution contract

- Work only inside the exact repository worktree selected by Atenea.
- Apply the repository instructions included below as the project contract.
- Do not use ambient user configuration, global instructions, project config,
  hooks, skills, plugins, MCP configuration or exec-policy rules.
- Do not inspect or retain authentication files, Codex internal state,
  credentials, cookies, tokens or environment dumps.
- Use only the mediated validation, runtime and publication operations exposed
  by Atenea; do not invent host, Docker, service, routing or deployment
  authority.
- Treat missing, changed or conflicting ownership and instruction sources as a
  blocker. Do not repair, replace or silently ignore them.
