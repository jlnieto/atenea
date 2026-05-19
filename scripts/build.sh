#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib/compose.sh"

cd "$REPO_DIR"

if [[ "${ATENEA_BUILD_RUN_TESTS:-false}" == "true" ]]; then
  compose -f docker-compose.dev.yml run --rm atenea-dev ./mvnw clean package "$@"
else
  compose -f docker-compose.dev.yml run --rm atenea-dev ./mvnw clean package -DskipTests "$@"
fi
