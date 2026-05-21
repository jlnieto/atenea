#!/usr/bin/env bash
set -euo pipefail

WORKSPACE_ROOT="${ATENEA_WORKSPACE_REPOS_ROOT:-/srv/atenea/workspace/repos}"
WORKSPACE_GROUP="${ATENEA_WORKSPACE_GROUP:-atenea}"

if [[ ! -d "$WORKSPACE_ROOT" ]]; then
  echo "Workspace root not found: $WORKSPACE_ROOT" >&2
  exit 1
fi

if ! getent group "$WORKSPACE_GROUP" >/dev/null; then
  echo "Workspace group not found: $WORKSPACE_GROUP" >&2
  exit 1
fi

echo "Normalizing workspace permissions"
echo "  root:  $WORKSPACE_ROOT"
echo "  group: $WORKSPACE_GROUP"

chgrp -hR "$WORKSPACE_GROUP" "$WORKSPACE_ROOT"
chmod -R g+rwX "$WORKSPACE_ROOT"
find "$WORKSPACE_ROOT" -type d -exec chmod g+s {} +

if command -v setfacl >/dev/null 2>&1; then
  setfacl -R -m "g:${WORKSPACE_GROUP}:rwX" -m "d:g:${WORKSPACE_GROUP}:rwX" "$WORKSPACE_ROOT"
else
  echo "setfacl not found; skipping default ACLs" >&2
fi

while IFS= read -r git_dir; do
  repo_dir="$(dirname "$git_dir")"
  git -C "$repo_dir" config core.sharedRepository group
done < <(find "$WORKSPACE_ROOT" -type d -name .git -prune -print)

echo "Workspace permissions normalized"
