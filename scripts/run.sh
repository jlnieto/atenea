#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib/compose.sh"

cd "$REPO_DIR"

STALE_RUN_CONTAINERS="$(
  docker ps -aq --filter "name=^atenea-atenea-dev-run-" --filter "status=running"
)"

if [[ -n "$STALE_RUN_CONTAINERS" ]]; then
  docker rm -f $STALE_RUN_CONTAINERS >/dev/null
fi

compose -f docker-compose.dev.yml up -d --build db codex-app-server atenea-dev
compose -f docker-compose.dev.yml exec atenea-dev \
  /bin/sh -lc 'umask 0002 && exec ./mvnw spring-boot:run "$@"' sh "$@"
