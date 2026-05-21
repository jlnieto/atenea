#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib/compose.sh"

cd "$REPO_DIR"

compose -f docker-compose.dev.yml up -d --build db codex-app-server
compose -f docker-compose.dev.yml run --rm atenea-dev \
  /bin/bash -lc 'umask 0002; exec /bin/bash'
