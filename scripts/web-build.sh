#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_DIR/web"

if [[ ! -d node_modules || package-lock.json -nt node_modules/.package-lock.json ]]; then
  npm ci
fi

rm -rf "$REPO_DIR/src/main/resources/static/assets"
npm run build
