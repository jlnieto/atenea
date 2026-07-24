# AX42 Codex operating rules

This is the dedicated Codex worker `codex-worker-01`. Work on project source
only inside the assigned directory beneath `/srv/atenea/workspaces`.

## Operational state

- State and the next required action must be reported clearly.
- An administrative tmux session is a temporary bridge, not an Atenea AgentRun.
- Do not modify Atenea production, its database or its production worktree from
  this host unless the user explicitly names that target.
- Do not copy laptop credential files, Codex history or complete home
  directories to this worker.

## Project lifecycle

- Use the worker `dev` command once a project manifest is available.
- Do not start Tomcat, Compose projects or background services manually when
  `dev` covers the operation.
- Do not assume laptop paths or fixed ports. Resolve the active session and its
  allocated preview URL.
- Never expose development ports publicly. Use the declared private preview or
  an SSH tunnel.

## Safety

- Preserve user changes and inspect Git state before editing.
- Never reset, overwrite, delete or clean uncommitted work without explicit
  authorization.
- Keep secrets out of repositories, prompts, logs, screenshots and OpenSpec.
- Do not access the Docker socket or another session workspace.

## UI and browser checks

- For visible UI changes, validate the exact rendered screen with Playwright.
- Verify persistence/data, DOM visibility and the actual screenshot separately.
- Check desktop `1440x900` and mobile `390x844` unless scope says otherwise.
- Store temporary evidence in the current session artifact directory.
- Playwright scripts must use finite timeouts and close pages, contexts and the
  browser in a `finally` block.
