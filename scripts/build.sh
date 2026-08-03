#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib/compose.sh"

cd "$REPO_DIR"

if [[ "${ATENEA_BUILD_SKIP_WEB:-false}" != "true" && -f "$REPO_DIR/web/package.json" ]]; then
  "$SCRIPT_DIR/web-build.sh"
fi

if [[ "${ATENEA_BUILD_RUN_TESTS:-false}" == "true" ]]; then
  compose -f docker-compose.dev.yml run --rm atenea-dev \
    /bin/sh -lc 'umask 0002 && exec ./mvnw clean package "$@"' sh "$@"
else
  compose -f docker-compose.dev.yml run --rm atenea-dev \
    /bin/sh -lc 'umask 0002 && exec ./mvnw clean package -DskipTests "$@"' sh "$@"
fi
