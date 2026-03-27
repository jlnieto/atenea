#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_DIR"

docker compose -f docker-compose.dev.yml up -d db-test codex-app-server

docker compose -f docker-compose.dev.yml run --rm --no-deps \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://db-test:5432/atenea_test \
  -e SPRING_DATASOURCE_USERNAME=atenea \
  -e SPRING_DATASOURCE_PASSWORD=atenea \
  atenea-dev ./mvnw test "$@"
